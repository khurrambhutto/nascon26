#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -x backend/.venv/bin/python ]; then
  PYTHON=backend/.venv/bin/python
elif command -v docker >/dev/null 2>&1; then
  docker compose run --rm --no-deps -v "$PWD:/workspace" -w /workspace api sh -c 'python -m pytest backend/tests --cov=backend/app --cov-report=json:backend/coverage.json -q && python tests/write_coverage.py'
  exit 0
else
  PYTHON=python
fi
"$PYTHON" -m pytest backend/tests --cov=backend/app --cov-report=json:backend/coverage.json -q
"$PYTHON" tests/write_coverage.py
