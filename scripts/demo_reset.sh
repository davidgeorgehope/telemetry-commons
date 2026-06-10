#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
git checkout -f main
git reset --hard demo-baseline
git clean -fd -e .gradle -e build -e .cursor
git branch -f demo/bad-span demo-bad-span-baseline
git branch -f demo/hidden-violation demo-hidden-baseline
if [ -d .cursor/rules.off ]; then rm -rf .cursor/rules; mv .cursor/rules.off .cursor/rules; fi
./gradlew test -q && echo "RESET OK - baseline green" || { echo "RESET FAILED"; exit 1; }
