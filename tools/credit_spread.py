"""信用利差代理：用债券 ETF 之间的收益差构造（无需中债接口）。

原理：
    信用利差 ≈ 信用债收益 − 同久期国债收益
    用可交易的债券 ETF 直接相减，得到**市场化的信用利差变动**

标的（均已实测可得）：
    国债     : 511010(5年) / 511260(十年) / 511020(活跃)
    政金债   : 511520 / 159650
    信用债   : 511030(公司债) / 511220(城投债) / 511060(地方债)
    同业存单 : 511360(短融)

用途：
    1. 作为债券基金估值的新因子（区分利率风险 vs 信用风险）
    2. 展示"信用利差走阔/收窄"这一宏观信号

⚠️ 中债官方收益率曲线（`yield.chinabond.com.cn/cbweb-mn/yc/*`）已探明
   曲线树接口 `queryTree` 可用（180 条，含中债国债/企业债曲线 ID），
   但**取数接口的参数格式未破解**（`bzqx` 返回空）→ 列为待办。
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
log = logging.getLogger("credit")

GROUPS = {
    "国债": ["1.511010", "1.511260", "1.511020"],
    "政金债": ["1.511520", "0.159650"],
    "信用债": ["1.511030", "1.511220", "1.511060"],
    "同业存单": ["1.511360"],
}


def series(con, sid: str) -> dict[str, float]:
    """⚠️ 债券 ETF 多按月/季分红，必须用复权修正后的收益（否则利差被假跳空污染）。"""
    from adjust import raw_returns
    return raw_returns(con, sid, adjust=True)


def build(con) -> dict[str, dict[str, float]]:
    """各分组的等权平均收益序列。"""
    out = {}
    for name, sids in GROUPS.items():
        parts = [series(con, s) for s in sids]
        parts = [p for p in parts if p]
        if not parts:
            continue
        dates = None
        for p in parts:
            dates = set(p) if dates is None else (dates & set(p))
        if not dates or len(dates) < 30:
            continue
        out[name] = {d: float(np.mean([p[d] for p in parts])) for d in sorted(dates)}
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=20)
    args = ap.parse_args()
    con = db.connect()
    g = build(con)
    if "国债" not in g or "信用债" not in g:
        log.error("缺少国债或信用债 ETF 数据"); return

    common = sorted(set(g["国债"]) & set(g["信用债"]))
    log.info("=" * 74)
    log.info("信用利差代理（信用债ETF收益 − 国债ETF收益，单位 bp）")
    log.info("=" * 74)
    log.info("  样本 %d 日（%s ~ %s）", len(common), common[0], common[-1])

    spread = {d: (g["信用债"][d] - g["国债"][d]) * 100 for d in common}   # % → bp
    arr = np.array(list(spread.values()))
    log.info("")
    log.info("  全样本:  均值 %+6.3f bp  标准差 %5.2f bp", arr.mean(), arr.std(ddof=1))
    log.info("  近 %d 日: 均值 %+6.3f bp  标准差 %5.2f bp", args.days,
             arr[-args.days:].mean(), arr[-args.days:].std(ddof=1))

    # 利差水平（20 日滚动均值）与其变动
    # ⚠️ 早期版本用「累积和」，会随天数无限增长（−3382bp 无意义）。
    #    正确做法：看**利差水平**相对 N 日前的变动。
    W = args.days
    ks = sorted(spread)
    level = {}
    for i, d in enumerate(ks):
        if i + 1 < W:
            continue
        level[d] = float(np.mean([spread[x] for x in ks[i + 1 - W:i + 1]]))
    log.info("")
    log.info("  利差水平（%d 日滚动均值）最近 10 日：", W)
    prev = None
    for d in ks[-10:]:
        if d not in level:
            continue
        lv = level[d]
        chg = f"{lv - level[ks[ks.index(d)-W]]:+6.2f}bp(20日)" if ks.index(d) >= W else ""
        bar = "█" * min(36, int(abs(lv) * 2))
        log.info("    %s  水平 %+6.2f bp   %s %s", d, lv, bar, chg)
    if len(ks) > 2 * W:
        now = level[ks[-1]]
        ago = level[ks[-1 - W]]
        log.info("")
        log.info("  ➜ %d 日变动: %+6.2f bp  →  %s", W, now - ago,
                 "信用利差走阔（风险偏好下降）" if now > ago else "信用利差收窄（风险偏好回升）")

    # 分组对比
    log.info("")
    log.info("  分组年化波动率（近 120 日）：")
    for name, s in g.items():
        if len(s) < 120:
            continue
        # ⚠️ series() 已返回百分比，年化波动 = σ% × √250（不要再 ×100）
        v = np.array([s[d] for d in sorted(s)[-120:]])
        log.info("    %-8s %6.3f%%", name, v.std(ddof=1) * np.sqrt(250))

    # 股债相关性
    hs = db.load_klines(con, "1.000300")
    it = sorted(hs.items())
    if len(it) > 120:
        eq = {it[i][0]: (it[i][1] / it[i - 1][1] - 1) * 100 for i in range(1, len(it))}
        c = sorted(set(g["国债"]) & set(eq))[-250:]
        a = np.array([g["国债"][d] for d in c])
        b = np.array([eq[d] for d in c])
        if a.std() > 0 and b.std() > 0:
            log.info("")
            log.info("  股债相关性（近 %d 日）：%.3f  %s", len(c), np.corrcoef(a, b)[0, 1],
                     "（负相关 → 避险属性）" if np.corrcoef(a, b)[0, 1] < 0 else "")
    con.close()


if __name__ == "__main__":
    main()
