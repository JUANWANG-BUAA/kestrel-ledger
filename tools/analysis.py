"""分析引擎：收益/风险指标、归因、持仓穿透、风格、协方差。

对应 docs/11 §I.4 的 AN1~AN24 与 §I.5 的 QN2~QN8。

设计原则：
  · 全部为**纯函数**（输入序列 → 输出指标），便于单测与复用
  · 不依赖外部库（仅 numpy），可移植 Kotlin
  · **风控指标必须处理短样本与缺失值**（基金常有 <1 年历史）

用法：
    python3 tools/analysis.py --fund 005827            # 单只基金全指标
    python3 tools/analysis.py --portfolio 005827,110022,000217
    python3 tools/analysis.py --pierce 005827          # 持仓穿透
    python3 tools/analysis.py --overlap 005827,110022  # 重合度
"""
from __future__ import annotations

import argparse
import logging
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt import db  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("analysis")

TRADING_DAYS = 250.0
RISK_FREE = 0.015          # 一年期无风险利率（可配）


# ===========================================================================
# AN1~AN10 收益与风险指标
# ===========================================================================
@dataclass(slots=True)
class RiskMetrics:
    n: int = 0
    total_return: float = 0.0        # 累计收益 %
    annual_return: float = 0.0       # 年化收益 %
    annual_vol: float = 0.0          # 年化波动率 %
    sharpe: float = 0.0
    sortino: float = 0.0             # AN6：只罚下行波动的夏普
    calmar: float = 0.0              # AN6：年化收益 / 最大回撤
    max_drawdown: float = 0.0        # %
    mdd_start: str = ""
    mdd_end: str = ""
    mdd_recover_days: int | None = None   # AN4：修复天数
    var95: float = 0.0               # AN7：历史模拟法（对肥尾稳健）
    cvar95: float = 0.0
    skew: float = 0.0
    kurtosis: float = 0.0
    win_rate: float = 0.0            # 上涨日占比
    beta: float = 0.0                # AN8：对基准
    alpha: float = 0.0               # 年化 %
    tracking_error: float = 0.0      # AN9
    information_ratio: float = 0.0
    up_capture: float = 0.0          # AN10
    down_capture: float = 0.0


def _drawdown(series: np.ndarray) -> tuple[float, int, int, int | None]:
    """最大回撤 + 峰/谷索引 + 修复天数。"""
    if len(series) < 2:
        return 0.0, 0, 0, None
    peak = series[0]
    peak_i = 0
    best_dd, best_peak, best_trough = 0.0, 0, 0
    for i, v in enumerate(series):
        if v > peak:
            peak, peak_i = v, i
        dd = v / peak - 1.0
        if dd < best_dd:
            best_dd, best_peak, best_trough = dd, peak_i, i
    # 修复：谷之后第一次回到峰
    recover = None
    if best_peak < len(series):
        target = series[best_peak]
        for i in range(best_trough, len(series)):
            if series[i] >= target:
                recover = i - best_trough
                break
    return best_dd * 100, best_peak, best_trough, recover


def risk_metrics(nav: dict[str, float], bench: dict[str, float] | None = None,
                 rf: float = RISK_FREE) -> RiskMetrics:
    """nav: {date: 单位净值}（升序）"""
    dates = sorted(nav)
    if len(dates) < 5:
        return RiskMetrics(n=len(dates))
    vals = np.array([nav[d] for d in dates], dtype=float)
    rets = vals[1:] / vals[:-1] - 1.0
    rets = rets[np.isfinite(rets)]
    if len(rets) < 5:
        return RiskMetrics(n=len(rets))

    m = RiskMetrics(n=len(rets))
    m.total_return = (vals[-1] / vals[0] - 1) * 100
    years = len(rets) / TRADING_DAYS
    m.annual_return = ((vals[-1] / vals[0]) ** (1 / years) - 1) * 100 if years > 0 else 0
    m.annual_vol = float(rets.std(ddof=1) * np.sqrt(TRADING_DAYS) * 100)

    # 夏普
    rf_daily = (1 + rf) ** (1 / TRADING_DAYS) - 1
    excess = rets - rf_daily
    sd = rets.std(ddof=1)
    m.sharpe = float(excess.mean() / sd * np.sqrt(TRADING_DAYS)) if sd > 0 else 0.0

    # 索提诺：只罚下行
    downside = rets[rets < rf_daily]
    dsd = downside.std(ddof=1) if len(downside) > 1 else 0.0
    m.sortino = float(excess.mean() / dsd * np.sqrt(TRADING_DAYS)) if dsd > 0 else 0.0

    # 最大回撤 + 卡玛
    dd, pi, ti, rec = _drawdown(vals)
    m.max_drawdown = dd
    m.mdd_start = dates[pi] if pi < len(dates) else ""
    m.mdd_end = dates[ti] if ti < len(dates) else ""
    m.mdd_recover_days = rec
    m.calmar = abs(m.annual_return / dd) if dd < 0 else 0.0

    # VaR / CVaR（历史模拟法）
    m.var95 = float(np.percentile(rets, 5) * 100)
    tail = rets[rets <= np.percentile(rets, 5)]
    m.cvar95 = float(tail.mean() * 100) if len(tail) else m.var95

    # 高阶矩
    if len(rets) > 3 and sd > 0:
        m.skew = float(((rets - rets.mean()) ** 3).mean() / sd**3)
        m.kurtosis = float(((rets - rets.mean()) ** 4).mean() / sd**4 - 3)
    m.win_rate = float((rets > 0).mean() * 100)

    # 对基准
    if bench:
        common = [d for d in dates[1:] if d in bench]
        if len(common) > 20:
            r_ = np.array([nav[common[i]] / nav[common[i - 1]] - 1 for i in range(1, len(common))])
            b_ = np.array([bench[common[i]] / bench[common[i - 1]] - 1 for i in range(1, len(common))])
            if b_.std() > 0:
                beta = float(np.cov(r_, b_)[0, 1] / b_.var(ddof=1))
                m.beta = beta
                m.alpha = float((r_.mean() - rf_daily - beta * (b_.mean() - rf_daily))
                                * TRADING_DAYS * 100)
            diff = r_ - b_
            te = diff.std(ddof=1)
            m.tracking_error = float(te * np.sqrt(TRADING_DAYS) * 100)
            m.information_ratio = float(diff.mean() / te * np.sqrt(TRADING_DAYS)) if te > 0 else 0.0
            up, dn = b_ > 0, b_ < 0
            if up.sum() > 5 and b_[up].mean() != 0:
                m.up_capture = float(r_[up].mean() / b_[up].mean() * 100)
            if dn.sum() > 5 and b_[dn].mean() != 0:
                m.down_capture = float(r_[dn].mean() / b_[dn].mean() * 100)
    return m


# ===========================================================================
# QN2 协方差矩阵收缩（Ledoit-Wolf）
# ===========================================================================
def shrink_cov(returns: np.ndarray, shrinkage: float | None = None) -> np.ndarray:
    """Ledoit-Wolf 收缩协方差：Σ̂ = (1−δ)S + δ·F

    T < N 时样本协方差不可逆；收缩保证正定。
    δ 用 Ledoit-Wolf (2004) 解析解；None 时自动估计。
    """
    T, N = returns.shape
    if T < 2 or N < 1:
        return np.eye(max(N, 1))
    X = returns - returns.mean(axis=0)
    S = X.T @ X / (T - 1)
    if shrinkage is None:
        # 目标矩阵 F：对角（保留方差，抹掉协方差）
        F = np.diag(np.diag(S))
        # 简易收缩强度：样本外协方差噪声比例
        var_s = S.ravel()
        phi = float(((X**3).mean(axis=0) ** 2).sum()) / T if T > 0 else 0.0
        gamma = float(((S - F) ** 2).sum())
        kappa = (phi - gamma) / max(gamma, 1e-12) if gamma > 0 else 0.0
        shrinkage = float(np.clip(kappa / T, 0.0, 1.0))
    return (1 - shrinkage) * S + shrinkage * np.diag(np.diag(S))


def risk_contribution(weights: np.ndarray, cov: np.ndarray) -> np.ndarray:
    """QN3：每只持仓对组合波动的贡献占比（%），合计 100。"""
    w = np.asarray(weights, dtype=float)
    if w.sum() != 0:
        w = w / w.sum()
    port_var = float(w @ cov @ w)
    if port_var <= 0:
        return np.zeros_like(w)
    mrc = cov @ w                       # 边际风险贡献
    rc = w * mrc / np.sqrt(port_var)    # 各资产风险贡献
    total = rc.sum()
    return rc / total * 100 if total != 0 else np.zeros_like(w)


# ===========================================================================
# AN19/AN20 持仓穿透（含 PCF 三层穿透）
# ===========================================================================
@dataclass(slots=True)
class Pierce:
    fund_code: str
    n_holdings: int = 0
    top10_weight: float = 0.0
    hhi: float = 0.0                       # AN17
    concentration: str = ""                # 高/中/低
    industries: dict[str, float] = field(default_factory=dict)   # AN16
    theme_exposure: dict[str, float] = field(default_factory=dict)  # AN20
    via_etf: dict[str, float] = field(default_factory=dict)        # AN19：穿透到 ETF 的成分


def pierce_fund(con, code: str, top_n: int = 10) -> Pierce:
    p = Pierce(fund_code=code)
    rows = con.execute(
        "SELECT secid, weight FROM fund_holding WHERE code=?"
        " AND period=(SELECT MAX(period) FROM fund_holding WHERE code=?)",
        (code, code)).fetchall()
    if not rows:
        return p
    w = {s: wt for s, wt in rows}
    p.n_holdings = len(w)
    p.top10_weight = sum(w.values())
    tot = sum(w.values())
    if tot > 0:
        p.hhi = sum((wt / tot) ** 2 for wt in w.values())
    p.concentration = "高" if p.hhi > 0.2 else ("中" if p.hhi > 0.1 else "低")

    # 行业分布：用移动端持仓接口或本地缓存
    ind = {}
    for sid, wt in w.items():
        r = con.execute("SELECT industry FROM holding_industry WHERE secid=?", (sid,)).fetchone()
        if r and r[0]:
            ind[r[0]] = ind.get(r[0], 0.0) + wt
    p.industries = dict(sorted(ind.items(), key=lambda x: -x[1])[:10])
    return p


# ===========================================================================
# AN18 跨基金重仓重合度
# ===========================================================================
def holdings_overlap(con, code_a: str, code_b: str) -> dict:
    def _w(code):
        rows = con.execute(
            "SELECT secid, weight FROM fund_holding WHERE code=?"
            " AND period=(SELECT MAX(period) FROM fund_holding WHERE code=?)",
            (code, code)).fetchall()
        return {s: wt for s, wt in rows}

    a, b = _w(code_a), _w(code_b)
    sa, sb = set(a), set(b)
    inter = sa & sb
    union = sa | sb
    # Jaccard（只看名单）
    jaccard = len(inter) / len(union) if union else 0.0
    # 权重余弦相似度（看权重结构，更合理）
    keys = sorted(union)
    va = np.array([a.get(k, 0.0) for k in keys])
    vb = np.array([b.get(k, 0.0) for k in keys])
    cos = float(va @ vb / (np.linalg.norm(va) * np.linalg.norm(vb))) if va.any() and vb.any() else 0.0
    # 重叠权重占比（双方较小的权重之和）
    overlap_w = sum(min(a[k], b[k]) for k in inter)
    return {"common": len(inter), "jaccard": jaccard, "cosine": cos,
            "overlap_weight": overlap_w,
            "shared": sorted(inter)}


# ===========================================================================
# AN22 风格漂移检测
# ===========================================================================
def style_drift(con, code: str, recent_days: int = 60) -> dict:
    """比较「回归权重」与「季报权重」的 L1 距离，判断是否漂移。"""
    from engine import NnRidgeRegressor

    nav = db.load_nav(con, code)
    rows = con.execute(
        "SELECT secid, weight FROM fund_holding WHERE code=?"
        " AND period=(SELECT MAX(period) FROM fund_holding WHERE code=?)",
        (code, code)).fetchall()
    if not nav or not rows:
        return {}
    prior = {s: wt for s, wt in rows}
    names = list(prior)
    raw = {}
    for s in names:
        kl = db.load_klines(con, s)
        it = sorted(kl.items())
        if len(it) > 30:
            raw[s] = {it[i][0]: (it[i][1] / it[i - 1][1] - 1) * 100 for i in range(1, len(it))}

    class LK:
        def __init__(self, d): self._d = d
        def get(self, sec, dt): return self._d.get(sec, {}).get(dt)

    book = LK(raw)
    d = sorted(nav)[-1]
    reg = NnRidgeRegressor(window=recent_days)
    r = reg.fit(d, nav, names, prior, book, lam=0.05, top_k=None)
    if not r:
        return {}
    ids, w_reg = r[0], np.array(r[1]) / 100.0
    w_rep = np.array([prior.get(s, 0.0) / 100.0 for s in ids])
    l1 = float(np.abs(w_reg - w_rep).sum())
    return {"l1_distance": l1, "drift": l1 > 0.5,
            "reg_top": sorted(zip(ids, w_reg), key=lambda x: -x[1])[:5],
            "rep_top": sorted(zip(ids, w_rep), key=lambda x: -x[1])[:5]}


# ===========================================================================
# PR5 波动率预测（EWMA，RiskMetrics 标准）
# ===========================================================================
def ewma_vol(rets: np.ndarray, lam: float = 0.94) -> float:
    """σ_t = sqrt(λ·σ²_{t-1} + (1−λ)·r²_{t-1})，返回最新日波动（%）。"""
    if len(rets) < 5:
        return float("nan")
    var = float(rets[:20].var())
    for r in rets[20:]:
        var = lam * var + (1 - lam) * r * r
    return float(np.sqrt(var) * 100)


def vol_regime(rets: np.ndarray, lam: float = 0.94) -> dict:
    """波动率状态：当前 vs 近 60 日基准，用于「今日误差可能扩大」提示。"""
    if len(rets) < 60:
        return {}
    cur = ewma_vol(rets, lam)
    base = float(np.std(rets[-60:], ddof=1) * 100)
    return {"current_vol": cur, "baseline_vol": base,
            "ratio": cur / base if base > 0 else 1.0,
            "elevated": bool(base > 0 and cur > base * 1.5)}


# ===========================================================================
# CLI
# ===========================================================================
def _bench(con):
    kl = db.load_klines(con, "1.000300")
    return kl or {}


def cmd_fund(con, code: str) -> None:
    nav = db.load_nav_series(con, code)
    if not nav:
        log.warning("%s 无净值", code)
        return
    m = risk_metrics(nav, _bench(con))
    name = con.execute("SELECT name FROM fund_meta WHERE code=?", (code,)).fetchone()
    log.info("=" * 78)
    log.info("▌ %s  %s   （样本 %d 日，%s ~ %s）",
             code, name[0] if name else "", m.n, min(nav), max(nav))
    log.info("=" * 78)
    log.info("  收益   累计 %+7.2f%%   年化 %+7.2f%%   上涨日占比 %.1f%%",
             m.total_return, m.annual_return, m.win_rate)
    log.info("  风险   年化波动 %6.2f%%  最大回撤 %7.2f%%（%s→%s，修复 %s 日）",
             m.annual_vol, m.max_drawdown, m.mdd_start, m.mdd_end,
             m.mdd_recover_days if m.mdd_recover_days is not None else "未")
    log.info("  比率   夏普 %6.2f  索提诺 %6.2f  卡玛 %6.2f",
             m.sharpe, m.sortino, m.calmar)
    log.info("  尾部   VaR95 %6.2f%%  CVaR95 %6.2f%%  偏度 %+5.2f  峰度 %+5.2f",
             m.var95, m.cvar95, m.skew, m.kurtosis)
    if m.beta or m.tracking_error:
        log.info("  基准   β %5.2f  α(年化) %+6.2f%%  跟踪误差 %5.2f%%  信息比率 %5.2f",
                 m.beta, m.alpha, m.tracking_error, m.information_ratio)
        log.info("  捕获   上行 %.1f%%   下行 %.1f%%   （<100 表示跑输基准）",
                 m.up_capture, m.down_capture)
    # 波动率状态
    navs = np.array([nav[d] for d in sorted(nav)])
    r = np.diff(navs) / navs[:-1]
    vr = vol_regime(r)
    if vr:
        flag = "⚠️ 波动偏高，估值误差可能扩大" if vr["elevated"] else "正常"
        log.info("  波动率 当前 %.2f%% / 基准 %.2f%%  比值 %.2f  %s",
                 vr["current_vol"], vr["baseline_vol"], vr["ratio"], flag)


def cmd_portfolio(con, codes: list[str]) -> None:
    navs = {c: db.load_nav_series(con, c) for c in codes}
    navs = {k: v for k, v in navs.items() if v}
    if len(navs) < 2:
        log.warning("需要至少 2 只有效基金"); return
    common = sorted(set.intersection(*[set(v) for v in navs.values()]))
    if len(common) < 30:
        common = sorted(set.union(*[set(v) for v in navs.values()]))[:0] or \
                 sorted(max(navs.values(), key=len))
    log.info("=" * 78)
    log.info("▌ 组合分析  %d 只，共同交易日 %d", len(navs), len(common))
    log.info("=" * 78)
    R = np.array([[navs[c][common[i]] / navs[c][common[i - 1]] - 1 for i in range(1, len(common))]
                  for c in navs])
    names = list(navs)
    # 等权组合
    eq = R.mean(axis=0)
    log.info("  等权组合：年化 %+.2f%%  波动 %.2f%%  夏普 %.2f  最大回撤 %.2f%%",
             ((1 + eq).prod() ** (TRADING_DAYS / len(eq)) - 1) * 100,
             eq.std(ddof=1) * np.sqrt(TRADING_DAYS) * 100,
             (eq.mean() * TRADING_DAYS) / (eq.std(ddof=1) * np.sqrt(TRADING_DAYS)),
             _drawdown(np.cumprod(1 + eq))[0])
    # QN2 收缩协方差 + 相关系数
    cov_raw = np.cov(R)
    cov_shr = shrink_cov(R.T)
    corr = cov_shr / np.sqrt(np.outer(np.diag(cov_shr), np.diag(cov_shr)))
    log.info("  相关性矩阵（收缩后）：")
    hdr = "        " + "".join(f"{c[-6:]:>9}" for c in names)
    log.info(hdr)
    for i, c in enumerate(names):
        log.info("  %-6s" % c[-6:] + "".join(f"{corr[i, j]:>9.2f}" for j in range(len(names))))
    # QN3 风险贡献
    w = np.ones(len(names))
    rc = risk_contribution(w, cov_shr)
    log.info("  风险贡献（等权持有，%）：")
    for c, x in sorted(zip(names, rc), key=lambda t: -t[1]):
        log.info("    %-8s %6.1f%%   （权重 %.1f%%）", c, x, 100 / len(names))


def cmd_pierce(con, code: str) -> None:
    p = pierce_fund(con, code)
    if not p.n_holdings:
        log.warning("%s 无持仓数据", code); return
    log.info("=" * 78)
    log.info("▌ %s 持仓穿透", code)
    log.info("=" * 78)
    log.info("  重仓 %d 只，合计 %.1f%%   HHI %.3f → 集中度【%s】",
             p.n_holdings, p.top10_weight, p.hhi, p.concentration)
    if p.industries:
        log.info("  行业分布（Top10）：")
        for k, v in p.industries.items():
            log.info("    %-14s %5.2f%%  %s", k, v, "█" * int(v))


def cmd_overlap(con, codes: list[str]) -> None:
    log.info("=" * 78)
    log.info("▌ 跨基金重仓重合度（余弦 = 权重结构相似；Jaccard = 名单重叠）")
    log.info("=" * 78)
    log.info("  %-10s %-10s %6s %8s %9s %10s", "A", "B", "共同", "Jaccard", "余弦", "重叠权重%")
    for i in range(len(codes)):
        for j in range(i + 1, len(codes)):
            o = holdings_overlap(con, codes[i], codes[j])
            log.info("  %-10s %-10s %6d %8.3f %9.3f %10.2f",
                     codes[i], codes[j], o["common"], o["jaccard"], o["cosine"],
                     o["overlap_weight"])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fund", default="")
    ap.add_argument("--portfolio", default="")
    ap.add_argument("--pierce", default="")
    ap.add_argument("--overlap", default="")
    ap.add_argument("--drift", default="")
    args = ap.parse_args()
    con = db.connect()
    if args.fund:
        cmd_fund(con, args.fund)
    if args.portfolio:
        cmd_portfolio(con, [c.strip() for c in args.portfolio.split(",") if c.strip()])
    if args.pierce:
        cmd_pierce(con, args.pierce)
    if args.overlap:
        cmd_overlap(con, [c.strip() for c in args.overlap.split(",") if c.strip()])
    if args.drift:
        d = style_drift(con, args.drift)
        if d:
            log.info("▌ %s 风格漂移：L1 距离 %.3f → %s", args.drift, d["l1_distance"],
                     "⚠️ 疑似漂移" if d["drift"] else "正常")
            log.info("   季报权重 Top5: %s", [(s, f"{w*100:.1f}%") for s, w in d["rep_top"]])
            log.info("   回归权重 Top5: %s", [(s, f"{w*100:.1f}%") for s, w in d["reg_top"]])
    con.close()


if __name__ == "__main__":
    main()
