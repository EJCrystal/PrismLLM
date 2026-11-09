"""临时探针：验证 PR-Agent stale/waiver 过滤，验证后删除，不合并。"""

import subprocess


def render(name: str) -> None:
    subprocess.run(["echo", name], check=True)
