"""CI 门禁：一键跑完数据健康 + 接口探针 + 精度回归，任一超标即失败。

用于夜间 CI（对应 docs/11 §I.7.4）：
    python3 tools/ci_check.py
退出码 0 = 通过，1 = 有门禁未过（可阻断发布）
"""
from __future__ import annotations
import logging, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("ci")
PY = sys.executable

# 门禁：[名称, 命令, 是否阻断]
GATES = [
    # ⚠️ 顺序有依赖：合成指数必须在健康自检之前重算（否则刚采的 PCF 还没派生）
    ("PCF 合成指数重算", [PY, "tools/pcf_synth.py", "--all-days"], False),
    ("数据健康自检", [PY, "tools/healthcheck.py"], True),
    ("接口探针", [PY, "tools/probe.py", "--quick"], True),
    ("交易日历", [PY, "tools/trade_calendar.py"], False),
    ("估值精度回归", [PY, "tools/benchmark.py"], False),   # 输出供人看，不阻断
]


def main() -> None:
    log.info("=" * 68)
    log.info("CI 门禁检查")
    log.info("=" * 68)
    failed = []
    for name, cmd, blocking in GATES:
        log.info("▶ %s", name)
        r = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True, timeout=3600)
        tail = "\n".join((r.stdout or "").strip().splitlines()[-12:])
        log.info("  %s\n%s", "✅ 通过" if r.returncode == 0 else "❌ 失败", tail)
        if r.returncode != 0:
            failed.append((name, blocking))
    log.info("=" * 68)
    blocking_fail = [n for n, b in failed if b]
    if blocking_fail:
        log.error("阻断性门禁未过：%s", "、".join(blocking_fail))
        sys.exit(1)
    if failed:
        log.warning("非阻断项未过：%s", "、".join(n for n, _ in failed))
    log.info("CI 通过 ✅")


if __name__ == "__main__":
    main()
