"""最终基准：模型层级 + 信息扩展 的完整对照，输出基准表。

层级（按优先级路由）：
  T0 场内/指数直连   滚动 OLS 追踪 目标指数 或 目标ETF净值   → R²≈100%
  T1 扩展池回归      全历史持仓池 + 主题因子 + 稀疏选择       → 主动基金主力
  T2 季报穿透        最近一期十大重仓直接加权                  → 兜底
"""
from __future__ import annotations
import sys
from datetime import date
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engine import ReturnsBook, HoldingSet, m1_penetration, NnRidgeRegressor, estimate_with_weights
from mkt import db
from mkt.sources.eastmoney import disclose_date, quarter_end

BROAD = ["1.000300", "1.000905", "0.399006", "1.000688", "1.000852"]
THEMES = ["1.515070", "1.512480", "1.512010", "1.515030", "1.512690", "1.512660",
          "1.512720", "1.515790", "1.513130", "1.513050", "1.512880", "1.512800", "1.516160"]
ALLF = BROAD + THEMES
# 指数/联接/LOF → 目标指数（生产中由 FundMNInverstPosition 的 ETFCODE / 基金合同业绩基准自动获得）
IDX = {"000961": "1.000300", "007339": "1.000300", "001593": "0.399006", "161725": "0.399997"}


class RLS:
    """递归最小二乘 + 遗忘因子（在线权重追踪）。"""
    def __init__(self, n, lam=0.97, delta=1e2):
        self.n, self.lam, self.P, self.b = n, lam, delta * np.eye(n), np.zeros(n)
    def update(self, x, y):
        Px = self.P @ x
        k = Px / (self.lam + float(x @ Px))
        self.b = self.b + k * (y - float(x @ self.b))
        self.P = (self.P - np.outer(k, Px)) / self.lam
        return self.b


def _rets(kl):
    it = sorted(kl.items())
    return {it[i][0]: (it[i][1] / it[i-1][1] - 1) * 100 for i in range(1, len(it))}


def build(con, code):
    nav = db.load_nav(con, code)
    hold = db.load_holdings(con, code)
    alloc = db.load_alloc(con, code)
    pool = {s for rows in hold.values() for s, _ in rows}
    kl = {s: db.load_klines(con, s) for s in pool | set(ALLF) | set(IDX.values())}
    book = ReturnsBook(kl)
    tl = sorted([(disclose_date(p, 30), quarter_end(p), dict(r), alloc.get(p)) for p, r in hold.items()])
    return nav, book, tl, pool


def t0_series(nav, book, idx_sid, window=60):
    r = _rets(db.load_klines_holder[idx_sid]) if False else None
    return None


def run(con, code, start="2025-01-01", end="2026-10-14"):
    nav, book, tl, pool = build(con, code)
    reg = NnRidgeRegressor(window=60)
    out = {k: [] for k in ("T0", "T1", "T2", "RLS")}
    A, dates_used = [], []
    idx_sid = IDX.get(code)
    idx_ret = {}
    if idx_sid:
        kl = db.load_klines(con, idx_sid)
        it = sorted(kl.items())
        idx_ret = {it[i][0]: (it[i][1] / it[i-1][1] - 1) * 100 for i in range(1, len(it))}
    rls = None
    for d in sorted(x for x in nav if start <= x <= end):
        cur = None
        for avail, rend, w, sr in tl:
            if avail <= date.fromisoformat(d):
                cur = (w, sr)
            else:
                break
        a = nav.get(d)
        if a is None:
            continue
        # T0
        v0 = np.nan
        if idx_sid:
            hist_d = sorted(x for x in nav if x < d)[-60:]
            xs = [idx_ret.get(x) for x in hist_d]
            ys = [nav[x] for x in hist_d]
            if len(hist_d) >= 30 and all(v is not None for v in xs) and idx_ret.get(d) is not None:
                b_, a_ = np.polyfit(np.array(xs), np.array(ys), 1)
                v0 = b_ * idx_ret[d] + a_
        v1 = v2 = np.nan
        if cur:
            w, sr = cur
            pool_ids = [s for s in pool if book.get(s, d) is not None]
            # ⚠️ 实测调优（候选池扩至 151 只后）：
            #   top_k 限制反而有害（topk=8 → 0.5235%；不限 → 0.3215%）
            #   lam 需加强（lam=0.2 → 0.3957%；lam=4.0 → 0.3215%）
            #   → 全量持仓下最优为 lam=4.0 + 不限制 top_k
            r1 = reg.fit(d, nav, pool_ids + ALLF, w, book, lam=4.0, top_k=None) if pool_ids else None
            if r1:
                v1 = estimate_with_weights(r1[0], r1[1], d, book)
            v2 = m1_penetration(HoldingSet(period="", weights=w, stock_ratio=sr), book, d)
            v2 = np.nan if v2 is None else v2
        out["T0"].append(v0); out["T1"].append(np.nan if v1 is None else v1)
        out["T2"].append(v2); out["RLS"].append(np.nan)
        A.append(a); dates_used.append(d)

    A = np.array(A)
    res = {}
    for k, v in out.items():
        if k == "RLS":
            continue
        v = np.array(v, dtype=float); m = ~np.isnan(v)
        if m.sum() < 30:
            continue
        e = v[m] - A[m]
        r = np.corrcoef(v[m], A[m])[0, 1] if v[m].std() > 0 else 0
        res[k] = dict(n=int(m.sum()), mae=float(np.abs(e).mean()), r2=float(r * r * 100),
                      hit=float((np.sign(v[m]) == np.sign(A[m])).mean() * 100))
    # 路由：优先 T0 → T1 → T2
    route = []
    for i in range(len(A)):
        for k in ("T0", "T1", "T2"):
            v = out[k][i]
            if v is not None and not (isinstance(v, float) and np.isnan(v)):
                route.append(v)
                break
        else:
            route.append(np.nan)
    route = np.array(route, dtype=float); m = ~np.isnan(route)
    if m.sum() >= 30:
        e = route[m] - A[m]
        r = np.corrcoef(route[m], A[m])[0, 1] if route[m].std() > 0 else 0
        res["ROUTED"] = dict(n=int(m.sum()), mae=float(np.abs(e).mean()), r2=float(r * r * 100),
                             hit=float((np.sign(route[m]) == np.sign(A[m])).mean() * 100))
    return res, len(pool)


def main():
    con = db.connect()
    metas = list(con.execute("SELECT code,name,fund_type FROM fund_meta ORDER BY code"))
    rows = []
    print(f"{'基金':<8}{'名称':<22}{'类型':<14}{'池':>4}{'T0':>16}{'T1扩展池':>16}{'T2季报':>16}{'路由':>16}")
    print("-" * 118)
    for code, name, ftype in metas:
        if not any(k in (ftype or "") for k in ("股票", "混合", "QDII", "指数型-股票")):
            continue
        res, npool = run(con, code)
        if not res:
            continue
        def c(k):
            return f"{res[k]['mae']:.3f}/{res[k]['r2']:.0f}" if k in res else "—"
        rows.append((code, name, ftype, res))
        print(f"{code:<8}{name[:20]:<22}{(ftype or '')[:12]:<14}{npool:>4}"
              f"{c('T0'):>16}{c('T1'):>16}{c('T2'):>16}{c('ROUTED'):>16}")
    print("-" * 118)
    print("（格式：MAE% / R²%）\n")
    for k in ("T0", "T1", "T2", "ROUTED"):
        v = [r[3][k] for r in rows if k in r[3]]
        if v:
            print(f"  {k:<8} 覆盖 {len(v):>2} 只   平均 MAE={np.mean([x['mae'] for x in v]):.3f}%   "
                  f"中位 R²={np.median([x['r2'] for x in v]):.1f}%   平均命中={np.mean([x['hit'] for x in v]):.1f}%")
    import json
    Path("data/report/benchmark.json").parent.mkdir(parents=True, exist_ok=True)
    Path("data/report/benchmark.json").write_text(
        json.dumps([{"code": c, "name": n, "type": t, "res": r} for c, n, t, r in rows],
                   ensure_ascii=False, indent=1), encoding="utf-8")
    print("\n✅ data/report/benchmark.json")


if __name__ == "__main__":
    import json
    main()
