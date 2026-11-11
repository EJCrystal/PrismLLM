"""pr_agent_gate.py 的 code_tokens / code_like 单元测试。

背景：PR #220 的三条 finding 正文 token（cost_amount 等下划线标识符）
不含点号连接的 a.b 形式，旧版 code_tokens 全部过滤为空，stale 检测
永远返回 False，导致已修复/误报的意见永久 ACTIVE 卡住 AI Review Gate。

运行：python3 .github/scripts/test_pr_agent_gate.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from pr_agent_gate import code_like, code_tokens


def test_snake_case_identifier_is_code():
    assert code_tokens("趋势 SQL 对 `cost_amount` 使用 `sum`") == ["cost_amount"]


def test_camel_case_identifier_is_code():
    assert code_tokens("返回为 `costUsd` 与 `costCurrency`") == ["costUsd", "costCurrency"]


def test_compound_expression_is_code():
    assert code_tokens("使用 `costUsd = costs[id]` 取值") == ["costUsd = costs[id]"]


def test_bracket_expression_is_code():
    assert code_tokens("通过 `toMap()` 与 `costs[id]`") == ["toMap()", "costs[id]"]


def test_plain_words_are_not_code():
    assert code_tokens("普通词 `reason` 和 `state` 不是锚点") == []


def test_chinese_is_not_code():
    assert code_tokens("`未分配费用` 被覆盖") == []


def test_short_tokens_filtered():
    assert code_tokens("`sum` 和 `null` 太短") == []


def test_urls_and_paths_filtered():
    assert code_tokens("见 `http://x.y/z` 与 `/abs/path`") == []


def test_dedup_preserves_order():
    assert code_tokens("`cost_amount` 后又提 `cost_amount`") == ["cost_amount"]


def test_code_like_direct():
    assert code_like("cost_amount")
    assert code_like("costUsd")
    assert code_like("a.b")
    assert code_like("x = y[1]")
    assert not code_like("reason")
    assert not code_like("状态")


def run_all() -> int:
    tests = [fn for name, fn in sorted(globals().items()) if name.startswith("test_")]
    failed = 0
    for fn in tests:
        try:
            fn()
            print(f"PASS {fn.__name__}")
        except AssertionError as exc:
            failed += 1
            print(f"FAIL {fn.__name__}: {exc}")
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(run_all())
