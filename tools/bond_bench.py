"""债券/货币基金估值：多算法交叉验证。

候选算法：
  B0 零基线       pct = 0（债券基金日内波动极小，这是强基线）
  B1 均值基线     pct = 近 20 日均值
  B2 单因子ETF    最相关债券 ETF 的滚动 OLS
  B3 多因子ETF    债券 ETF 因子集 NNLS
  B4 国债期货     TS/TF/T/TL 多因子 NNLS  ← 盘中实时可得
  B5 混合         ETF + 期货
  B6 久期理论     -D_mod × Δyield（由期货反推收益率变动）

指标（债券专用，单位 bp = 0.01%）：
  MAE(bp) / R² / 显著日方向命中(|实际|≥2bp) / 相对零基线改善
"""
from __future__ import annotations
import sys
from datetime import date
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engine import NnRidgeRegressor
from mkt import db

BOND_ETF = ["1.511360", "1.511010", "1.511020", "1.511260", "1.511090",
            "1.511520", "1.511030", "1.511220", "1.511380", "1.511180",
            "1.511060", "0.159650", "1.511130"]
FUTURES = ["NF.TS0", "NF.TF0", "NF.T0", "NF.TL0"]
BOND_TYPES = ("债券型", "指数型-固收")
MMF_TYPES = ("货币型",)


class RetLookup:
    """适配 NnRidgeRegressor 的 book.get(secid, date) 接口。"""
    def __init__(self, d): self._d = d
    def get(self, secid, dt):
        return self._d.get(secid, {}).get(dt)


def series(con, sid):
    """⚠️ 必须用**复权修正后**的收益：债券 ETF 按月/季分红，
    未修正会产生 −1.1% 的假跳空（z=−11），严重污染因子回归。"""
    from adjust import raw_returns
    return raw_returns(con, sid, adjust=True)


def run_fund(con, code, start="2025-01-01", end="2026-10-14", window=60):
    nav = db.load_nav(con, code)
    if not nav:
        return None
    dates = sorted(x for x in nav if start <= x <= end)
    if len(dates) < 60:
        return None
    raw = {s: series(con, s) for s in BOND_ETF + FUTURES}
    raw = {k: v for k, v in raw.items() if v}
    book = RetLookup(raw)
    reg = NnRidgeRegressor(window=window)
    out = {k: [] for k in ("B0", "B1", "B2", "B3", "B4", "B5", "B6")}
    A = []
    for i, d in enumerate(dates):
        a = nav[d]
        h = [nav[x] for x in dates[max(0, i - 20):i]]
        out["B0"].append(0.0)
        out["B1"].append(float(np.mean(h)) if h else 0.0)
        # B2 单因子（历史上最相关的 ETF）
        best, bestv = None, None
        hist = [x for x in dates[:i]]
        if len(hist) > 80:
            hh = hist[-120:]
            ya = np.array([nav[x] for x in hh])
            for sid, rr in raw.items():
                if not all(x in rr for x in hh) or not all(x in rr for x in hh):
                    continue
                xa = np.array([rr[x] for x in hh])
                if xa.std() <= 0:
                    continue
                c = abs(np.corrcoef(xa, ya)[0, 1])
                if bestv is None or c > bestv:
                    bestv, best = c, sid
        if best and d in raw[best]:
            hh = [x for x in dates[:i]][-window:]
            if len(hh) > 30 and all(x in raw[best] for x in hh):
                b_, a_ = np.polyfit(np.array([raw[best][x] for x in hh]),
                                    np.array([nav[x] for x in hh]), 1)
                bestv2 = b_ * raw[best][d] + a_
            else:
                bestv2 = np.nan
        else:
            bestv2 = np.nan
        out["B2"].append(bestv2)
        # B3/B4/B5 回归
        def fit_reg(cands, prior=None, lam=0.5, top_k=None):
            r = reg.fit(d, nav, cands, prior or {}, book, lam=lam, top_k=top_k, min_corr=0.05)
            if not r or len(r[0]) == 0:
                return np.nan
            tot = 0.0
            for sid, w in zip(r[0], r[1]):
                v = raw.get(sid, {}).get(d)
                if v is not None:
                    tot += w * v
            return tot / 100.0
        out["B3"].append(fit_reg(BOND_ETF, lam=0.5, top_k=6))
        out["B4"].append(fit_reg(FUTURES, lam=0.5, top_k=4))
        out["B5"].append(fit_reg(BOND_ETF + FUTURES, lam=0.5, top_k=8))
        # B6 久期理论：用 10 年期货反推收益率变动 → 净值变动 ≈ -D*Δy
        v = np.nan
        if i > 0:
            r10 = raw.get("NF.T0", {}).get(d)
            if r10 is not None:
                D = 4.0  # 组合修正久期（可标定）
                dy = -(r10 / 100.0) / 7.5   # 10年期货久期约 7.5
                v = -D * dy * 100
        out["B6"].append(v)
        A.append(a)
    A = np.array(A)
    res = {}
    for k, v in out.items():
        v = np.array(v, dtype=float)
        m = ~np.isnan(v)
        if m.sum() < 50:
            continue
        e = v[m] - A[m]
        sig = np.abs(A[m]) >= 0.02
        r = np.corrcoef(v[m], A[m])[0, 1] if v[m].std() > 1e-9 else 0.0
        res[k] = dict(
            n=int(m.sum()),
            mae_bp=float(np.abs(e).mean() * 100),
            r2=float(r * r * 100),
            hit_all=float((np.sign(v[m]) == np.sign(A[m])).mean() * 100),
            n_sig=int(sig.sum()),
            hit_sig=float((np.sign(v[m][sig]) == np.sign(A[m][sig])).mean() * 100) if sig.sum() > 10 else float("nan"),
            bias_bp=float(e.mean() * 100),
        )
    return res, len(A), float(np.std(A) * 100)


def main():
    con = db.connect()
    metas = list(con.execute("SELECT code,name,fund_type FROM fund_meta ORDER BY code"))
    bond = [(c, n, t) for c, n, t in metas
            if any(k in (t or "") for k in BOND_TYPES) and "可转债" not in (n or "")]
    cb = [(c, n, t) for c, n, t in metas if "可转债" in (n or "")]
    mmf = [(c, n, t) for c, n, t in metas if any(k in (t or "") for k in MMF_TYPES)]

    for title, group in (("中长期纯债 / 短债 / 一级二级债基 / 债券指数", bond),
                         ("可转债基金（含股票仓位）", cb),
                         ("货币基金", mmf)):
        print("\n" + "=" * 126)
        print(f"▌ {title}")
        print("=" * 126)
        print(f"{'基金':<8}{'名称':<24}{'年度σ':>8}{'零基线':>9}{'均值':>9}{'单ETF':>9}"
              f"{'多ETF':>9}{'国债期货':>10}{'ETF+期货':>10}{'久期模型':>10}")
        print(f"{'':<8}{'':<24}{'(bp)':>8}{'MAE':>9}{'MAE':>9}{'MAE':>9}{'MAE':>9}{'MAE':>10}{'MAE':>10}{'MAE':>10}")
        print("-" * 126)
        agg = {}
        for c, n, t in group:
            r = run_fund(con, c)
            if not r:
                continue
            res, nsamp, sigma = r
            def g(k):
                return f"{res[k]['mae_bp']:.2f}" if k in res else "—"
            print(f"{c:<8}{(n or '')[:22]:<24}{sigma:>8.2f}{g('B0'):>9}{g('B1'):>9}{g('B2'):>9}"
                  f"{g('B3'):>9}{g('B4'):>10}{g('B5'):>10}{g('B6'):>10}")
            for k, v in res.items():
                agg.setdefault(k, []).append(v)
        if agg:
            print("-" * 126)
            line = f"{'平均':<8}{'':<24}{'':>8}"
            for k in ("B0", "B1", "B2", "B3", "B4", "B5", "B6"):
                v = agg.get(k)
                line += f"{np.mean([x['mae_bp'] for x in v]):>{len(k)+5}.2f}" if v else f"{'—':>{len(k)+5}}"
            print(line)
            print()
            for k in ("B0", "B1", "B2", "B3", "B4", "B5", "B6"):
                v = agg.get(k)
                if not v:
                    continue
                hs = [x['hit_sig'] for x in v if not np.isnan(x['hit_sig'])]
                print(f"  {k}: MAE={np.mean([x['mae_bp'] for x in v]):.2f}bp  "
                      f"R²={np.mean([x['r2'] for x in v]):.1f}%  "
                      f"显著日方向命中={np.mean(hs):.1f}%  偏差={np.mean([x['bias_bp'] for x in v]):+.2f}bp")


if __name__ == "__main__":
    main()
