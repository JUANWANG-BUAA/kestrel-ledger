"""每日自动化主流程（一条命令跑完全部日常任务）。

用法：
    python3 tools/daily.py              # 完整日常
    python3 tools/daily.py --pre        # 盘前（09:00）：只采 PCF
    python3 tools/daily.py --post       # 盘后（16:00）：PCF + 合成指数 + 归档
    python3 tools/daily.py --weekly     # 周任务：分红/全量回填

建议 crontab（交易日）：
    0 9  * * 1-5  cd <repo> && python3 tools/daily.py --pre  >> logs/daily.log 2>&1
    0 16 * * 1-5  cd <repo> && python3 tools/daily.py --post >> logs/daily.log 2>&1
    0 3  * * 6    cd <repo> && python3 tools/daily.py --weekly >> logs/daily.log 2>&1

设计说明：
  · 每个步骤**失败不阻断后续**（写 collect_log），最后汇总
  · 所有步骤幂等，可安全重跑
  · 归档顺序：先采 → 再算派生 → 最后清理（避免算了才发现原始被删）
"""
from __future__ import annotations

import argparse
import logging
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("daily")


def run(step: str, cmd: list[str], timeout: int = 3600) -> tuple[bool, float]:
    t0 = time.time()
    log.info("▶ %s", step)
    try:
        r = subprocess.run(cmd, cwd=str(ROOT.parent), capture_output=True,
                           text=True, timeout=timeout)
        ok = r.returncode == 0
        out = (r.stdout or "")[-400:] + (r.stderr or "")[-400:]
        if not ok:
            log.warning("✗ %s 失败 (code=%d)\n%s", step, r.returncode, out)
        return ok, time.time() - t0
    except subprocess.TimeoutExpired:
        log.warning("✗ %s 超时 (%ds)", step, timeout)
        return False, time.time() - t0
    except Exception as e:  # noqa: BLE001
        log.warning("✗ %s 异常: %s", step, e)
        return False, time.time() - t0


PY = sys.executable


def step_pre() -> list[tuple[str, list[str]]]:
    """盘前：PCF 开盘前公布，含当日成分。"""
    return [
        ("PCF 采集（当日成分）", [PY, "tools/collect_pcf.py"]),
    ]


def step_post() -> list[tuple[str, list[str]]]:
    """盘后：采数据 → 算派生 → 归档清理。"""
    return [
        ("PCF 复核", [PY, "tools/collect_pcf.py"]),
        ("PCF 合成主题指数", [PY, "tools/pcf_synth.py", "--all-days"]),
        ("成分股行情增量", [PY, "tools/fetch_quotes.py", "--pcf-components", "-j", "6"]),
        ("基金净值/持仓增量", [PY, "tools/collect.py"]),
        ("归档：清理 90 天前原始 PCF", [PY, "tools/pcf_synth.py", "--all-days", "--prune"]),
        ("数据自检", [PY, "tools/healthcheck.py"]),
    ]


def step_weekly() -> list[tuple[str, list[str]]]:
    """周任务：数据清洗、分红（复权修正）、全量回填、基准回测。"""
    return [
        ("场内基金分红（复权）", [PY, "tools/clean.py", "--collect-div"]),
        ("数据清洗（错位/折算/异常）", [PY, "tools/clean.py", "--fix-scale", "--verify"]),
        ("分红送配增量（股票复权）", [PY, "tools/adjust.py"]),
        ("全量持仓（topline=100）", [PY, "tools/collect.py"]),
        ("基准回测（精度门禁）", [PY, "tools/benchmark.py"]),
        ("文档一致性检查", [PY, "tools/doc_check.py"]),
    ]


MODES = {
    "--pre": (step_pre, "盘前"),
    "--post": (step_post, "盘后"),
    "--weekly": (step_weekly, "周任务"),
}


def main() -> None:
    ap = argparse.ArgumentParser()
    for m in MODES:
        ap.add_argument(m, action="store_true")
    args = ap.parse_args()
    chosen = [m for m in MODES if getattr(args, m.lstrip("-"))]
    if not chosen:
        chosen = list(MODES)          # 无参数 = 全跑

    log.info("=" * 68)
    log.info("每日任务开始：%s", ", ".join(MODES[m][1] for m in chosen))
    log.info("=" * 68)

    results = []
    for mode in chosen:
        fn, label = MODES[mode]
        log.info("──── %s ────", label)
        for step, cmd in fn():
            ok, el = run(step, cmd)
            results.append((label, step, ok, el))
            log.info("   %s %s  %.1fs", "✅" if ok else "⚠️", step, el)

    log.info("=" * 68)
    bad = [r for r in results if not r[2]]
    log.info("完成 %d/%d 步成功，总耗时 %.0fs",
             len(results) - len(bad), len(results), sum(r[3] for r in results))
    for _, step, _, _ in bad:
        log.warning("  失败：%s", step)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
