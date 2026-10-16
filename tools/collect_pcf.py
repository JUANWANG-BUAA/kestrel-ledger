"""上交所 ETF 申购赎回清单（PCF）每日采集器。

⚠️ **上交所不支持历史查询**（日期参数被忽略，只返回当日）→ 必须每日累积。

建议调度：每个交易日 09:00 与 16:00 各跑一次（PCF 在开盘前公布，含当日成分）。

用法：
    python3 tools/collect_pcf.py                       # 采集默认 ETF 清单
    python3 tools/collect_pcf.py --codes 510300,518880 # 指定代码
    python3 tools/collect_pcf.py --comments            # 采集后打印成分概览
"""
from __future__ import annotations

import argparse
import logging
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mkt import db, kline  # noqa: E402
from mkt.sources import sse_pcf  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("collect_pcf")

# 默认清单：覆盖你持仓相关的主题 + 宽基 + 黄金 + 债券
DEFAULT_CODES = [
    # 科技主题（用于主题主动基金的因子构建）
    "512480",  # 半导体ETF国联安
    "515070",  # 人工智能ETF华夏
    "588000",  # 科创50ETF华夏
    "512720",  # 计算机ETF
    "515880",  # 通信ETF
    "512330",  # 信息技术ETF
    "516010",  # 游戏ETF
    "512760",  # 芯片ETF国泰
    "515790",  # 光伏ETF
    "515030",  # 新能源车ETF
    # 宽基
    "510300",  # 沪深300ETF
    "510500",  # 中证500ETF
    "588080",  # 科创板50ETF
    "512100",  # 中证1000ETF
    "510050",  # 上证50ETF
    # 行业/主题
    "512690",  # 酒ETF
    "512010",  # 医药ETF
    "512660",  # 军工ETF
    "512880",  # 证券ETF
    "512800",  # 银行ETF
    # 固收/黄金
    "511380",  # 可转债ETF
    "511010",  # 国债ETF
    "511260",  # 十年国债ETF
    "518880",  # 黄金ETF华安
    "518800",  # 黄金ETF国泰
    # 跨境（如有）
    "513050",  # 中概互联网ETF
    "513130",  # 恒生科技ETF
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--codes", default=",".join(DEFAULT_CODES))
    ap.add_argument("--comments", action="store_true", help="打印成分概览")
    ap.add_argument("--fetch-prices", action="store_true", help="同时采集成分股日线")
    ap.add_argument("--db", default=str(db.DB_PATH))
    args = ap.parse_args()

    codes = [c.strip() for c in args.codes.split(",") if c.strip()]
    con = db.connect(Path(args.db))
    con.executescript("""
    CREATE TABLE IF NOT EXISTS pcf_component(
      fund_code TEXT, trading_day TEXT, instrument_id TEXT, name TEXT,
      quantity REAL, substitution_flag INTEGER,
      PRIMARY KEY(fund_code,trading_day,instrument_id));
    CREATE TABLE IF NOT EXISTS pcf_meta(
      fund_code TEXT, trading_day TEXT, pre_trading_day TEXT,
      nav REAL, nav_per_cu REAL, creation_unit REAL, cash_component REAL,
      n_component INTEGER, collected_at TEXT,
      PRIMARY KEY(fund_code,trading_day));
    """)
    # 兼容早期版本建的表（缺 collected_at 列）
    cols = {r[1] for r in con.execute("PRAGMA table_info(pcf_meta)")}
    if "collected_at" not in cols:
        con.execute("ALTER TABLE pcf_meta ADD COLUMN collected_at TEXT")
        con.commit()

    ok = fail = 0
    secids: set[str] = set()
    for code in codes:
        p = sse_pcf.fetch(code, use_cache=False)
        if not p:
            log.warning("  %-8s ✗ 无 PCF（可能非上交所或代码无效）", code)
            fail += 1
            continue
        already = con.execute(
            "SELECT 1 FROM pcf_meta WHERE fund_code=? AND trading_day=?",
            (p.fund_code, p.trading_day.isoformat()),
        ).fetchone()
        con.execute(
            "INSERT OR REPLACE INTO pcf_meta VALUES(?,?,?,?,?,?,?,?,datetime('now'))",
            (p.fund_code, p.trading_day.isoformat(),
             p.pre_trading_day.isoformat() if p.pre_trading_day else None,
             p.nav, p.nav_per_cu, p.creation_unit, p.cash_component, len(p.components)),
        )
        con.executemany(
            "INSERT OR REPLACE INTO pcf_component VALUES(?,?,?,?,?,?)",
            [(p.fund_code, p.trading_day.isoformat(), c.instrument_id, c.name,
              c.quantity, c.substitution_flag) for c in p.components],
        )
        con.commit()
        for c in p.components:
            sid = sse_pcf.to_secid(c.instrument_id)
            if sid:
                secids.add(sid)
        tag = "已存在" if already else "新增"
        log.info("  %-8s %-8s 成分=%3d NAV=%s 交易日=%s",
                 code, tag, len(p.components), p.nav, p.trading_day)
        ok += 1

    log.info("PCF 采集：成功 %d / 失败 %d，涉及成分股 %d 只", ok, fail, len(secids))

    if args.fetch_prices:
        have = {r[0] for r in con.execute("SELECT DISTINCT secid FROM stock_daily")}
        todo = sorted(secids - have)
        log.info("采集成分股日线：待采 %d 只", len(todo))
        got = 0
        for i, sid in enumerate(todo, 1):
            d, src = kline.fetch(sid)
            if d:
                db.upsert_klines(con, sid, d)
                got += 1
            if i % 50 == 0:
                log.info("    ... %d/%d", i, len(todo))
        log.info("成分股日线：新增 %d 只", got)

    if args.comments:
        td = con.execute("SELECT MAX(trading_day) FROM pcf_meta").fetchone()[0]
        log.info("最新交易日 %s 的清单概览：", td)
        for code, n in con.execute(
            "SELECT fund_code, COUNT(*) FROM pcf_component WHERE trading_day=? GROUP BY fund_code ORDER BY 2 DESC",
            (td,),
        ):
            top = con.execute(
                "SELECT name, quantity FROM pcf_component WHERE fund_code=? AND trading_day=?"
                " ORDER BY quantity DESC LIMIT 3", (code, td)).fetchall()
            log.info("  %-8s %3d 只  前3: %s", code, n,
                     " ".join(f"{nm}×{q:.0f}" for nm, q in top))

    log.info("库统计: %s", db.stats(con))
    con.close()


if __name__ == "__main__":
    main()
