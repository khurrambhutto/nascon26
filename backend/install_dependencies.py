"""Install the exact Linux wheel lock; optional mirror is only a local build cache.

All artifacts retain their PyPI SHA-256 checksums when a mirror is used.
The ordinary clean-clone build downloads directly from PyPI.
"""
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit

source = Path("requirements-linux.lock").read_text()
cache_secret = Path("/run/secrets/wheel_mirror")
mirror = (cache_secret.read_text().strip() if cache_secret.exists() else "").rstrip("/")
if mirror:
    lines = []
    for line in source.splitlines():
        fields = line.split()
        if len(fields) >= 4 and fields[1] == "@":
            fields[2] = mirror + "/" + fields[2].rsplit("/", 1)[1]
            line = " ".join(fields)
        lines.append(line)
    source = "\n".join(lines) + "\n"
Path("/tmp/install.lock").write_text(source)
command = [sys.executable, "-m", "pip", "install", "--no-cache-dir", "--require-hashes", "--no-deps", "--retries", "5", "--timeout", "30", "-r", "/tmp/install.lock"]
if mirror:
    command.extend(["--trusted-host", urlsplit(mirror).hostname])
subprocess.run(command, check=True)
