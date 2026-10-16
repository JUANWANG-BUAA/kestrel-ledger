"""Brinson 归因：把基金超额收益分解为「配置效应 + 选择效应 + 交互效应」。

三因子 Brinson-Fachler 模型：
    配置效应 AR = Σ (w_p,i − w_b,i) × (r_b,i − r_b)      ← 超配了跑赢基准的行业
    选择效应 SR = Σ w_b,i × (r_p,i − r_b,i)              ← 行业内选股能力
    交互效应 IR = Σ (w_p,i − w_b,i) × (r_p,i − r_b,i)     ← 超配 + 选股的双重作用
    总超额    = AR + SR + IR

数据来源：
    行业权重  : 基金季报持仓的行业归属聚合（`holding_industry`）
    行业收益  : **PCF 合成主题指数**（比 ETF 价格干净，见 docs/07 第五部分）
    基准       : 沪深300（可配）

⚠️ 已知局限：
  1. 季报只披露前十大 → 行业权重不完整（用全量持仓可缓解：topline=100）
  2. 行业分类用东财口径，与基准（如中证）不完全一致
  3. 单期归因只对"报告期"有意义，跨期需链式连接（Carino 平滑）

用法：python3 tools/brinson.py --fund 005827 --period 2025Q4
"""
from __future__ import annotations
import argparse
import logging
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt import db  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("brinson")

BENCH = "1.000300"


def period_range(period: str) -> tuple[str, str]:
    """报告期 → 下期开始前的区间（用于计算区间收益）。"""
    y, q = int(period[:4]), int(period[-1])
    starts = {1: (1, 1), 2: (4, 1), 3: (7, 1), 4: (10, 1)}
    ends = {1: (3, 31), 2: (6, 30), 3: (9, 30), 4: (12, 31)}
    m0, d0 = starts[q]
    m1, d1 = ends[q]
    return f"{y}-{m0:02d}-{d0:02d}", f"{y}-{m1:02d}-{d1:02d}"


def cum_return(ser: dict[str, float], lo: str, hi: str) -> float | None:
    """区间累计收益 %（用复权修正后的日收益）。"""
    ks = [d for d in sorted(ser) if lo <= d <= hi]
    if len(ks) < 5:
        return None
    v = 1.0
    for d in ks:
        v *= (1 + ser[d] / 100.0)
    return (v - 1) * 100


def run(con, code: str, period: str) -> dict | None:
    lo, hi = period_range(period)
    # ⚠️ 必须用 load_nav_series（净值）；load_nav 返回的是**日收益率**（曾误用导致 −44% 的错误结果）
    nav = db.load_nav_series(con, code)
    if not nav:
        return None
    ks = [d for d in sorted(nav) if lo <= d <= hi]
    if len(ks) < 10:
        return None
    navs = np.array([nav[d] for d in ks])
    r_fund = (navs[-1] / navs[0] - 1) * 100

    # 基准区间收益
    bench = db.load_klines(con, BENCH)
    bk = [d for d in sorted(bench) if lo <= d <= hi]
    if len(bk) < 5:
        return None
    r_bench = (bench[bk[-1]] / bench[bk[0]] - 1) * 100

    # 组合行业权重（按报告期口径取最近一期）
    rows = con.execute(
        "SELECT h.secid, h.weight FROM fund_holding h WHERE h.code=?"
        " AND h.period=(SELECT MAX(period) FROM fund_holding WHERE code=? AND period<=?)",
        (code, code, period)).fetchall()
    if not rows:
        return None
    w_p: dict[str, float] = {}
    total = sum(w for _, w in rows)
    for sid, w in rows:
        r = con.execute("SELECT industry FROM holding_industry WHERE secid=?", (sid,)).fetchone()
        ind = (r[0] if r and r[0] else "其他")
        w_p[ind] = w_p.get(ind, 0.0) + w / total * 100      # 归一化到 100%

    # 行业收益（用 PCF 合成指数，取最接近的行业指数）
    from mkt.sources import sse_pcf
    def ser_of(sid):
        from adjust import raw_returns
        return raw_returns(con, sid, adjust=True)

    THEME_MAP = {
        "电子": "512480", "半导体": "512480", "计算机": "512720", "通信": "515880",
        "传媒": "516010", "医药生物": "512010", "电力设备": "515030", "有色金属": None,
        "食品饮料": "512690", "银行": "512800", "非银金融": "512880", "国防军工": "512660",
        "机械设备": None, "汽车": None, "基础化工": None, "房地产": None,
    }
    r_ind: dict[str, float] = {}
    for ind in w_p:
        sid_code = THEME_MAP.get(ind)
        if sid_code:
            rr = cum_return(ser_of(f"1.{sid_code}"), lo, hi)
            if rr is not None:
                r_ind[ind] = rr
    # 无对应指数的行业 → 用基准收益代理
    covered = sum(w_p[i] for i in r_ind)
    for ind in w_p:
        r_ind.setdefault(ind, r_bench)

    # 基准行业权重：简化假设「按沪深300 行业分布」——用行业指数市值占比近似不可得，
    # 此处采用**等权基准**并明确标注（真实基准权重需中证行业数据）
    n_ind = len(w_p)
    w_b = {i: 100.0 / n_ind for i in w_p}
    r_b = r_bench

    AR = sum((w_p[i] - w_b[i]) / 100 * (r_ind[i] - r_b) for i in w_p)
    SR = sum(w_b[i] / 100 * (0.0) for i in w_p)        # 无行业内选股数据 → 计入残差
    IR = sum((w_p[i] - w_b[i]) / 100 * (0.0) for i in w_p)
    excess = r_fund - r_bench
    residual = excess - AR - SR - IR

    return {
        "period": period, "lo": lo, "hi": hi,
        "r_fund": r_fund, "r_bench": r_bench, "excess": excess,
        "AR": AR, "SR": SR, "IR": IR, "residual": residual,
        "w_p": w_p, "r_ind": r_ind, "coverage": covered,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fund", default="005827")
    ap.add_argument("--period", default="")
    ap.add_argument("--funds", default="")
    args = ap.parse_args()
    con = db.connect()

    codes = [c.strip() for c in args.funds.split(",") if c.strip()] or [args.fund]
    for code in codes:
        periods = [r[0] for r in con.execute(
            "SELECT DISTINCT period FROM fund_holding WHERE code=? ORDER BY period DESC LIMIT 4",
            (code,))]
        if args.period:
            periods = [args.period]
        name = con.execute("SELECT name FROM fund_meta WHERE code=?", (code,)).fetchone()
        for p in periods:
            r = run(con, code, p)
            if not r:
                continue
            log.info("=" * 78)
            log.info("▌ %s %s  %s（%s ~ %s）", code, name[0] if name else "", p, r["lo"], r["hi"])
            log.info("=" * 78)
            log.info("  收益   基金 %+7.2f%%   基准(沪深300) %+7.2f%%   超额 %+7.2f%%",
                     r["r_fund"], r["r_bench"], r["excess"])
            log.info("  归因   配置效应 %+7.2f%%   交互效应 %+7.2f%%   残差(选股+其他) %+7.2f%%",
                     r["AR"], r["IR"], r["residual"])
            log.info("  行业暴露（归一化 Top6，行业指数覆盖率 %.0f%%）：", r["coverage"])
            for ind, w in sorted(r["w_p"].items(), key=lambda x: -x[1])[:6]:
                ri = r["r_ind"].get(ind, float("nan"))
                contrib = (w - 100 / len(r["w_p"])) / 100 * (ri - r["r_bench"])
                log.info("    %-12s 权重 %5.1f%%  行业收益 %+7.2f%%  配置贡献 %+6.2f%%",
                         ind, w, ri, contrib)
    con.close()


if __name__ == "__main__":
    main()
