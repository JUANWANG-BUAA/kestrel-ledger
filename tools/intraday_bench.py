"""盘中实时估算验证：各时点估算 vs 当日最终净值。

数据：债券ETF 30分钟线(40天) + 国债期货30分钟线(115天)
修正：所有因子都必须有盘中数据，否则权重被静默丢弃 → 估算失真
"""
from __future__ import annotations
import sys
from collections import defaultdict
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engine import NnRidgeRegressor
from mkt import db

BOND_ETF = ["1.511010","1.511260","1.511020","1.511030","1.511520","1.511380",
            "1.511360","1.511220","1.511180","1.511060","0.159650"]
FUT = ["NF.TS0","NF.TF0","NF.T0","NF.TL0"]
CUTS = [("1030","10:30"),("1100","11:00"),("1130","11:30"),("1330","13:30"),
        ("1400","14:00"),("1430","14:30"),("1500","15:00")]
FUNDS = [("002811","博时裕顺纯债"),("004102","中信保诚稳悦"),("000015","华夏纯债"),
         ("000402","工银纯债"),("002920","中欧短债"),("004672","华夏短债"),
         ("002301","兴业短债"),("000207","建信双债增强"),("164814","工银双债增强"),
         ("006633","博时1-3政金债"),("003429","中证兴业信用债"),("008216","农银利率债"),
         ("003358","易方达7-10国开"),("001021","华夏亚债中国"),("003401","工银可转债")]


def main():
    con = db.connect()
    # 统一盘中表
    intr = {}
    for tbl in ("intraday30", "intraday30b"):
        for sid, ts, c in con.execute(f"SELECT secid,ts,close FROM {tbl}"):
            intr.setdefault(sid, {})[ts] = c
    dates_etf = sorted({ts[:8] for ts in intr.get("1.511010", {})})
    dates_fut = sorted({ts[:8] for ts in intr.get("NF.T0", {})})
    common = sorted(set(dates_etf) & set(dates_fut))
    print(f"债券ETF 盘中天数={len(dates_etf)}  期货盘中天数={len(dates_fut)}  交集={len(common)}")

    prev = {}
    for sid in intr:
        kl = db.load_klines(con, sid)
        it = sorted(kl.items())
        prev[sid] = {it[i][0]: it[i - 1][1] for i in range(1, len(it))}

    def mv(sid, day8, upto):
        p = prev.get(sid, {}).get(f"{day8[:4]}-{day8[4:6]}-{day8[6:8]}")
        if not p:
            return None
        d = intr.get(sid, {})
        ks = [t for t in d if t[:8] == day8 and t[8:12] <= upto]
        if not ks:
            return None
        return (d[max(ks)] / p - 1) * 100

    def daily_ser(sid):
        kl = db.load_klines(con, sid)
        it = sorted(kl.items())
        return {it[i][0]: (it[i][1] / it[i - 1][1] - 1) * 100 for i in range(1, len(it))}

    raw = {s: daily_ser(s) for s in BOND_ETF + FUT}
    raw = {k: v for k, v in raw.items() if v}

    class RL:
        def __init__(s, d): s._d = d
        def get(s, sec, dt): return s._d.get(sec, {}).get(dt)

    book = RL(raw)

    print("\n" + "=" * 122)
    print("盘中实时估算：各时点 vs 当日最终官方净值（MAE，bp）")
    print("=" * 122)
    print(f"{'基金':<8}{'名称':<16}{'σ(bp)':>8}{'零基线':>8}" + "".join(f"{c[1]:>9}" for c in CUTS))
    print("-" * 122)
    agg = defaultdict(list)
    base_all = []
    for code, nm in FUNDS:
        nav = db.load_nav(con, code)
        ds = [f"{d[:4]}-{d[4:6]}-{d[6:8]}" for d in common]
        ds = [d for d in ds if d in nav]
        if len(ds) < 15:
            continue
        A = np.array([nav[d] for d in ds])
        base = np.abs(A - A.mean()).mean() * 100
        base_all.append(base)
        row = []
        for cut, _ in CUTS:
            errs = []
            reg = NnRidgeRegressor(window=60)
            for day in ds:
                day8 = day.replace("-", "")
                vals = {s: mv(s, day8, cut) for s in BOND_ETF + FUT}
                ids = [s for s, v in vals.items() if v is not None]
                if len(ids) < 4:
                    continue
                r = reg.fit(day, nav, ids, {}, book, lam=0.5, top_k=6, min_corr=0.03)
                if not r or len(r[0]) == 0:
                    continue
                tot = sum(w * vals[s] for s, w in zip(r[0], r[1]) if vals.get(s) is not None)
                errs.append(abs(tot / 100.0 - nav[day]) * 100)
            m = float(np.mean(errs)) if errs else np.nan
            row.append(m)
            agg[cut].append(m)
        print(f"{code:<8}{nm:<16}{A.std()*100:>8.2f}{base:>8.2f}" +
              "".join(f"{v:>9.2f}" if not np.isnan(v) else f"{'—':>9}" for v in row))
    print("-" * 122)
    print(f"{'平均 MAE(bp)':<32}{np.mean(base_all):>8.2f}" +
          "".join(f"{np.nanmean(agg[c[0]]):>9.2f}" for c in CUTS))
    print(f"{'相对零基线改善':<32}{'':>8}" +
          "".join(f"{(1-np.nanmean(agg[c[0]])/np.mean(base_all))*100:>8.0f}%" for c in CUTS))


if __name__ == "__main__":
    main()
