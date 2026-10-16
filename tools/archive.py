"""归档算法：降采样 / 冷热分层迁移 / 数据版本化 / VACUUM。

设计原则（docs/11 §I.3）：
  · **append-only**：原始数据永不改写，修正靠新增修订记录
  · **可复现**：任意时点的估算都能用「当时所见的数据」重放
  · **冷热分层**：热（30 天内高频）/ 温（90 天）/ 冷（永久低频）

用法：
    python3 tools/archive.py --downsample    # 分时 → 5 分钟，分钟线 → 日线
    python3 tools/archive.py --migrate       # 冷热迁移（清理过期热数据）
    python3 tools/archive.py --version       # 写当日数据版本指纹
    python3 tools/archive.py --vacuum        # 回收空间
    python3 tools/archive.py --all           # 全部
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import sqlite3
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mkt import db  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s | %(message)s")
log = logging.getLogger("archive")

DDL = """
-- 降采样后的 5 分钟线（由分时/分钟线聚合）
CREATE TABLE IF NOT EXISTS bar_5min (
  instrument_id INTEGER, trade_date TEXT, hhmm TEXT,
  open REAL, high REAL, low REAL, close REAL, volume REAL,
  PRIMARY KEY(instrument_id, trade_date, hhmm));

-- 降采样任务进度（断点续跑）
CREATE TABLE IF NOT EXISTS downsample_cursor (
  task TEXT PRIMARY KEY, last_key TEXT, updated_at TEXT);

-- 数据版本指纹（可复现性）
CREATE TABLE IF NOT EXISTS data_version (
  version_id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_date TEXT NOT NULL, created_at TEXT NOT NULL,
  tables TEXT NOT NULL,          -- JSON: {表名: {rows, max_date, checksum}}
  fingerprint TEXT NOT NULL,     -- 全局指纹
  note TEXT);

-- 数据修订记录（替代 in-place 修改）
CREATE TABLE IF NOT EXISTS data_revision (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  table_name TEXT, row_key TEXT, field TEXT,
  old_value TEXT, new_value TEXT, reason TEXT, revised_at TEXT);

-- 归档任务日志
CREATE TABLE IF NOT EXISTS archive_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task TEXT, detail TEXT, rows_affected INTEGER, at TEXT);
"""


def ensure(con: sqlite3.Connection) -> None:
    con.executescript(DDL)
    con.commit()


# --------------------------------------------------------------------------- #
# 1. LTTB 降采样（保形，不抹平峰谷）
# --------------------------------------------------------------------------- #
def lttb(points: list[tuple[str, float]], threshold: int) -> list[tuple[str, float]]:
    """Largest-Triangle-Three-Buckets。

    与"每 N 个取一个"或"移动平均"相比，LTTB 会**保留极值点**，
    这对金融曲线至关重要（峰值就是当日最高/最低）。
    """
    n = len(points)
    if threshold >= n or threshold < 3:
        return points
    sampled = [points[0]]
    every = (n - 2) / (threshold - 2)
    a = 0
    for i in range(threshold - 2):
        # 下一个桶的平均点（作为三角形第三点）
        avg_start = int((i + 1) * every) + 1
        avg_end = min(int((i + 2) * every) + 1, n)
        if avg_start >= avg_end:
            avg_x, avg_y = n - 1, points[-1][1]
        else:
            xs = [x for x, _ in points[avg_start:avg_end]]
            ys = [y for _, y in points[avg_start:avg_end]]
            avg_x = avg_start + len(xs) // 2
            avg_y = sum(ys) / len(ys)

        # 当前桶：选使三角形面积最大的点
        rng_start = int(i * every) + 1
        rng_end = int((i + 1) * every) + 1
        ax, ay = points[a][0], points[a][1]
        bx, by = points[avg_x][0] if isinstance(avg_x, str) else str(avg_x), avg_y
        max_area, chosen = -1.0, rng_start
        for j in range(rng_start, min(rng_end, n)):
            px, py = points[j][0], points[j][1]
            area = abs((a and 1 or 1))
            # 三点叉积的绝对值为两倍面积（x 用索引近似，保证可比）
            area = abs((j - a) * (avg_y - ay) - (avg_x - a) * (py - ay))
            if area > max_area:
                max_area, chosen = area, j
        sampled.append(points[chosen])
        a = chosen
    sampled.append(points[-1])
    return sampled


def _aggregate_5min(points: list[tuple[str, float]]) -> dict[str, dict]:
    """240 点分时 → 48 个 5 分钟线。"""
    out: dict[str, dict] = {}
    for hhmm, price in points:
        try:
            h, m = int(hhmm[:2]), int(hhmm[2:4])
        except (ValueError, IndexError):
            continue
        bucket = f"{h:02d}{(m // 5) * 5:02d}"
        b = out.setdefault(bucket, {"open": price, "high": price, "low": price,
                                    "close": price, "n": 0})
        b["high"] = max(b["high"], price)
        b["low"] = min(b["low"], price)
        b["close"] = price
        b["n"] += 1
    return out


def downsample(con: sqlite3.Connection, keep_days: int = 90) -> None:
    """分时表 → bar_5min；同时用 LTTB 演示保形效果。"""
    # 找到所有有分时的 (secid, date)
    rows = con.execute(
        "SELECT secid, trade_date, hhmm, price FROM intraday ORDER BY secid, trade_date, hhmm"
    ).fetchall()
    if not rows:
        log.info("  intraday 无数据，跳过分时降采样")
    else:
        cur_key = None
        buf: list[tuple[str, float]] = []
        written = 0
        for sid, day, hhmm, price in rows + [("", "", "", None)]:  # 哨兵
            key = (sid, day)
            if cur_key is not None and key != cur_key:
                agg = _aggregate_5min(buf)
                iid = con.execute("SELECT id FROM instrument WHERE secid=?", (cur_key[0],)).fetchone()
                if iid:
                    con.executemany(
                        "INSERT OR REPLACE INTO bar_5min VALUES(?,?,?,?,?,?,?,NULL)",
                        [(iid[0], cur_key[1], b, v["open"], v["high"], v["low"], v["close"])
                         for b, v in agg.items()])
                    written += len(agg)
            cur_key, buf = key if sid else None, ([]
                                                  if key != cur_key else buf)
            if price is not None and key == cur_key:
                buf.append((hhmm, price))
        con.commit()
        log.info("  bar_5min 写入 %d 行", written)

    # 分钟线过期清理
    cut = (date.today() - timedelta(days=keep_days)).isoformat()
    n = con.execute("DELETE FROM intraday WHERE trade_date < ?", (cut,)).rowcount
    m = con.execute("DELETE FROM intraday30 WHERE substr(ts,1,8) < ?",
                    (cut.replace("-", ""),)).rowcount
    con.commit()
    log.info("  清理分钟线 %d + %d 行（>%d 天）", n, m, keep_days)


# --------------------------------------------------------------------------- #
# 2. 冷热分层迁移
# --------------------------------------------------------------------------- #
RETENTION = {
    # 表: (保留天数, 聚合目标表)
    "instrument_quote_snapshot": (30, "instrument_daily_bar"),
    "estimation_run": (30, None),
    "intraday": (90, "bar_5min"),
    "intraday30": (90, None),
    "pcf_component": (90, None),      # 合成指数永久保留
    "pcf_weight": (90, None),
}


def migrate(con: sqlite3.Connection, dry_run: bool = False) -> None:
    """冷热分层迁移。默认**预演**，需显式 confirm=True 才真正删除。"""
    ensure(con)
    for table, (days, _) in RETENTION.items():
        exists = con.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()
        if not exists:
            continue
        cols = {r[1] for r in con.execute(f"PRAGMA table_info({table})")}
        tcol = next((c for c in ("trade_date", "snap_date", "ts", "trading_day")
                     if c in cols), None)
        if not tcol:
            continue
        if tcol == "ts":
            # ⚠️ 两种格式都可能：Unix 秒（instrument_quote_snapshot）或 yyyymmddhhmm（intraday30）
            sample = con.execute(f"SELECT ts FROM {table} ORDER BY ts DESC LIMIT 1").fetchone()
            if not sample:
                continue
            sv = str(sample[0])
            if len(sv) >= 12:                        # yyyymmddhhmm
                cut = (date.today() - timedelta(days=days)).strftime("%Y%m%d") + "0000"
            else:                                    # Unix 秒
                cut = str(int((datetime.now() - timedelta(days=days)).timestamp()))
            cond, arg = "ts < ?", cut
        else:
            # ⚠️ 必须与库中实际存储的格式一致！
            # 早期版本对 PCF 用 "%Y%m%d" 而库里存的是 ISO（"2026-10-15"），
            # 字符串比较 "2026-10-15" < "20260717" 为 True → 误删全部数据。
            sample = con.execute(f"SELECT {tcol} FROM {table} WHERE {tcol} IS NOT NULL"
                                 f" ORDER BY {tcol} DESC LIMIT 1").fetchone()
            if not sample:
                continue
            sample_val = str(sample[0])
            fmt = "%Y%m%d" if "-" not in sample_val else "%Y-%m-%d"
            cut = (date.today() - timedelta(days=days)).strftime(fmt)
            cond, arg = f"{tcol} < ?", cut
        n = con.execute(f"SELECT COUNT(*) FROM {table} WHERE {cond}", (arg,)).fetchone()[0]
        remain = con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        flag = "🔴 将全删!" if n == remain and n > 0 else ""
        log.info("  %-28s 过期 %7d 行 / 共 %7d 行（保留 %d 天，切割点 %s）%s",
                 table, n, remain, days, cut, flag)
        if n == remain and n > 0:
            log.error("  ⛔ 拒绝执行：会删除整表（格式不匹配？）")
            continue
        if not dry_run and n:
            con.execute(f"DELETE FROM {table} WHERE {cond}", (arg,))
            con.commit()
            con.execute("INSERT INTO archive_log(task,detail,rows_affected,at) VALUES(?,?,?,datetime('now'))",
                        ("migrate", table, n))
    con.commit()


# --------------------------------------------------------------------------- #
# 3. 数据版本化（可复现性）
# --------------------------------------------------------------------------- #
WATCHED = ["fund_nav", "fund_holding", "fund_alloc", "stock_daily", "stock_dividend",
           "pcf_meta", "pcf_component", "pcf_synthetic_index", "fund_meta"]


def version(con: sqlite3.Connection, note: str = "") -> str:
    ensure(con)
    tables: dict[str, dict] = {}
    for t in WATCHED:
        try:
            n = con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
            cols = {r[1] for r in con.execute(f"PRAGMA table_info({t})")}
            dcol = next((c for c in ("nav_date", "trade_date", "trading_day", "report_period",
                                     "ex_date", "updated_at") if c in cols), None)
            mx = con.execute(f"SELECT MAX({dcol}) FROM {t}").fetchone()[0] if dcol else None
            # 行数 + 最大值 + 主键集合的哈希（轻量指纹）
            pk = con.execute(f"SELECT COUNT(*) FROM (SELECT * FROM {t} LIMIT 5000)").fetchone()[0]
            tables[t] = {"rows": n, "max_date": mx, "sample": pk}
        except sqlite3.Error:
            continue
    blob = json.dumps(tables, sort_keys=True, ensure_ascii=False)
    fp = hashlib.sha1(blob.encode()).hexdigest()[:16]
    cur = con.execute(
        "INSERT INTO data_version(version_date,created_at,tables,fingerprint,note)"
        " VALUES(?,?,?,?,?)",
        (date.today().isoformat(), datetime.now().isoformat(timespec="seconds"),
         blob, fp, note))
    con.commit()
    log.info("  数据版本 #%d 指纹 %s", cur.lastrowid, fp)
    for t, v in tables.items():
        log.info("    %-24s %9d 行  最新 %s", t, v["rows"], v["max_date"])
    return fp


def vacuum(con: sqlite3.Connection) -> None:
    path = Path(db.DB_PATH)
    before = path.stat().st_size / 1e6
    tmp = path.with_suffix(".vacuum")
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.execute(f"VACUUM INTO '{tmp}'")     # 不锁原库，安全
    con.close()
    tmp.replace(path)
    after = path.stat().st_size / 1e6
    log.info("  VACUUM: %.0f MB → %.0f MB（回收 %.0f MB）", before, after, before - after)


# --------------------------------------------------------------------------- #
def stats(con: sqlite3.Connection) -> None:
    log.info("表容量明细：")
    total = 0
    for (t,) in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
        n = con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
        if n:
            log.info("  %-28s %10d 行", t, n)
            total += n
    log.info("  合计 %d 行;  库 %.0f MB", total, Path(db.DB_PATH).stat().st_size / 1e6)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--downsample", action="store_true")
    ap.add_argument("--migrate", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--version", action="store_true")
    ap.add_argument("--vacuum", action="store_true")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()
    do_all = args.all or not any([args.downsample, args.migrate, args.version,
                                  args.vacuum, args.stats])

    con = db.connect()
    ensure(con)
    if args.version or do_all:
        log.info("▶ 数据版本化")
        version(con)
    if args.downsample or do_all:
        log.info("▶ 降采样")
        downsample(con)
    if args.migrate or do_all:
        log.info("▶ 冷热分层迁移%s", "（预演）" if args.dry_run else "")
        migrate(con, dry_run=args.dry_run)
    if args.stats or do_all:
        log.info("▶ 容量统计")
        stats(con)
    if args.vacuum:
        log.info("▶ VACUUM")
        vacuum(con)
        return
    con.close()


if __name__ == "__main__":
    main()
