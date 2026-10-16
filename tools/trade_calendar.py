"""交易日历：从实际交易数据反推（比查公告更可靠）。

原理：上证指数（1.000001）有日线数据的日期，就是 A 股交易日。
     港股（100.HSI）、美股（100.NDX）同理。

用法：
    python3 tools/calendar.py --build        # 从已有行情重建
    python3 tools/calendar.py --check 2026-10-15
    python3 tools/calendar.py --next 5
"""
from __future__ import annotations
import argparse, logging, sys
from datetime import date, timedelta
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt import db  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("calendar")

DDL = """
CREATE TABLE IF NOT EXISTS trading_calendar (
  market TEXT, cal_date TEXT, is_open INTEGER, source TEXT,
  PRIMARY KEY(market, cal_date));
CREATE INDEX IF NOT EXISTS idx_cal ON trading_calendar(market, is_open, cal_date);
"""

MARKET_PROXY = {
    "CN": "1.000001",     # 上证指数
    "HK": "100.HSI",      # 恒生指数
    "US": "100.NDX",      # 纳斯达克
}


def build(con) -> None:
    con.executescript(DDL)
    for mkt, secid in MARKET_PROXY.items():
        kl = db.load_klines(con, secid)
        if not kl:
            log.warning("  %s 代理指数 %s 无数据", mkt, secid)
            continue
        days = sorted(kl)
        con.executemany("INSERT OR REPLACE INTO trading_calendar VALUES(?,?,?,?)",
                        [(mkt, d, 1, f"derived:{secid}") for d in days])
        # 标记区间内的非交易日（工作日但无行情 = 假日）
        closed = []
        d0 = date.fromisoformat(days[0])
        d1 = date.fromisoformat(days[-1])
        dset = set(days)
        cur = d0
        while cur <= d1:
            if cur.weekday() < 5 and cur.isoformat() not in dset:
                closed.append((mkt, cur.isoformat(), 0, "derived:holiday"))
            cur += timedelta(days=1)
        con.executemany("INSERT OR REPLACE INTO trading_calendar VALUES(?,?,?,?)", closed)
        con.commit()
        log.info("  %-3s %4d 交易日 + %3d 假日  （%s ~ %s）", mkt, len(days), len(closed), days[0], days[-1])


def is_open(con, market: str, d: date) -> bool | None:
    r = con.execute("SELECT is_open FROM trading_calendar WHERE market=? AND cal_date=?",
                    (market, d.isoformat())).fetchone()
    if r is not None:
        return bool(r[0])
    # 超出已知范围：降级为「周一至周五」
    return d.weekday() < 5


def next_open(con, market: str, d: date, n: int = 1) -> list[str]:
    out, cur = [], d
    while len(out) < n and (cur - d).days < 400:
        cur += timedelta(days=1)
        if is_open(con, market, cur):
            out.append(cur.isoformat())
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--build", action="store_true")
    ap.add_argument("--check", default="")
    ap.add_argument("--next", type=int, default=0)
    args = ap.parse_args()
    con = db.connect()
    con.executescript(DDL)
    if args.build or not any([args.check, args.next]):
        log.info("▶ 重建交易日历")
        build(con)
    if args.check:
        d = date.fromisoformat(args.check)
        for mkt in MARKET_PROXY:
            log.info("  %s %s → %s", mkt, args.check,
                     "开市" if is_open(con, mkt, d) else "休市")
    if args.next:
        for mkt in MARKET_PROXY:
            log.info("  %s 未来 %d 个交易日: %s", mkt, args.next,
                     ", ".join(next_open(con, mkt, date.today(), args.next)))
    n = con.execute("SELECT COUNT(*) FROM trading_calendar").fetchone()[0]
    log.info("trading_calendar 共 %d 行", n)
    con.close()


if __name__ == "__main__":
    main()
