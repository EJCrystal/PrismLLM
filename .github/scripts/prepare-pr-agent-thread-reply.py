#!/usr/bin/env python3
"""Turn a human reply on a PR-Agent review thread into an /ask request."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


TRUSTED_ASSOCIATIONS = {"OWNER", "MEMBER", "COLLABORATOR"}
PR_AGENT_MARKER = os.environ.get("PR_AGENT_MARKER", "<!-- pr-agent-")
MAX_REPLY_CHARS = 16000


def write_output(name: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    with open(output_path, "a", encoding="utf-8") as output:
        output.write(f"{name}={value}\n")


def skip(reason: str) -> None:
    print(f"Skipping PR-Agent thread reply: {reason}")
    write_output("should_run", "false")
    write_output("resolve_threads", "false")


def github_json(url: str, token: str) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "llm-gateway-pr-agent-thread-reply",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = json.load(response)
    if not isinstance(payload, dict):
        raise ValueError(f"Unexpected GitHub API response from {url}")
    return payload


def main() -> int:
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    if event_name != "pull_request_review_comment":
        write_output("should_run", "true")
        write_output("resolve_threads", "false")
        return 0

    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if not event_path:
        print("GITHUB_EVENT_PATH is not set", file=sys.stderr)
        return 1

    event_file = Path(event_path)
    event = json.loads(event_file.read_text(encoding="utf-8"))
    if not isinstance(event, dict):
        raise ValueError("GitHub event payload must be an object")

    if event.get("action") not in {"created", "edited"}:
        skip("unsupported review-comment action")
        return 0

    sender = event.get("sender") or {}
    if sender.get("type") == "Bot":
        skip("sender is a bot")
        return 0

    comment = event.get("comment") or {}
    body = str(comment.get("body") or "").strip()
    if not body:
        skip("comment body is empty")
        return 0

    association = str(comment.get("author_association") or "")
    if association not in TRUSTED_ASSOCIATIONS:
        skip("commenter is not a trusted repository collaborator")
        return 0

    if body.startswith("/"):
        if body == "/ask" or body.startswith("/ask "):
            write_output("should_run", "true")
            write_output("resolve_threads", "false")
            return 0
        skip("review comment starts with a different command")
        return 0

    parent_id = comment.get("in_reply_to_id")
    if not parent_id:
        skip("comment is not an inline thread reply")
        return 0

    repository = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    if not repository or not token:
        print("GITHUB_REPOSITORY or GITHUB_TOKEN is not set", file=sys.stderr)
        return 1

    try:
        parent = github_json(
            f"{api_url}/repos/{repository}/pulls/comments/{parent_id}", token
        )
    except urllib.error.HTTPError as error:
        print(
            f"::warning::Could not read parent review comment {parent_id}: HTTP {error.code}"
        )
        write_output("should_run", "false")
        write_output("resolve_threads", "false")
        return 0
    except (urllib.error.URLError, TimeoutError) as error:
        print(
            f"::warning::Could not read parent review comment {parent_id}: {error}"
        )
        write_output("should_run", "false")
        write_output("resolve_threads", "false")
        return 0

    parent_user = parent.get("user") or {}
    parent_body = str(parent.get("body") or "")
    if parent_user.get("type") != "Bot" or PR_AGENT_MARKER not in parent_body:
        skip("parent comment was not created by PR-Agent")
        return 0

    reply = body[:MAX_REPLY_CHARS]
    if len(body) > MAX_REPLY_CHARS:
        reply += "\n\n[回复过长，工作流已截断；请优先依据上述内容判断。]"

    comment["body"] = (
        "/ask 这是开发者对 PR-Agent 行内审查意见的回复。请结合当前 PR 代码、"
        "选中行、完整讨论和这条回复继续分析，并直接回复在该线程。不要仅凭开发者"
        "声称已修复就认定完成，必须核对当前代码、相关测试和上下文。若问题已经完整"
        "解决，最后单独输出 [THREAD_RESOLVED]；若仍有遗漏、反例、未覆盖的边界或"
        "未验证风险，明确指出剩余问题，不要输出该标记。\n\n"
        f"开发者回复：\n{reply}"
    )
    event_file.write_text(
        json.dumps(event, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    print(f"Prepared automatic PR-Agent reply for review comment {comment.get('id')}")
    write_output("should_run", "true")
    write_output("resolve_threads", "true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
