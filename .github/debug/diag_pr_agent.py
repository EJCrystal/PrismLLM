"""临时诊断脚本：在 Actions 容器内定位 apply_repo_settings 静默退出的位置。

不属于正式工作流，仅用于排查 pragent/pr-agent 镜像在 GitHub Actions 环境下的
exit 0 静默退出问题。诊断完成后应随调试分支一起删除。
"""

import atexit
import faulthandler
import json
import os
import signal
import sys
import traceback

faulthandler.enable()

atexit.register(lambda: print("ATEXIT reached", flush=True))


def _on_signal(signum, frame):
    print(f"SIGNAL received: {signum}", flush=True)
    traceback.print_stack(frame)


for _sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
    try:
        signal.signal(_sig, _on_signal)
    except Exception:
        pass


def mark(label, extra=""):
    print(f"MARK {label} {extra}", flush=True)


mark("PY_VERSION", sys.version.replace("\n", " "))
mark("PR_AGENT_FILE", __import__("pr_agent").__file__ if True else "")
mark("EVENT_PATH", os.environ.get("GITHUB_EVENT_PATH", "<unset>"))
mark("CWD", os.getcwd())
mark(
    "GITHUB_ENV_KEYS",
    ",".join(sorted(k for k in os.environ if k.startswith("GITHUB"))),
)

with open(os.environ["GITHUB_EVENT_PATH"], "r", encoding="utf-8") as fh:
    event_payload = json.load(fh)
pr_url = event_payload["pull_request"]["html_url"]
mark("PR_URL", pr_url)

from pr_agent.config_loader import get_settings  # noqa: E402
from pr_agent.git_providers import get_git_provider_with_context  # noqa: E402
from pr_agent.git_providers.utils import (  # noqa: E402
    _restore_per_directory_settings,
    apply_host_settings,
    apply_repo_settings,
)

mark("IMPORT_OK")
mark("use_repo_settings_file", repr(get_settings().config.get("use_repo_settings_file")))
mark("git_provider", repr(get_settings().config.get("git_provider")))
mark("enable_per_directory_settings", repr(get_settings().config.get("enable_per_directory_settings")))
mark("github.user_token_set", bool(get_settings().get("GITHUB.USER_TOKEN", "")))

try:
    mark("M1 before _restore_per_directory_settings")
    _restore_per_directory_settings()
    mark("M1 ok")

    mark("M2 before apply_host_settings")
    apply_host_settings()
    mark("M2 ok")

    mark("M3 before get_git_provider_with_context")
    git_provider = get_git_provider_with_context(pr_url)
    mark("M3 ok", type(git_provider).__name__)

    mark("M4 before get_repo_settings")
    repo_settings = git_provider.get_repo_settings()
    mark("M4 ok", repr(repo_settings)[:300])

    mark("M5 before apply_repo_settings (full)")
    apply_repo_settings(pr_url)
    mark("M5 ok", f"enable_custom_labels={get_settings().config.enable_custom_labels}")
except BaseException as exc:  # noqa: BLE001 - 诊断需要捕获 SystemExit
    traceback.print_exc()
    print(f"APPLY_ERR {type(exc).__name__}: {exc!r}", flush=True)

mark("DONE")
