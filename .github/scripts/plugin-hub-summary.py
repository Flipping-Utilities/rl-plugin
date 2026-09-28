#!/usr/bin/env python3
"""Render packager output as text, never as trusted Markdown or workflow commands."""

import html
import os
from pathlib import Path
import re
import sys


output = Path(sys.argv[1])
status = int(sys.argv[2])
parts = ["## RuneLite Plugin Hub check\n", f"**{'Passed' if status == 0 else 'Failed'}** (exit {status}).\n"]
environment = output / "environment.txt"
if environment.exists():
    parts.append(f"<pre>{html.escape(environment.read_text())}</pre>\n")
jar = output / "flipping-utilities.jar"
if jar.exists():
    parts.append(f"Package size: {jar.stat().st_size:,} bytes.\n")
parts.append(
    "This runs RuneLite's build, metadata, dependency, API and package checks. "
    "It does not perform Plugin Hub's separate automated or human review.\n"
)
log = output / "packager.log"
if log.exists():
    text = log.read_text(errors="replace")
    text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)
    excerpt = "\n".join(text.splitlines()[-100:])[-16000:]
    parts.append(f"<details open><summary>Last 100 log lines</summary><pre>{html.escape(excerpt)}</pre></details>\n")
parts.append("The `plugin-hub-check` artifact includes the full log and any generated package.\n")
report = "\n".join(parts)
(output / "summary.md").write_text(report)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(report)
