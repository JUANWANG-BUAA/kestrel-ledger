"""回测 v2：用真实历史数据评估估值引擎精度。

相对 v1 的修正：
  1. **共同样本**：所有模型只在"全部模型都有产出"的交易日上比较，避免样本量不一致
  2. **有效方向命中**：只在大波动日（|实际| >= 阈值）上统计方向，这才是决策相关的指标
  3. **报告期新鲜度分层**：量化"持仓滞后"对精度的贡献
  4. **滞后敏感性**：lag = 0/15/30/60 天，回答"披露延迟值多少 MAE"

用法：
    python3 tools/backtest.py --start 2025-01-01 --end 2026-10-14 --lag 30
    python3 tools/backtest.py --sweep-lag
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from engine import (  # noqa: E402
    BiasCalibrator,
    HoldingSet,
    RegressionProxy,
    ReturnsBook,
    m1_penetration,
    m2_normalized,
    m3_plus_bench,
)
from mkt import db  # noqa: E402
from mkt.sources.eastmoney import disclose_date, quarter_end  # noqa: E402

BENCH = ["1.000300", "0.399006", "1.000905"]   # 沪深300 / 创业板指 / 中证500
BENCH_PRIMARY = "1.000300"
MODELS = ["M1_穿透", "M2_归一化", "M3_补基准", "M4_补基准+校准", "M5_回归", "M0_基准"]


# --------------------------------------------------------------------------- #
def build_timeline(holdings, alloc, lag: int):
    out = []
    for period, rows in holdings.items():
        out.append(
            (
                disclose_date(period, lag),
                quarter_end(period),
                HoldingSet(period=period, weights=dict(rows), stock_ratio=alloc.get(period)),
            )
        )
    out.sort(key=lambda x: x[0])
    return out


def collect_daily(con, code: str, start: date, end: date, lag: int, nav_lag: int, window: int):
    """返回逐日记录 list[dict]，包含全部模型输出与实际值。"""
    nav_pct = db.load_nav(con, code)
    holdings = db.load_holdings(con, code)
    alloc = db.load_alloc(con, code)
    if not nav_pct or not holdings:
        return []

    secids = {s for rows in holdings.values() for s, _ in rows}
    klines = {s: db.load_klines(con, s) for s in secids}
    for b in BENCH:
        klines[b] = db.load_klines(con, b)
    book = ReturnsBook(klines)

    timeline = build_timeline(holdings, alloc, lag)
    reg = RegressionProxy(BENCH, window=window)
    calib = BiasCalibrator()

    dates = sorted(d for d in nav_pct if start <= date.fromisoformat(d) <= end)
    pos = {d: i for i, d in enumerate(dates)}
    records = []

    for d in dates:
        dd = date.fromisoformat(d)
        hs, report_end = None, None
        for avail, rend, cand in timeline:
            if avail <= dd:
                hs, report_end = cand, rend
            else:
                break
        if hs is None:
            continue

        bench = book.get(BENCH_PRIMARY, d)
        m0 = bench
        m1 = m1_penetration(hs, book, d)
        m2 = m2_normalized(hs, book, d)
        m3 = m3_plus_bench(hs, book, d, bench)

        beta = reg.fit(d, nav_pct, book)
        m5 = None
        if beta is not None:
            proxy = reg.predict(beta, d, book)
            if proxy is not None:
                m5 = m3_plus_bench(hs, book, d, proxy)

        # 实际值：nav_lag 平移（QDII 净值反映的是此前海外市场表现）
        j = pos[d] + nav_lag
        if j >= len(dates):
            continue
        act = nav_pct.get(dates[j])
        if act is None:
            continue

        m4 = calib.apply(m3) if m3 is not None else None
        rec = {
            "date": d, "actual": act, "M0_基准": m0, "M1_穿透": m1,
            "M2_归一化": m2, "M3_补基准": m3, "M4_补基准+校准": m4, "M5_回归": m5,
            "stale_days": (dd - report_end).days if report_end else None,
            "disclosed": hs.disclosed_sum, "stock_ratio": hs.stock_ratio,
        }
        records.append(rec)
        if m3 is not None:
            calib.update(m3, act)

    return records


# --------------------------------------------------------------------------- #
def common_sample(records, models) -> np.ndarray:
    """所有给定模型都非 nan 的行掩码。"""
    mask = np.ones(len(records), dtype=bool)
    for m in models:
        mask &= np.array([r[m] is not None and not np.isnan(r[m]) for r in records])
    return mask


def metric_block(est: np.ndarray, act: np.ndarray) -> dict:
    if len(est) == 0:
        return {}
    err = est - act
    ae = np.abs(err)
    out = {
        "n": len(est),
        "MAE": round(float(ae.mean()), 4),
        "RMSE": round(float(np.sqrt((err**2).mean())), 4),
        "命中%": round(float((np.sign(est) == np.sign(act)).mean() * 100), 1),
        "P95": round(float(np.percentile(ae, 95)), 4),
        "偏差": round(float(err.mean()), 4),
    }
    if len(est) > 2 and est.std() > 0 and act.std() > 0:
        out["相关"] = round(float(np.corrcoef(est, act)[0, 1]), 3)
    return out


def evaluate(records, models, big_threshold=0.5):
    """返回 {model: 指标}，同时给出大波动日子集指标。"""
    if not records:
        return {}, {}
    mask = common_sample(records, models)
    sub = [r for r, m in zip(records, mask) if m]
    if len(sub) < 20:
        return {}, {}

    act = np.array([r["actual"] for r in sub])
    full, big = {}, {}
    big_mask = np.abs(act) >= big_threshold
    for m in models:
        est = np.array([r[m] for r in sub], dtype=float)
        full[m] = metric_block(est, act)
        if big_mask.sum() >= 10:
            big[m] = metric_block(est[big_mask], act[big_mask])
    return full, big


def staleness_buckets(records, model="M1_穿透", edges=(0, 30, 90, 180, 999)):
    out = []
    for lo, hi in zip(edges[:-1], edges[1:]):
        sub = [
            r for r in records
            if r[model] is not None and r["stale_days"] is not None and lo <= r["stale_days"] < hi
        ]
        if len(sub) < 10:
            continue
        est = np.array([r[model] for r in sub])
        act = np.array([r["actual"] for r in sub])
        m = metric_block(est, act)
        m["区间"] = f"{lo}-{hi if hi != 999 else '∞'}天"
        out.append(m)
    return out


# --------------------------------------------------------------------------- #
def run_one(code: str, name: str, start: date, end: date, lag: int, nav_lag: int,
            window: int, verbose: bool = True) -> dict | None:
    con = db.connect()
    records = collect_daily(con, code, start, end, lag, nav_lag, window)
    con.close()
    if len(records) < 30:
        return None
    full, big = evaluate(records, MODELS)
    if not full:
        return None
    res = {"code": code, "name": name, "n": len(records), "models": full, "big": big,
           "staleness": staleness_buckets(records)}
    if verbose:
        print(f"\n▌ {code} {name}  样本 {len(records)} 天")
        print(f"  {'模型':<16} {'MAE%':>7} {'RMSE%':>7} {'命中%':>7} {'P95':>7} {'相关':>6} │ "
              f"{'大波动n':>7} {'MAE%':>7} {'命中%':>7}")
        for m in MODELS:
            if m not in full:
                continue
            f, b = full[m], big.get(m, {})
            print(f"  {m:<16} {f['MAE']:>7.3f} {f['RMSE']:>7.3f} {f['命中%']:>7.1f} "
                  f"{f['P95']:>7.3f} {f.get('相关', float('nan')):>6.3f} │ "
                  f"{b.get('n', 0):>7} {b.get('MAE', float('nan')):>7.3f} {b.get('命中%', float('nan')):>7.1f}")
        print("  报告期新鲜度（M1）：")
        for s in res["staleness"]:
            print(f"    {s['区间']:<10} n={s['n']:>4}  MAE={s['MAE']:.3f}%  命中={s['命中%']:.1f}%")
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default="2025-01-01")
    ap.add_argument("--end", default="2026-10-14")
    ap.add_argument("--lag", type=int, default=30)
    ap.add_argument("--window", type=int, default=120)
    ap.add_argument("--funds", default="")
    ap.add_argument("--qdii-nav-lag", type=int, default=1)
    ap.add_argument("--sweep-lag", action="store_true")
    ap.add_argument("--json", default="")
    args = ap.parse_args()

    con = db.connect()
    metas = list(con.execute("SELECT code,name FROM fund_meta ORDER BY code"))
    con.close()
    if args.funds:
        want = {c.strip() for c in args.funds.split(",") if c.strip()}
        metas = [(c, n) for c, n in metas if c in want]

    start, end = date.fromisoformat(args.start), date.fromisoformat(args.end)
    QDII = {"110011", "007339"}

    if args.sweep_lag:
        print(f"\n{'='*100}")
        print(f"披露滞后敏感性分析（{args.start} ~ {args.end}，M1 穿透 / M3 补基准）")
        print(f"{'='*100}")
        print(f"  {'lag':>5} │ {'M1 MAE%':>9} {'M1命中%':>8} │ {'M3 MAE%':>9} {'M3命中%':>8} │ 说明")
        for lag in (0, 15, 30, 45, 60, 90):
            a1, a3, h1, h3 = [], [], [], []
            for code, name in metas:
                if code in QDII:
                    continue
                r = run_one(code, name, start, end, lag, 0, args.window, verbose=False)
                if not r or "M1_穿透" not in r["models"]:
                    continue
                a1.append(r["models"]["M1_穿透"]["MAE"])
                h1.append(r["models"]["M1_穿透"]["命中%"])
                if "M3_补基准" in r["models"]:
                    a3.append(r["models"]["M3_补基准"]["MAE"])
                    h3.append(r["models"]["M3_补基准"]["命中%"])
            note = {0: "理论上限（季末即可见）", 15: "接近监管最短", 30: "保守默认",
                    45: "偏悲观", 60: "很悲观", 90: "极限"} [lag]
            print(f"  {lag:>5} │ {np.mean(a1):>9.3f} {np.mean(h1):>8.1f} │ "
                  f"{np.mean(a3):>9.3f} {np.mean(h3):>8.1f} │ {note}")
        return

    all_res = []
    for code, name in metas:
        nav_lag = args.qdii_nav_lag if code in QDII else 0
        r = run_one(code, name, start, end, args.lag, nav_lag, args.window)
        if r:
            r["nav_lag"] = nav_lag
            all_res.append(r)

    # ---- 汇总 ----
    print(f"\n{'='*118}")
    print(f"汇总（{args.start} ~ {args.end}，披露滞后 {args.lag} 天）")
    print(f"{'='*118}")
    print(f"  {'模型':<16} {'覆盖基金':>8} {'平均MAE%':>9} {'加权MAE%':>9} {'平均命中%':>9} "
          f"{'大波动MAE%':>10} {'大波动命中%':>11}")
    for m in MODELS:
        maes, hits, bigs, bighs, ws, nsum = [], [], [], [], [], 0
        for r in all_res:
            f = r["models"].get(m)
            if not f:
                continue
            maes.append(f["MAE"]); hits.append(f["命中%"]); ws.append(f["MAE"] * f["n"]); nsum += f["n"]
            b = r["big"].get(m)
            if b:
                bigs.append(b["MAE"]); bighs.append(b["命中%"])
        if not maes:
            continue
        print(f"  {m:<16} {len(maes):>8} {np.mean(maes):>9.3f} "
              f"{(sum(ws)/nsum if nsum else float('nan')):>9.3f} {np.mean(hits):>9.1f} "
              f"{(np.mean(bigs) if bigs else float('nan')):>10.3f} "
              f"{(np.mean(bighs) if bighs else float('nan')):>11.1f}")

    if args.json:
        Path(args.json).write_text(
            json.dumps(all_res, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(f"\n结果已写入 {args.json}")


if __name__ == "__main__":
    main()
