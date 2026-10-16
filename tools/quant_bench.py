"""量化方法 vs 基线：单位统一为「小数」，并正确实现自适应窗口。

单位约定（关键，之前踩过坑）：
  内部：y = 净值收益（小数，如 0.0012）、x = 因子收益（小数）
  输出：×100 转百分比
"""
from __future__ import annotations
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engine import NnRidgeRegressor, estimate_with_weights
from mkt import db
from mkt.sources.eastmoney import disclose_date, quarter_end
from quant import AdaptiveWindow, ElasticNetNN, HuberIRLS, KalmanWeightTracker, OnlineEnsemble, RLS

START, END = "2025-01-01", "2026-10-14"
BROAD = ["1.000300", "1.000905", "0.399006", "1.000688", "1.000852", "1.000016", "0.399005"]
THEMES = ["1.515070", "0.159819", "1.512480", "1.512760", "1.512720", "1.515230",
          "1.515880", "1.588000", "1.588080", "0.159781", "1.512330", "1.516010",
          "1.512930", "0.159801", "1.512690", "1.512660", "1.515790", "1.515030",
          "1.513130", "1.513050", "1.512010", "1.516160", "1.512880", "1.512800"]
BOND = ["1.511360", "1.511010", "1.511020", "1.511260", "1.511090", "1.511520",
        "1.511030", "1.511220", "1.511380", "1.511180", "1.511060", "0.159650",
        "1.000832", "NF.TS0", "NF.TF0", "NF.T0", "NF.TL0"]
GOLD_F = ["SGE.AU9999", "1.518880", "0.159934", "1.517400"]
ALLF = BROAD + THEMES + BOND + GOLD_F


def ser(con, sid):
    kl = db.load_klines(con, sid)
    it = sorted(kl.items())
    if len(it) < 30:
        return {}
    return {it[i][0]: (it[i][1] / it[i - 1][1] - 1) * 100 for i in range(1, len(it))}   # 百分比（与 engine 一致）


class Lookup:
    def __init__(self, d): self._d = d
    def get(self, secid, dt): return self._d.get(secid, {}).get(dt)


def fit_ist(con, raw, nav, d, names, kind, prior=None, window=60, lam=0.5, alpha=0.03):
    hist = [x for x in sorted(nav) if x < d][-window:]
    if len(hist) < max(20, window // 2):
        return None
    usable = [s for s in names if all(raw.get(s, {}).get(x) is not None for x in hist)
              and raw.get(s, {}).get(d) is not None]
    if len(usable) < 3:
        return None
    # 全程百分比：y ≈ 1.0、X ≈ 2.0 → β ≈ 0.5（无量纲），estimate = x·β 直接是百分比
    X = np.array([[raw[s][x] for s in usable] for x in hist])
    y = np.array([nav[x] for x in hist])
    if kind == "enet":
        b = ElasticNetNN(alpha=alpha, l1_ratio=0.6).fit(X, y)
    elif kind == "huber":
        # Huber 的先验必须是"权重占比"（0~1），不是百分比权重
        b = HuberIRLS(c=1.345, iters=6, lam=lam).fit(X, y, prior=np.array(
            [(prior or {}).get(s, 0.0) / 100.0 for s in usable]))
    else:
        b = NnRidgeRegressor(window=len(hist)).fit(
            d, {k: v * 100 for k, v in nav.items()}, usable,
            {s: (prior or {}).get(s, 0.0) * 100 for s in usable},
            Lookup({s: {k: v * 100 for k, v in raw[s].items()} for s in usable}),
            lam=lam, top_k=None)
        b = (b[1] / 100.0, b[0]) if b else None
        if b is None:
            return None
        w, ids = b
        return float(np.array([raw[s][d] for s in ids]) @ w) * 100
    return float(np.array([raw[s][d] for s in usable]) @ b)


BOND_ONLY = set(BOND)
GOLD_ONLY = set(GOLD_F)


def factor_set(code, pool):
    """按基金类型限制因子集（避免无关因子引入噪声——实测债券用全因子会更差）"""
    con2 = db.connect()
    ft = (con2.execute("SELECT fund_type FROM fund_meta WHERE code=?", (code,)).fetchone() or [""])[0] or ""
    con2.close()
    if any(k in ft for k in ("债券型", "指数型-固收", "货币型")):
        return BOND + ["1.000300"]
    if "指数型-其他" in ft and code in ("000217", "000307", "002610"):
        return GOLD_F + ["1.000300", "0.399006"]
    return ALLF


def run(con, code):
    nav = db.load_nav(con, code)
    hold = db.load_holdings(con, code)
    alloc = db.load_alloc(con, code)
    if not nav:
        return None
    pool = {s for rows in hold.values() for s, _ in rows}
    names = [s for s in sorted(pool | set(factor_set(code, pool))) if ser(con, s)]
    raw = {s: ser(con, s) for s in names}
    raw = {k: v for k, v in raw.items() if v}
    book = Lookup(raw)
    tl = sorted([(disclose_date(p, 30), quarter_end(p), dict(r), alloc.get(p))
                 for p, r in hold.items()])
    reg = NnRidgeRegressor(window=60)
    kf = rls = None
    kf_names: list[str] = []
    hedge = OnlineEnsemble(6, eta=0.08)
    aw = AdaptiveWindow(lookback=25)
    preds = {k: [] for k in ("T1", "Q1", "Q2", "Q3", "Q4", "Q5")}
    A = []

    for t, d in enumerate(sorted(x for x in nav if START <= x <= END)):
        a = nav.get(d)
        if a is None:
            continue
        cur = None
        for avail, rend, w, sr in tl:
            if avail <= date.fromisoformat(d):
                cur = (w, sr)
            else:
                break
        prior = (cur[0] if cur else {}) or {}
        cands = [s for s in raw if raw[s].get(d) is not None]
        if len(cands) < 5:
            continue
        # T1 基线
        r1 = reg.fit(d, nav, cands, prior, book, lam=0.2, top_k=20)
        t1 = estimate_with_weights(r1[0], r1[1], d, book) if r1 else None
        if t1 is None:
            continue
        # Q1 Kalman（固定池 = 持仓池 ∪ 宽基）
        if kf is None:
            kf_names = [s for s in cands if s in pool] + BROAD
            kf_names = [s for s in dict.fromkeys(kf_names) if s in raw]
            kf = KalmanWeightTracker(len(kf_names), q=5e-5, r=0.5,
                                     prior=np.array([prior.get(s, 0.0) / 100 for s in kf_names]))
        xk = np.array([raw.get(s, {}).get(d, 0.0) for s in kf_names]) / 100.0   # 小数
        q1 = kf.estimate(xk) * 100
        kf.update(xk, a / 100.0)
        # Q2 RLS
        if rls is None:
            rls = RLS(len(kf_names), lam=0.97)
        q2 = rls.estimate(xk) * 100
        rls.update(xk, a / 100.0)
        # Q3 弹性网（滚动窗口重拟合）
        q3 = fit_ist(con, raw, nav, d, cands, "enet", prior, 60, alpha=0.02)
        # Q4 Huber
        q4 = fit_ist(con, raw, nav, d, cands, "huber", prior, 60)
        # Q5 自适应窗口
        wsel = aw.choose()
        q5 = fit_ist(con, raw, nav, d, cands, "huber", prior, wsel)
        q5 = q5 if q5 is not None else t1
        arr = np.array([t1, q1, q2, q3 if q3 is not None else t1,
                        q4 if q4 is not None else t1, q5], dtype=float)
        q6 = hedge.predict(arr)
        hedge.update(arr, a, scale=0.5)
        A.append(a)
        for k, v in (("T1", t1), ("Q1", q1), ("Q2", q2),
                     ("Q3", q3 if q3 is not None else np.nan),
                     ("Q4", q4 if q4 is not None else np.nan), ("Q5", q5)):
            preds[k].append(v)
        preds.setdefault("Q6", []).append(q6)
        # 记录各窗口表现供自适应
        for wc in aw.candidates:
            rr = fit_ist(con, raw, nav, d, cands, "huber", prior, wc)
            if rr is not None:
                aw.record(wc, abs(rr - a) * 100)

    if len(A) < 60:
        return None
    A = np.array(A)
    out = {}
    for k, v in preds.items():
        v = np.array(v, dtype=float)
        m = ~np.isnan(v)
        if m.sum() < 60:
            continue
        e = v[m] - A[m]
        base = np.abs(A[m] - A[m].mean()).mean()
        mae = float(np.abs(e).mean())
        out[k] = dict(n=int(m.sum()), mae=mae,
                      improve=float((base - mae) / base * 100) if base > 0 else 0.0,
                      hit=float((np.sign(v[m]) == np.sign(A[m])).mean() * 100),
                      r2=float(np.corrcoef(v[m], A[m])[0, 1] ** 2 * 100) if v[m].std() > 0 else 0.0)
    return out


GROUPS = {
    "主动股票/混合": ["005827", "110022", "002190", "003095", "519674", "110011"],
    "科技主题": ["005729", "006281", "005962", "001513", "008903", "005911", "004997", "001156"],
    "债券": ["002811", "004102", "000015", "000402", "001021"],
    "可转债": ["000297", "003401", "000003", "008331"],
    "指数/联接/黄金": ["000961", "001593", "007339", "161725", "000217", "000307", "002610"],
}
LABELS = {"T1": "T1 滚动NNLS(基线)", "Q1": "Q1 卡尔曼滤波", "Q2": "Q2 RLS遗忘因子",
          "Q3": "Q3 非负弹性网(L1)", "Q4": "Q4 Huber稳健", "Q5": "Q5 自适应窗口",
          "Q6": "Q6 Hedge在线集成"}


def main():
    con = db.connect()
    summary = defaultdict(lambda: defaultdict(list))
    for gname, codes in GROUPS.items():
        print("\n" + "=" * 114)
        print(f"▌ {gname}")
        print("=" * 114)
        print(f"{'方法':<24}{'平均MAE':>10}{'改善':>10}{'方向命中':>10}{'R²':>9}{'胜出':>6}")
        print("-" * 114)
        agg = defaultdict(list)
        for c in codes:
            r = run(con, c)
            if r:
                for k, v in r.items():
                    agg[k].append(v)
        if not agg:
            print("  无数据"); continue
        cnt = defaultdict(int)
        for i in range(len(agg["T1"])):
            vals = {k: agg[k][i]["mae"] for k in agg if i < len(agg[k])}
            cnt[min(vals, key=vals.get)] += 1
        for k in ("T1", "Q1", "Q2", "Q3", "Q4", "Q5", "Q6"):
            v = agg.get(k)
            if not v:
                continue
            print(f"{LABELS[k]:<24}{np.mean([x['mae'] for x in v]):>10.4f}"
                  f"{np.mean([x['improve'] for x in v]):>9.1f}%"
                  f"{np.mean([x['hit'] for x in v]):>9.1f}%"
                  f"{np.mean([x['r2'] for x in v]):>9.1f}{cnt.get(k,0):>6}")
            summary[k]["mae"].append(np.mean([x['mae'] for x in v]))
            summary[k]["hit"].append(np.mean([x['hit'] for x in v]))
        print(f"  （{len(agg['T1'])} 只基金）")
    print("\n" + "=" * 114)
    print("▌ 跨品类汇总（各品类平均后再平均）")
    print("=" * 114)
    print(f"{'方法':<24}{'平均MAE':>10}{'方向命中':>10}")
    for k in ("T1", "Q1", "Q2", "Q3", "Q4", "Q5", "Q6"):
        v = summary.get(k)
        if v and v["mae"]:
            print(f"{LABELS[k]:<24}{np.mean(v['mae']):>10.4f}{np.mean(v['hit']):>9.1f}%")


if __name__ == "__main__":
    main()
