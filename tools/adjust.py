"""复权修正：用不复权价 + 分红数据，计算**真实日收益率**。

为什么需要：
  腾讯 fqkline 的 `qfq` 参数实测未生效（qfq ≡ raw）→ 全部分析都在用不复权价
  → 除权日产生**假跳空**（如工商银行 2023-07-17 的 −6.16%，实际是每股派 0.3035 元）
  → 污染因子回归（A 股分红集中在 6~7 月）

修正公式（标准做法）：
  真实收益 r_t = (P_t + div_t) / P_{t-1} − 1
  若含送转股：r_t = (P_t × (1 + bonus + it) + div_t) / P_{t-1} − 1

归档原则：
  **存不复权价（append-only，永不改写） + 分红表** → 任意时点可复现
  禁止存前复权价（每次分红都会改写全部历史）
"""
from __future__ import annotations
import logging
import sqlite3
from dataclasses import dataclass
from datetime import date

from mkt import db

log = logging.getLogger("adjust")

DDL = """
CREATE TABLE IF NOT EXISTS stock_dividend (
  secid TEXT NOT NULL, ex_date TEXT NOT NULL,
  cash_per_share REAL DEFAULT 0,
  bonus_ratio REAL DEFAULT 0,
  it_ratio REAL DEFAULT 0,
  profile TEXT,
  PRIMARY KEY(secid, ex_date));
"""


def ensure(con: sqlite3.Connection) -> None:
    con.executescript(DDL)
    con.commit()


@dataclass(slots=True)
class Div:
    cash: float = 0.0
    bonus: float = 0.0
    it: float = 0.0


def load_dividends(con: sqlite3.Connection, secid: str) -> dict[str, Div]:
    """{ex_date: Div}"""
    out: dict[str, Div] = {}
    for ex, c, b, i in con.execute(
        "SELECT ex_date, cash_per_share, bonus_ratio, it_ratio FROM stock_dividend WHERE secid=?",
        (secid,),
    ):
        out[ex] = Div(cash=c or 0.0, bonus=b or 0.0, it=i or 0.0)
    return out


def raw_returns(con: sqlite3.Connection, secid: str, adjust: bool = True) -> dict[str, float]:
    """真实日收益率（%，已做除权修正）。adjust=False 时退化为原始不复权收益。"""
    kl = db.load_klines(con, secid)
    it = sorted(kl.items())
    if len(it) < 2:
        return {}
    divs = load_dividends(con, secid) if adjust else {}
    out: dict[str, float] = {}
    for i in range(1, len(it)):
        (d0, p0), (d1, p1) = it[i - 1], it[i]
        if p0 <= 0:
            continue
        d = divs.get(d1)
        if d is not None:
            # 除权日：加回现金分红，并调整送转股的股数
            eff = p1 * (1.0 + d.bonus + d.it) + d.cash
        else:
            eff = p1
        out[d1] = (eff / p0 - 1.0) * 100.0
    return out


def main() -> None:
    """采集成分股/持仓股的分红数据 + 报告修正效果。"""
    import argparse
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from mkt.sources import eastmoney as em

    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="最多采集多少只（0=全部）")
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()

    con = db.connect()
    ensure(con)

    secids = [r[0] for r in con.execute(
        "SELECT DISTINCT secid FROM stock_daily WHERE secid LIKE '1.%' OR secid LIKE '0.%'")]
    if not secids:
        log.warning("无 A 股标的"); return
    todo = [s for s in secids if not con.execute(
        "SELECT 1 FROM stock_dividend WHERE secid=? LIMIT 1", (s,)).fetchone()]
    if args.limit:
        todo = todo[: args.limit]
    log.info("A 股标的 %d 只，待采分红 %d 只", len(secids), len(todo))

    from concurrent.futures import ThreadPoolExecutor, as_completed

    def fetch_one(sid: str):
        code = sid.split(".")[1]
        try:
            return sid, em.stock_dividends(code, page_size=40)
        except Exception:  # noqa: BLE001
            return sid, []

    ok = done = 0
    with ThreadPoolExecutor(max_workers=6) as ex:
        futs = [ex.submit(fetch_one, s) for s in todo]
        for f in as_completed(futs):
            sid, rows = f.result()
            done += 1
            if rows:
                con2 = db.connect()
                ensure(con2)
                con2.executemany(
                    "INSERT OR REPLACE INTO stock_dividend VALUES(?,?,?,?,?,?)",
                    [(sid, r["ex_date"].isoformat(), r["cash_per_share"],
                      r["bonus_ratio"], r["it_ratio"], r["profile"]) for r in rows])
                con2.commit(); con2.close()
                ok += 1
            if done % 200 == 0:
                log.info("  %d/%d  命中 %d", done, len(todo), ok)
    log.info("分红采集完成：%d 只命中 / %d 只待采", ok, len(todo))

    if args.report:
        n = con.execute("SELECT COUNT(*) FROM stock_dividend").fetchone()[0]
        log.info("stock_dividend 共 %d 行", n)
        # 展示修正效果
        import numpy as np
        log.info("修正效果抽样（除权日原始 vs 修正后）：")
        for sid, nm in (("1.601398", "工商银行"), ("1.600519", "贵州茅台"),
                        ("0.000858", "五粮液")):
            raw = raw_returns(con, sid, adjust=False)
            adj = raw_returns(con, sid, adjust=True)
            diffs = [(d, raw[d], adj[d]) for d in raw if abs(raw[d] - adj[d]) > 0.3]
            log.info("  %s: %d 个除权日被修正", nm, len(diffs))
            for d, r0, r1 in sorted(diffs)[-3:]:
                log.info("    %s  原始 %+.2f%% → 修正 %+.2f%%  (差 %.2f pp)", d, r0, r1, r1 - r0)


if __name__ == "__main__":
    import logging as _l
    _l.basicConfig(level=_l.INFO, format="%(asctime)s %(levelname)s | %(message)s")
    main()
