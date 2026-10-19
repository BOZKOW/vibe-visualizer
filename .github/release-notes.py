"""Release notes for an app release: every change since the app's previous release, from the commits' bullet points.

    python3 .github/release-notes.py <tag-prefix> <tag> <intro-file-text> <path> [<path> ...]  > notes.md
"""
import subprocess, sys

prefix, tag, intro, paths = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
try:
    prev = subprocess.run(["git", "describe", "--tags", "--abbrev=0", "--match", prefix + "*", tag + "^"],
                          capture_output=True, text=True, check=True).stdout.strip()
except subprocess.CalledProcessError:
    prev = ""
log = subprocess.run(["git", "log", "--no-merges", "--format=%x1e%s%n%b", (prev + ".." if prev else "") + tag, "--"] + paths,
                     capture_output=True, text=True, check=True).stdout
pts = []
for c in log.split("\x1e"):
    lines = c.strip().splitlines()
    if not lines:
        continue
    mine = []
    for l in lines[1:]:
        if l.strip().startswith("- "):
            mine.append(l.strip()[2:].strip())
        elif mine and l.startswith("  ") and l.strip():
            mine[-1] += " " + l.strip()
    pts += mine or [lines[0]]
print(intro + "\n")
print("### What's new" + (" since " + prev[len(prefix):] if prev else "") + "\n")
print("\n".join("- " + p for p in pts[:40]))
