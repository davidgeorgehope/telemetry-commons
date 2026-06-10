#!/usr/bin/env python3
"""Lint added Java lines in a diff against the Northgate business-telemetry contract.

This is a fast, dependency-free PR gate. It inspects only ADDED lines from
`git diff --unified=0 ${BASE_REF:-origin/main}...HEAD -- '*.java'` and applies
three heuristics:

  CHECK A (edge coverage): a file under /api/ or /http/ (or containing
    @RequestPath) that ADDS an HTTP handler method must establish business
    context somewhere in that file via `withBusinessContext(`.
  CHECK B (anti-drift): an ADDED `setAttribute(...)` referencing
    NorthgateAttributes.CUSTOMER_ID or TENANT_ID anywhere except
    processors/BaggageEnrichingSpanProcessor.java is forbidden — the processor
    owns ubiquitous attributes.
  CHECK C (span-local contract): an ADDED `spanBuilder(`/`startSpan(` in an
    order- or shipment-domain package must have the matching NorthgateAttributes
    ID (ORDER_ID / SHIPMENT_ID) within +/-12 lines, unless @InternalSpan appears
    in that window.

HONEST LIMITATIONS — read before trusting this:
  * It is a line-window/regex heuristic with NO AST. It sees added text, not
    semantics. String literals, comments, and multi-line statements can fool it.
  * The +/-12 line window is arbitrary. An attribute set in a called helper, a
    loop body, or 13 lines away is a false NEGATIVE; an unrelated nearby ID is a
    false POSITIVE.
  * It cannot follow helper indirection. If business context is established or an
    attribute is set inside another method/class, this script does not see it.
  * "HTTP handler method" detection is pattern-based (@RequestPath, HttpExchange
    signatures, `handle(`) and will both miss and over-match real handlers.
  * Package classification (order-/shipment-domain by path) is a DECLARED
    CONVENTION, not proof of domain ownership. Renaming a package changes the
    verdict; the code's actual behavior does not.
  * Only ADDED lines are checked. Pre-existing violations and lines removed in
    the diff are invisible. This gates new drift, it does not audit the codebase.

Exit code: 1 if any violation is found (with a file:line report), else 0.
A git failure exits 2. No violations / empty diff exits 0.
"""

import os
import re
import subprocess
import sys


HANDLER_RE = re.compile(
    r"@RequestPath\b"
    r"|\bvoid\s+handle\s*\(\s*HttpExchange"
    r"|\b(?:public|protected|private)\s+[\w.<>\[\]]+\s+\w+\s*\([^;{]*HttpExchange"
)
DRIFT_RE = re.compile(r"setAttribute\s*\(\s*NorthgateAttributes\.(CUSTOMER_ID|TENANT_ID)\b")
SPAN_RE = re.compile(r"\b(?:spanBuilder|startSpan)\s*\(")
INTERNAL_SPAN_RE = re.compile(r"@InternalSpan\b")
WITH_CONTEXT_RE = re.compile(r"\bwithBusinessContext\s*\(")

CONTEXT_WINDOW = 12
PROCESSOR_SUFFIX = "processors/BaggageEnrichingSpanProcessor.java"


class Violation:
    def __init__(self, path, line, rule, message):
        self.path = path
        self.line = line
        self.rule = rule
        self.message = message

    def __str__(self):
        return f"{self.path}:{self.line}: [{self.rule}] {self.message}"


def run_git_diff(base_ref):
    cmd = ["git", "diff", "--unified=0", f"{base_ref}...HEAD", "--", "*.java"]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True)
    except OSError as exc:
        print(f"error: failed to run git: {exc}", file=sys.stderr)
        sys.exit(2)
    if proc.returncode != 0:
        print(f"error: `{' '.join(cmd)}` failed:\n{proc.stderr}", file=sys.stderr)
        sys.exit(2)
    return proc.stdout


def parse_added_lines(diff_text):
    """Return {path: [(new_lineno, text), ...]} for added lines (unified=0)."""
    hunk_re = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
    added = {}
    current = None
    new_lineno = 0
    for raw in diff_text.splitlines():
        if raw.startswith("+++ "):
            target = raw[4:].strip()
            if target == "/dev/null":
                current = None
            else:
                current = target[2:] if target.startswith("b/") else target
                added.setdefault(current, [])
            continue
        if raw.startswith("--- ") or raw.startswith("diff --git") or raw.startswith("index "):
            continue
        m = hunk_re.match(raw)
        if m:
            new_lineno = int(m.group(1))
            continue
        if current is None:
            continue
        if raw.startswith("+"):
            added[current].append((new_lineno, raw[1:]))
            new_lineno += 1
        elif raw.startswith("-") or raw.startswith("\\"):
            # deletions / "No newline" markers do not advance new-file numbering
            continue
    return added


def read_file_lines(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read().splitlines()
    except OSError:
        return None


def window_text(file_lines, lineno):
    if file_lines is None:
        return ""
    lo = max(0, lineno - 1 - CONTEXT_WINDOW)
    hi = min(len(file_lines), lineno - 1 + CONTEXT_WINDOW + 1)
    return "\n".join(file_lines[lo:hi])


def package_dir(path):
    slash = path.rfind("/")
    return path[:slash].lower() if slash != -1 else ""


def check_edge_coverage(path, added, file_text, violations):
    path_l = path.lower()
    is_edge = "/api/" in path_l or "/http/" in path_l or "@RequestPath" in (file_text or "")
    if not is_edge:
        return
    handler_lines = [ln for ln, text in added if HANDLER_RE.search(text)]
    if not handler_lines:
        return
    if file_text and WITH_CONTEXT_RE.search(file_text):
        return
    violations.append(Violation(
        path, handler_lines[0], "CHECK A",
        "edge does not establish business context "
        "(adds an HTTP handler but no withBusinessContext( in this file)"))


def check_anti_drift(path, added, violations):
    if path.endswith(PROCESSOR_SUFFIX):
        return
    for lineno, text in added:
        m = DRIFT_RE.search(text)
        if m:
            violations.append(Violation(
                path, lineno, "CHECK B",
                f"manual ubiquitous attribute (NorthgateAttributes.{m.group(1)}) "
                "— the processor owns these"))


def check_span_local(path, added, file_lines, violations):
    pkg = package_dir(path)
    is_order = "order" in pkg
    is_shipment = "shipment" in pkg
    if not (is_order or is_shipment):
        return
    for lineno, text in added:
        if not SPAN_RE.search(text):
            continue
        window = window_text(file_lines, lineno)
        if INTERNAL_SPAN_RE.search(window):
            continue
        required = []
        if is_order:
            required.append("ORDER_ID")
        if is_shipment:
            required.append("SHIPMENT_ID")
        satisfied = any(f"NorthgateAttributes.{rid}" in window for rid in required)
        if not satisfied:
            ids = " or ".join(f"NorthgateAttributes.{rid}" for rid in required)
            violations.append(Violation(
                path, lineno, "CHECK C",
                f"span in {'/'.join(d for d in ['order' if is_order else '', 'shipment' if is_shipment else ''] if d)}"
                f"-domain package missing {ids} within +/-{CONTEXT_WINDOW} lines "
                "(annotate @InternalSpan to exempt)"))


def main():
    base_ref = os.environ.get("BASE_REF", "origin/main")
    diff_text = run_git_diff(base_ref)
    added = parse_added_lines(diff_text)

    violations = []
    for path, added_lines in added.items():
        if not added_lines:
            continue
        file_lines = read_file_lines(path)
        file_text = "\n".join(file_lines) if file_lines is not None else None

        check_edge_coverage(path, added_lines, file_text, violations)
        check_anti_drift(path, added_lines, violations)
        check_span_local(path, added_lines, file_lines, violations)

    if violations:
        violations.sort(key=lambda v: (v.path, v.line))
        print(f"Telemetry contract: {len(violations)} violation(s) "
              f"(base {base_ref}...HEAD)\n")
        for v in violations:
            print(f"  {v}")
        print("\nSee ci/check_telemetry_contract.py for rule definitions and limitations.")
        return 1

    print(f"Telemetry contract: OK (no violations in {base_ref}...HEAD).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
