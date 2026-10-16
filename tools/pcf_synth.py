"""PCF 合成主题指数：计算并持久化。

为什么要单独存：
  · 原始 pcf_component：27 ETF × 80 成分 × 250 日 ≈ **22 MB/年**
  · 合成指数：27 ETF × 250 日 ≈ **270 KB/年**（差 80 倍）
  · 且合成指数是**重算成本高**的派生数据（需成分股全量行情）
→ 原始 PCF 只留 90 天，合成指数**永久保留**

用法：
    python3 tools/pcf_synth.py            # 计算当日全部
    python3 tools/pcf_synth.py --prune    # 同时清理 90 天前的原始 PCF
"""
from __future__ import annotations
import argparse, logging, sys
from datetime import date, timedelta
from pathlib import Path
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt import db  # noqa: E402
from mkt.sources import sse_pcf  # noqa: E402

log = logging.getLogger("pcf_synth")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")

DDL = """
CREATE TABLE IF NOT EXISTS pcf_synthetic_index (
  fund_code TEXT, trading_day TEXT,
  ret REAL,              -- 合成指数当日收益 %
  n_used INTEGER,        -- 参与计算的成分股数
  n_total INTEGER,       -- PCF 成分股总数
  weight_sum REAL,       -- 权重合计（%，应≈100）
  cash_ratio REAL,       -- 现金替代占比 %
  PRIMARY KEY(fund_code, trading_day));
CREATE TABLE IF NOT EXISTS pcf_weight (
  fund_code TEXT, trading_day TEXT, instrument_id TEXT, weight REAL,
  PRIMARY KEY(fund_code, trading_day, instrument_id));
"""


def compute(con, fund_code: str, trading_day: str) -> dict | None:
    comps = con.execute(
        "SELECT instrument_id, quantity FROM pcf_component WHERE fund_code=? AND trading_day=?",
        (fund_code, trading_day)).fetchall()
    if not comps:
        return None
    meta = con.execute(
        "SELECT nav, cash_component FROM pcf_meta WHERE fund_code=? AND trading_day=?",
        (fund_code, trading_day)).fetchone()
    cash = abs(meta[1]) if meta else 0.0

    vals: dict[str, float] = {}
    rets: dict[str, float] = {}
    for cid, q in comps:
        sid = sse_pcf.to_secid(cid)
        if not sid:
            continue
        kl = db.load_klines(con, sid)
        it = sorted(kl.items())
        if len(it) < 2:
            continue
        # 找 trading_day 及其前一日
        idx = next((i for i, (d, _) in enumerate(it) if d == trading_day), None)
        if idx is None or idx == 0:
            continue
        px_prev, px_now = it[idx - 1][1], it[idx][1]
        if px_prev <= 0:
            continue
        vals[cid] = q * px_now
        rets[cid] = (px_now / px_prev - 1) * 100.0

    total = sum(vals.values()) + cash
    if total <= 0 or not rets:
        return None
    w = {c: v / total for c, v in vals.items()}
    ret = sum(w[c] * rets[c] for c in rets)
    return {"ret": ret, "n_used": len(rets), "n_total": len(comps),
            "weight_sum": sum(w.values()) * 100, "cash_ratio": cash / total * 100,
            "weights": w}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--day", default="")          # 默认最新交易日
    ap.add_argument("--all-days", action="store_true", help="回填所有已有 PCF 日期")
    ap.add_argument("--prune", action="store_true", help="清理 90 天前的原始 PCF")
    ap.add_argument("--keep-days", type=int, default=90)
    ap.add_argument("--db", default=str(db.DB_PATH))
    args = ap.parse_args()

    con = db.connect(Path(args.db))
    con.executescript(DDL)
    con.commit()

    days = ([r[0] for r in con.execute("SELECT DISTINCT trading_day FROM pcf_meta ORDER BY 1")]
            if args.all_days else
            [args.day or con.execute("SELECT MAX(trading_day) FROM pcf_meta").fetchone()[0]])
    codes = [r[0] for r in con.execute("SELECT DISTINCT fund_code FROM pcf_meta")]

    ok = 0
    for day in days:
        if not day:
            continue
        for code in codes:
            r = compute(con, code, day)
            if not r:
                continue
            con.execute("INSERT OR REPLACE INTO pcf_synthetic_index VALUES(?,?,?,?,?,?,?)",
                        (code, day, r["ret"], r["n_used"], r["n_total"],
                         r["weight_sum"], r["cash_ratio"]))
            con.executemany("INSERT OR REPLACE INTO pcf_weight VALUES(?,?,?,?)",
                            [(code, day, c, w) for c, w in r["weights"].items()])
            ok += 1
        con.commit()
        log.info("  %s 完成", day)

    log.info("合成指数：写入 %d 条", ok)

    if args.prune:
        cut = (date.today() - timedelta(days=args.keep_days)).isoformat()
        n1 = con.execute("DELETE FROM pcf_component WHERE trading_day < ?", (cut,)).rowcount
        n2 = con.execute("DELETE FROM pcf_meta WHERE trading_day < ?", (cut,)).rowcount
        n3 = con.execute("DELETE FROM pcf_weight WHERE trading_day < ?", (cut,)).rowcount
        con.commit()
        log.info("清理 %s 之前：pcf_component %d 行 / pcf_meta %d 行 / pcf_weight %d 行",
                 cut, n1, n2, n3)

    # 汇总
    row = con.execute("SELECT COUNT(*), MIN(trading_day), MAX(trading_day) FROM pcf_synthetic_index").fetchone()
    log.info("pcf_synthetic_index: %d 条  %s ~ %s", row[0], row[1], row[2])
    # 粗略容量估算（按行数与列宽）
    EST = {"pcf_component": 45, "pcf_meta": 120, "pcf_weight": 40, "pcf_synthetic_index": 50}
    for t in ("pcf_component", "pcf_meta", "pcf_weight", "pcf_synthetic_index"):
        n = con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
        log.info("  %-22s %8d 行   约 %6.1f KB", t, n, n * EST.get(t, 50) / 1024)
    con.close()


if __name__ == "__main__":
    main()
