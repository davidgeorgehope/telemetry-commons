# telemetry-coverage

Audit this codebase against the business telemetry contract (business-telemetry.mdc) and report for a product-manager audience.

1. Scan for all span creation sites and the attributes they carry (directly or via the Baggage processor allowlist).
2. Report, in plain language: which business questions current telemetry CAN answer (e.g. "usage by tenant: yes — tenant.id is on every request-path span"), and which it CANNOT, with the missing attribute or path named.
3. For each gap, state the one-line contract change that would close it (new constant + contract line), sized as small/medium/large. Do not modify code. Output is a report, not a diff.
