"""Sphinx configuration for the corbelsflightlog documentation.

Sphinx has no working Java autodoc (javasphinx has been unmaintained since
Python 2), so these pages are written by hand and the API reference is
Javadoc, built by Gradle and published beside them by docs/build_site.py.
"""

import os

project = "corbelsflightlog"
author = "Catholic Central Spires Robotics"
copyright = "2026, Catholic Central Spires Robotics"

# The version being built: set by docs/build_site.py ("dev" or a release tag).
version = release = os.environ.get("FLIGHTLOG_VERSION", "dev")

extensions = ["myst_parser"]
myst_enable_extensions = ["colon_fence", "deflist"]

templates_path = ["_templates"]
exclude_patterns = ["_build", "_site", "Thumbs.db", ".DS_Store"]

html_theme = "furo"
html_title = f"corbelsflightlog {version}"
html_static_path = ["_static"]
html_js_files = ["versions.js"]        # the version switcher
html_theme_options = {
    "source_repository": "https://github.com/msb-stitt/corbelsflightlog/",
    "source_branch": "main",
    "source_directory": "docs/",
}
