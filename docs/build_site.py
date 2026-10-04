#!/usr/bin/env python3
"""Builds the documentation site for one version.

Produces docs/_site/<version>/ containing the Sphinx pages and, under
javadoc/, the API reference Gradle built. Also writes versions.json and a
root index.html that redirects to the newest release, so publishing with
keep_files keeps every version side by side:

    <site root>/
        index.html        -> redirects to the newest release
        versions.json     -> ["v1.1.0", "v1.0.0", "dev"]
        v1.1.0/           -> pages + javadoc/core, javadoc/pedro
        dev/

Usage:  python docs/build_site.py --version v1.2.3
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

DOCS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(DOCS)
SITE = os.path.join(DOCS, "_site")
MODULES = ("core", "pedro", "wpilib", "ftc")


def published_versions(repo):
    """Versions already on the site, so this build doesn't drop them.

    Publishing uses keep_files, which keeps the other versions' directories but
    overwrites versions.json -- so the list has to be rebuilt from what is
    already published, plus anything already in _site locally.
    """
    found = []
    if os.path.isdir(SITE):
        found = [d for d in os.listdir(SITE) if os.path.isdir(os.path.join(SITE, d))]
    if not repo:
        return found
    url = "https://%s.github.io/%s/versions.json" % tuple(repo.split("/", 1))
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            found += json.load(r).get("versions", [])
    except (urllib.error.URLError, ValueError, OSError) as e:
        # Expected on the very first publish; otherwise the switcher would
        # silently lose versions, so say so.
        print("warning: could not read %s (%s). If the site already has other "
              "versions, they will drop out of the switcher." % (url, e))
    return found


def sort_key(v):
    """Releases newest first, then dev."""
    if v == "dev":
        return (0,)
    parts = v.lstrip("v").split(".")
    return (1,) + tuple(int(p) if p.isdigit() else 0 for p in parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help='a tag such as v1.2.3, or "dev"')
    args = ap.parse_args()
    version = args.version

    out = os.path.join(SITE, version)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out, exist_ok=True)

    env = dict(os.environ, FLIGHTLOG_VERSION=version)
    subprocess.run([sys.executable, "-m", "sphinx", "-b", "html", "-W", DOCS, out],
                   check=True, env=env)

    for module in MODULES:
        javadoc = os.path.join(ROOT, module, "build", "docs", "javadoc")
        if os.path.isdir(javadoc):
            shutil.copytree(javadoc, os.path.join(out, "javadoc", module))
        else:
            print("warning: no Javadoc for %s (run ./gradlew :%s:javadoc)" % (module, module))

    versions = published_versions(os.environ.get("GITHUB_REPOSITORY", ""))
    versions = sorted(set(versions) | {version}, key=sort_key, reverse=True)
    versions.sort(key=sort_key, reverse=True)
    with open(os.path.join(SITE, "versions.json"), "w") as f:
        json.dump({"versions": versions}, f, indent=2)

    newest = next((v for v in versions if v != "dev"), versions[0])
    with open(os.path.join(SITE, "index.html"), "w") as f:
        f.write('<!doctype html><meta http-equiv="refresh" content="0; url=./%s/">'
                '<a href="./%s/">corbelsflightlog documentation</a>\n' % (newest, newest))
    print("built %s -> %s (versions: %s)" % (version, out, ", ".join(versions)))


if __name__ == "__main__":
    main()
