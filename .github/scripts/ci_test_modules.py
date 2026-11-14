#!/usr/bin/env python3
"""Select CI test modules from CHANGED_* booleans, never shell expressions."""

from __future__ import annotations

import os
import sys
from collections.abc import Mapping

CHANGE_FLAGS = (
    "build",
    "shared",
    "protocol",
    "channel",
    "routing",
    "resilience",
    "oauth",
    "quota",
    "pricing",
    "requestlog",
    "admin",
    "app",
)

# Keep the existing affected-module order; routing is added after its upstreams.
DIRECT_MODULES = (
    ("protocol", "prism-protocol"),
    ("channel", "prism-channel"),
    ("resilience", "prism-resilience"),
    ("oauth", "prism-subscription-oauth"),
    ("quota", "prism-quota"),
    ("pricing", "prism-pricing"),
    ("requestlog", "prism-request-log"),
    ("admin", "prism-admin"),
)
ALL_UPSTREAM_MODULES = (
    "prism-shared",
    "prism-protocol",
    "prism-channel",
    "prism-routing",
    "prism-resilience",
    "prism-subscription-oauth",
    "prism-quota",
    "prism-pricing",
    "prism-request-log",
    "prism-admin",
)
FULL_APP_FLAGS = {"app", "routing", "resilience", "oauth", "quota", "pricing", "requestlog", "admin"}


def resolve_modules(changes: Mapping[str, bool]) -> dict[str, str]:
    changed = {flag for flag in CHANGE_FLAGS if changes.get(flag, False)}
    app_tags = ""
    # Shared kernel and root build changes test every upstream and the full app.
    if changed & {"build", "shared"}:
        modules = list(ALL_UPSTREAM_MODULES)
        app_tests = True
    else:
        modules = [module for flag, module in DIRECT_MODULES if flag in changed]
        # Channel/protocol changes also affect routing compilation and tests.
        if changed & {"channel", "protocol", "routing"}:
            modules.append("prism-routing")
        app_tests = bool(modules) or "app" in changed
        # Unit tests always run in full. Only pure channel/protocol changes may
        # trim integration tests; admin (including mixed changes) needs all app tests.
        if not changed & FULL_APP_FLAGS:
            app_tags = "|".join(flag for flag in ("channel", "protocol") if flag in changed)
    return {
        "modules": ",".join(modules),
        "app_tests": "true" if app_tests else "false",
        "app_tags": app_tags,
    }


def changes_from_env(environment: Mapping[str, str]) -> dict[str, bool]:
    changes = {}
    for flag in CHANGE_FLAGS:
        name = f"CHANGED_{flag.upper()}"
        value = environment.get(name)
        if value not in ("true", "false"):
            # Fail closed on missing/malformed filter outputs, without echoing input.
            raise ValueError(f"{name} must be 'true' or 'false'")
        changes[flag] = value == "true"
    return changes


def main() -> int:
    outputs = resolve_modules(changes_from_env(os.environ))
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        raise ValueError("GITHUB_OUTPUT is required")
    with open(output_path, "a", encoding="utf-8") as handle:
        for key, value in outputs.items():
            handle.write(f"{key}={value}\n")
    print(f"Affected upstream test modules: {outputs['modules'] or 'none'}")
    print(f"App tests: {outputs['app_tests']} (tags: {outputs['app_tags'] or 'all'})")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
