#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -x backend/.venv/bin/python ]; then
  PYTHON=backend/.venv/bin/python
elif [ -x backend/.venv/Scripts/python.exe ]; then
  PYTHON=backend/.venv/Scripts/python.exe
else
  PYTHON=python
fi
"$PYTHON" -m pytest backend/tests --cov=backend/app --cov-report=json:backend/coverage.json -q
"$PYTHON" tests/write_coverage.py
