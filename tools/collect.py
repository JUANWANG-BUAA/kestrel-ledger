"""采集脚本：基金净值 / 资产配置 / 十大重仓 / 持仓个股日线 / 基准指数。

用法：
    python3 tools/collect.py --funds 110011,005827 --years 2024,2025,2026 [--bench]
"""
from __future__ import annotations

import argparse
import logging
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mkt import db, kline  # noqa: E402
from mkt.sources import eastmoney as em  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s | %(message)s")
log = logging.getLogger("collect")

# 默认研究样本：覆盖主动/指数/行业/QDII/债券/货币
DEFAULT_FUNDS = [
    "110011",  # 易方达优质精选混合(QDII)
    "005827",  # 易方达蓝筹精选混合
    "161725",  # 招商中证白酒指数(LOF)A
    "110022",  # 易方达消费行业股票
    "003095",  # 中欧医疗健康混合A
    "002190",  # 农银新能源主题
    "000961",  # 天弘沪深300ETF联接A
    "001593",  # 天弘创业板ETF联接A
    "519674",  # 银河创新成长混合A
    "007339",  # 易方达恒生国企ETF联接(QDII)A
    "003358",  # 易方达中债7-10年国开债A
    "000198",  # 天弘余额宝货币
]

BENCHMARKS = {
    "1.000300": "沪深300",
    "0.399006": "创业板指",
    "1.000905": "中证500",
    "1.000001": "上证指数",
    "100.HSI": "恒生指数",
}


def collect_fund(con, code: str, years: list[int], kline_limit: int) -> dict:
    res = {"code": code, "nav": 0, "holdings": 0, "alloc": 0, "secids": 0, "err": []}

    # 1) 净值
    try:
        navs = em.fund_nav_history(code)
        res["nav"] = db.upsert_nav(con, code, navs)
        db.log(con, "nav", code, True, f"{res['nav']} 条")
    except Exception as e:  # noqa: BLE001
        res["err"].append(f"nav:{e}")
        db.log(con, "nav", code, False, str(e))

    # 2) 画像（名称 + 股票占比）
    try:
        prof = em.fund_profile(code)
        db.upsert_meta(con, code, prof.name)
        res["alloc"] = db.upsert_alloc(con, code, prof.stock_ratio_by_period)
        db.log(con, "profile", code, True, prof.name)
    except Exception as e:  # noqa: BLE001
        res["err"].append(f"profile:{e}")
        db.log(con, "profile", code, False, str(e))

    # 3) 重仓（按年）
    secids: set[str] = set()
    for y in years:
        try:
            rows = em.fund_holdings(code, y, topline=10)
            n = db.upsert_holdings(con, code, rows)
            res["holdings"] += n
            secids.update(r.secid for r in rows)
            db.log(con, "holdings", f"{code}:{y}", True, f"{n} 行")
        except Exception as e:  # noqa: BLE001
            res["err"].append(f"holdings{y}:{e}")
            db.log(con, "holdings", f"{code}:{y}", False, str(e))

    # 4) 持仓个股日线（多源：腾讯主 + 新浪备；已入库的跳过）
    have = {
        r[0]
        for r in con.execute(
            "SELECT DISTINCT secid FROM stock_daily WHERE secid IN (%s)"
            % (",".join("?" * len(secids)) if secids else "''"),
            tuple(sorted(secids)),
        ).fetchall()
    } if secids else set()
    ok, skip, failed = 0, len(have), 0
    src_count: dict[str, int] = {}
    for sid in sorted(secids - have):
        closes, src = kline.fetch(sid)
        if closes:
            db.upsert_klines(con, sid, closes)
            src_count[src] = src_count.get(src, 0) + 1
            ok += 1
        else:
            failed += 1
            db.log(con, "kline", sid, False, "所有源均失败")
    res["secids"] = ok
    res["skip"] = skip
    res["failed"] = failed
    res["src"] = src_count
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--funds", default=",".join(DEFAULT_FUNDS))
    ap.add_argument("--years", default="")
    ap.add_argument("--kline-limit", type=int, default=900, help="个股日线取多少根（900≈3.6年）")
    ap.add_argument("--bench", action="store_true", help="同时采集基准指数")
    ap.add_argument("--db", default=str(db.DB_PATH))
    args = ap.parse_args()

    today = date.today()
    years = (
        [int(x) for x in args.years.split(",") if x.strip()]
        if args.years
        else list(range(today.year - 3, today.year + 1))
    )

    con = db.connect(Path(args.db))
    funds = [c.strip() for c in args.funds.split(",") if c.strip()]

    log.info("开始采集 %d 只基金, 年份=%s", len(funds), years)
    for i, code in enumerate(funds, 1):
        r = collect_fund(con, code, years, args.kline_limit)
        log.info(
            "[%d/%d] %s 净值=%d 重仓=%d 配置=%d 个股新增=%d 跳过=%d 失败=%d 源=%s %s",
            i, len(funds), code, r["nav"], r["holdings"], r["alloc"],
            r["secids"], r.get("skip", 0), r.get("failed", 0), r.get("src", {}),
            f"错误={r['err']}" if r["err"] else "",
        )

    if args.bench:
        log.info("采集基准指数日线")
        for sid, name in BENCHMARKS.items():
            closes, src = kline.fetch(sid)
            if closes:
                db.upsert_klines(con, sid, closes)
                log.info("  %-10s %-10s %d 条 (源=%s)", sid, name, len(closes), src)
            else:
                log.warning("  %-10s %-10s 失败", sid, name)

    log.info("完成。库统计: %s", db.stats(con))
    con.close()


if __name__ == "__main__":
    main()
