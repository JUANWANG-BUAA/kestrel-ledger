"""国际金价 → 国内金价 → 银行积存金：全链路误差传递分析

链路：
  ① 折算金价(元/克) = XAU(USD/oz) × USDCNY / 31.1034768
  ② 内外盘溢价 = 上金所Au99.99 − 折算金价
  ③ 银行积存金 = 国内金价 ± 银行点差
"""
from __future__ import annotations
import sys
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt import db

TROY_OZ_G = 31.1034768


def load(con, sid):
    return db.load_klines(con, sid)


def main():
    con = db.connect()
    xau = load(con, "XAU.XAU")
    cny = load(con, "FX.USDCNY")
    cnh = load(con, "FX.USDCNH")
    au = load(con, "SGE.AU9999")
    gc = load(con, "XAU.GC")
    shau = load(con, "SGE.SHAU")

    days = sorted(set(au) & set(xau) & set(cny))
    print(f"重叠交易日: {len(days)}  ({days[0]} ~ {days[-1]})")

    conv = {d: xau[d] * cny[d] / TROY_OZ_G for d in days}
    au_v = np.array([au[d] for d in days])
    cv = np.array([conv[d] for d in days])
    prem = au_v - cv
    prem_pct = prem / cv * 100

    print("\n" + "=" * 100)
    print("① 折算精度：用「伦敦金 × 汇率 ÷ 31.1034768」直接推算上金所 Au99.99")
    print("=" * 100)
    err = prem
    print(f"  平均误差(溢价)   : {err.mean():+8.3f} 元/克   ({err.mean()/au_v.mean()*100:+.3f}%)")
    print(f"  标准差           : {err.std():8.3f} 元/克")
    print(f"  平均绝对误差 MAE  : {np.abs(err).mean():8.3f} 元/克")
    print(f"  分位数 |误差|    : P50={np.percentile(np.abs(err),50):.2f}  "
          f"P90={np.percentile(np.abs(err),90):.2f}  P99={np.percentile(np.abs(err),99):.2f}")
    print(f"  相关性 r         : {np.corrcoef(au_v, cv)[0,1]:.6f}   R²={np.corrcoef(au_v,cv)[0,1]**2*100:.4f}%")

    print("\n" + "=" * 100)
    print("② 内外盘溢价的稳定性（这是误差的主要来源）")
    print("=" * 100)
    # 分年统计
    import collections
    byyear = collections.defaultdict(list)
    for d, p in zip(days, prem_pct):
        byyear[d[:4]].append(p)
    print(f"  {'年份':<8}{'均值%':>9}{'标准差%':>10}{'P5%':>8}{'P95%':>8}")
    for y in sorted(byyear)[-8:]:
        v = np.array(byyear[y])
        print(f"  {y:<8}{v.mean():>9.3f}{v.std():>10.3f}{np.percentile(v,5):>8.3f}{np.percentile(v,95):>8.3f}")
    print("\n  溢价（元/克）自相关（均值回归特征）：")
    p = np.array(prem)
    for k in (1, 2, 5, 10, 20):
        print(f"    ACF({k:2d}) = {np.corrcoef(p[:-k], p[k:])[0,1]:+.3f}")

    print("\n" + "=" * 100)
    print("③ 误差传递到「银行积存金」")
    print("=" * 100)
    print("  银行积存金 = 国内金价 ± 点差，因此推算误差 = 折算误差 + 点差标定误差")
    for name, spread in (("点差完全知晓(OCR标定)", 0.0), ("点差用行业均值 1.5 元/克", 1.5)):
        tot = np.abs(prem) + spread
        print(f"  {name:<24} 总 MAE = {tot.mean():6.3f} 元/克  "
              f"（相对金价 {tot.mean()/au_v.mean()*100:.3f}%）")

    print("\n" + "=" * 100)
    print("④ 关键对照：用国际金价 vs 用国内金价，差多少？")
    print("=" * 100)
    print(f"  {'方案':<34}{'MAE(元/克)':>12}{'MAE(%)':>10}{'可实时性':>10}")
    print(f"  {'A. 直接用上金所 Au99.99（基准）':<34}{0.0:>12.3f}{0.0:>10.3f}{'盘中可得':>10}")
    print(f"  {'B. 国际金价折算（本方案）':<34}{np.abs(prem).mean():>12.3f}"
          f"{np.abs(prem).mean()/au_v.mean()*100:>10.3f}{'24h可得':>10}")
    if gc:
        gd = sorted(set(au) & set(gc) & set(cny))
        gcv = np.array([gc[d] * cny[d] / TROY_OZ_G for d in gd])
        au2 = np.array([au[d] for d in gd])
        m = np.abs(au2 - gcv).mean()
        print(f"  {'C. 纽约金折算':<34}{m:>12.3f}{m/au2.mean()*100:>10.3f}{'夜盘可得':>10}")
    if cnh:
        cd = sorted(set(au) & set(xau) & set(cnh))
        ccv = np.array([xau[d] * cnh[d] / TROY_OZ_G for d in cd])
        au3 = np.array([au[d] for d in cd])
        m = np.abs(au3 - ccv).mean()
        print(f"  {'D. 离岸人民币 USDCNH 折算':<34}{m:>12.3f}{m/au3.mean()*100:>10.3f}{'24h可得':>10}")

    print("\n" + "=" * 100)
    print("⑤ 结论")
    print("=" * 100)
    mae = np.abs(prem).mean()
    print(f"  ✅ 国际金价折算可行：MAE {mae:.2f} 元/克（{mae/au_v.mean()*100:.3f}%）")
    print(f"  ⚠️ 误差来自「内外盘溢价」，其标准差 {prem.std():.2f} 元/克，且 ACF(1)={np.corrcoef(p[:-1],p[1:])[0,1]:+.2f}")
    print(f"     → 溢价有均值回归特征，可用「前一日/近N日溢价均值」做滚动修正")
    # 滚动溢价修正后的误差
    for win in (1, 5, 20):
        adj = np.array([prem[max(0, i - win):i].mean() if i >= win else 0.0
                        for i in range(len(prem))])
        resid = prem - adj
        print(f"     滚动 {win:2d} 日溢价修正后 MAE = {np.abs(resid[max(20,len(resid)//10):]).mean():.3f} 元/克")


if __name__ == "__main__":
    main()
