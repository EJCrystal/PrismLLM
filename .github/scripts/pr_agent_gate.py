#!/usr/bin/env python3
"""AI Review Gate 的 PR-Agent 状态过滤器。

读取 PR-Agent 的持久化状态块，只统计当前 HEAD 上仍未解决、且没有被人工
豁免或判定为过期描述的意见。输出一行：

    <active> <waived> <stale> <unresolved>

- active: 当前 HEAD 的 ACTIVE 意见总数
- waived: 命中 .github/pr-agent-waivers.json 的数量
- stale: 引用的代码已不在标注行附近的数量
- unresolved: 最终需要门禁拦截的数量

退出码：0 正常；2 解析/请求失败；3 状态块来自未知机器人。
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from datetime import date
from urllib.parse import quote

ALLOWED_BOTS = {"github-actions[bot]", "pr-agent[bot]", "qodo-merge-pro[bot]"}
STATE_MARKER = "pr-agent-review-state:v1"
STATE_PATTERN = re.compile(r"<!--\s*pr-agent-review-state:v1\s*(\{.*?\})\s*-->", re.S)
WAIVER_PATH = os.environ.get("WAIVER_PATH", ".github/pr-agent-waivers.json")
STALE_LINE_MARGIN = int(os.environ.get("STALE_LINE_MARGIN", "10"))


class GateError(Exception):
    """门禁无法可靠判断时抛出，调用方按 fail closed 处理。"""


def gh(args: list[str]) -> str:
    result = subprocess.run(["gh", *args], text=True, capture_output=True)
    if result.returncode != 0:
        raise GateError(f"gh {' '.join(args)} 失败：{result.stderr.strip()}")
    return result.stdout


def gh_json(endpoint: str, *extra: str) -> dict:
    return json.loads(gh(["api", endpoint, *extra]))


def gh_raw(endpoint: str) -> str | None:
    """读取 raw 文件；404 返回 None，其余错误 fail closed。"""
    result = subprocess.run(
        ["gh", "api", endpoint, "-H", "Accept: application/vnd.github.raw"],
        text=True,
        capture_output=True,
    )
    if result.returncode == 0:
        return result.stdout
    stderr = result.stderr.lower()
    if "not found" in stderr or "http 404" in stderr or "404" in stderr:
        return None
    raise GateError(f"gh api {endpoint} 失败：{result.stderr.strip()}")


def fetch_comments(repo: str, pr_number: str) -> list[dict]:
    raw = gh(["api", f"repos/{repo}/issues/{pr_number}/comments", "--paginate", "--jq", ".[]"])
    comments = []
    for line in raw.splitlines():
        line = line.strip()
        if line:
            comments.append(json.loads(line))
    return comments


def parse_state(body: str) -> dict:
    match = STATE_PATTERN.search(body)
    if match is None:
        raise GateError("状态块存在但找不到 pr-agent-review-state:v1 JSON")
    try:
        state = json.loads(match.group(1))
    except json.JSONDecodeError as exc:
        raise GateError(f"无法解析 PR-Agent 状态块：{exc}") from exc
    if not isinstance(state, dict) or not isinstance(state.get("findings"), list):
        raise GateError("PR-Agent 状态块缺少 findings 数组")
    return state


def load_waivers(repo: str, pr_number: str) -> list[dict]:
    """从 base 分支读取豁免，避免 PR 给自己发豁免。"""
    pr_info = gh_json(f"repos/{repo}/pulls/{pr_number}")
    base_ref = pr_info.get("base", {}).get("ref")
    if not isinstance(base_ref, str) or not base_ref:
        raise GateError("无法确定 PR 的 base 分支")
    endpoint = f"repos/{repo}/contents/{quote(WAIVER_PATH, safe='/')}?ref={quote(base_ref, safe='')}"
    raw = gh_raw(endpoint)
    if raw is None:
        return []
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise GateError(f"豁免文件不是合法 JSON：{exc}") from exc
    if not isinstance(data, dict) or data.get("version") != 1 or not isinstance(data.get("waivers"), list):
        raise GateError("豁免文件结构必须是 {\"version\": 1, \"waivers\": [...]}")
    today = date.today()
    waivers = []
    for index, waiver in enumerate(data["waivers"]):
        if not isinstance(waiver, dict):
            raise GateError(f"waivers[{index}] 必须是对象")
        finding_id = waiver.get("finding_id")
        reason = waiver.get("reason")
        if not isinstance(finding_id, str) or not re.fullmatch(r"[0-9a-f]{8,64}", finding_id):
            raise GateError(f"waivers[{index}].finding_id 必须是 8-64 位小写十六进制")
        if not isinstance(reason, str) or not reason.strip():
            raise GateError(f"waivers[{index}].reason 不能为空")
        expires = waiver.get("expires")
        if expires is not None:
            if not isinstance(expires, str):
                raise GateError(f"waivers[{index}].expires 必须是 YYYY-MM-DD")
            try:
                expiry = date.fromisoformat(expires)
            except ValueError as exc:
                raise GateError(f"waivers[{index}].expires 不是合法日期：{expires}") from exc
            if expiry < today:
                print(f"WAIVER-EXPIRED {finding_id} {expires}", file=sys.stderr)
                continue
        waivers.append(waiver)
    return waivers


def code_tokens(body: str) -> list[str]:
    """提取 finding 正文里反引号包住的代码表达式。"""
    tokens = []
    for raw in re.findall(r"`([^`\n]+)`", body):
        token = raw.strip()
        if len(token) < 8:
            continue
        if "://" in token or token.startswith(("/", "~", "http")):
            continue
        if "(" not in token and not re.search(r"[A-Za-z_]\w*\.[A-Za-z_]\w*", token):
            continue
        tokens.append(token)
    return list(dict.fromkeys(tokens))


def token_present(token: str, text: str) -> bool:
    # 用词边界避免 delay(5) 命中 delay(50)、- 1 命中 - 1_000。
    return re.search(r"(?<![\w])" + re.escape(token) + r"(?![\w])", text) is not None


def is_stale(finding: dict, repo: str, head_sha: str, file_cache: dict[str, str | None]) -> tuple[bool, str]:
    path = finding.get("path")
    if not isinstance(path, str) or not path:
        return False, "没有可校验的文件路径"
    if path.startswith("/") or ".." in path.split("/"):
        return False, "文件路径不安全，保留给人工判断"

    if path not in file_cache:
        endpoint = f"repos/{repo}/contents/{quote(path, safe='/')}?ref={quote(head_sha, safe='')}"
        file_cache[path] = gh_raw(endpoint)
    content = file_cache[path]
    if content is None:
        return True, f"{path} 在 HEAD 已不存在"

    lines = content.splitlines()
    start = finding.get("line_start")
    end = finding.get("line_end")
    if not isinstance(start, int) or not isinstance(end, int) or start < 1 or end < start:
        return False, "行号缺失或非法"
    if start > len(lines):
        return True, f"line_start {start} 超出文件行数 {len(lines)}"

    low = max(1, start - STALE_LINE_MARGIN)
    high = min(len(lines), end + STALE_LINE_MARGIN)
    scoped = "\n".join(lines[low - 1:high])
    tokens = code_tokens(finding.get("body") or "")
    if not tokens:
        return False, "正文没有可校验的代码表达式"
    present = [token for token in tokens if token_present(token, scoped)]
    if present:
        return False, f"代码仍在标注行附近：{present[0][:80]}"
    return True, f"标注行 {low}-{high} 附近找不到引用的代码：{tokens[0][:80]}"


def main() -> int:
    repo = os.environ["REPO"]
    pr_number = os.environ["PR_NUMBER"]
    head_sha = os.environ["HEAD_SHA"]

    comments = fetch_comments(repo, pr_number)
    marked = [
        comment
        for comment in comments
        if comment.get("user", {}).get("type") == "Bot"
        and STATE_MARKER in (comment.get("body") or "")
    ]
    allowed = [comment for comment in marked if comment["user"].get("login") in ALLOWED_BOTS]
    if len(marked) != len(allowed):
        print(
            f"ERROR: {len(marked) - len(allowed)} 条状态块来自未知机器人账号",
            file=sys.stderr,
        )
        return 3
    if not allowed:
        print("0 0 0 0")
        return 0

    # 同一 finding 可能在多个状态块里出现；按时间顺序合并，后者覆盖前者。
    findings_by_id: dict[str, dict] = {}
    for comment in sorted(allowed, key=lambda item: item.get("updated_at") or item.get("created_at") or ""):
        state = parse_state(comment.get("body") or "")
        for finding in state["findings"]:
            if not isinstance(finding, dict):
                raise GateError("findings 中存在非对象元素")
            finding_id = finding.get("finding_id")
            if isinstance(finding_id, str) and finding_id:
                findings_by_id[finding_id] = finding

    active = [
        finding
        for finding in findings_by_id.values()
        if finding.get("state") == "ACTIVE"
        and ((finding.get("last_seen_head_sha") or head_sha) == head_sha)
    ]
    waivers = load_waivers(repo, pr_number)
    waived_ids = {waiver["finding_id"] for waiver in waivers}
    file_cache: dict[str, str | None] = {}
    waived = 0
    stale = 0
    unresolved = []
    for finding in active:
        finding_id = finding.get("finding_id", "?")
        if finding_id in waived_ids:
            waived += 1
            print(f"WAIVED {finding_id} {finding.get('path')}", file=sys.stderr)
            continue
        stale_flag, reason = is_stale(finding, repo, head_sha, file_cache)
        if stale_flag:
            stale += 1
            print(f"STALE {finding_id} {finding.get('path')}：{reason}", file=sys.stderr)
            continue
        unresolved.append(finding)
        print(f"UNRESOLVED {finding_id} {finding.get('path')}：{reason}", file=sys.stderr)

    print(f"{len(active)} {waived} {stale} {len(unresolved)}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except GateError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(2)
