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


def resolve_modules(changes: Mapping[str, bool], full_regression: bool = False) -> dict[str, str]:
    changed = {flag for flag in CHANGE_FLAGS if changes.get(flag, False)}
    app_tags = ""
    # Main's backend baseline and shared/root build changes test the whole reactor.
    if full_regression or changed & {"build", "shared"}:
        modules = list(ALL_UPSTREAM_MODULES)
        app_tests = True
    else:
        selected = {module for flag, module in DIRECT_MODULES if flag in changed}
        if changed & {"channel", "protocol", "routing"}:
            selected.add("prism-routing")
        # Request-log consumes pricing in production and protocol in its tests.
        if changed & {"pricing", "protocol"}:
            selected.add("prism-request-log")
        modules = [module for module in ALL_UPSTREAM_MODULES if module in selected]
        app_tests = bool(modules) or "app" in changed
        # All non-integration tests still run. Added downstream tests do not
        # broaden or narrow the existing pure channel/protocol app selection.
        if not changed & FULL_APP_FLAGS:
            app_tags = "|".join(flag for flag in ("channel", "protocol") if flag in changed)
    return {
        "modules": ",".join(modules),
        "app_tests": "true" if app_tests else "false",
        "app_tags": app_tags,
        "full_regression": "true" if full_regression else "false",
    }


def boolean_from_env(environment: Mapping[str, str], name: str) -> bool:
    value = environment.get(name)
    if value not in ("true", "false"):
        # Fail closed on missing/malformed filter outputs, without echoing input.
        raise ValueError(f"{name} must be 'true' or 'false'")
    return value == "true"


def changes_from_env(environment: Mapping[str, str]) -> dict[str, bool]:
    return {flag: boolean_from_env(environment, f"CHANGED_{flag.upper()}") for flag in CHANGE_FLAGS}


def main() -> int:
    outputs = resolve_modules(
        changes_from_env(os.environ),
        full_regression=boolean_from_env(os.environ, "FULL_REGRESSION"),
    )
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        raise ValueError("GITHUB_OUTPUT is required")
    with open(output_path, "a", encoding="utf-8") as handle:
        for key, value in outputs.items():
            handle.write(f"{key}={value}\n")
    print(f"Affected upstream test modules: {outputs['modules'] or 'none'}")
    print(f"App tests: {outputs['app_tests']} (tags: {outputs['app_tags'] or 'all'})")
    print(f"Full regression: {outputs['full_regression']}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
