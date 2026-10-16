"""研究用 SQLite 存储（字段与 docs/03 §B.8 的正式 DDL 对齐，去掉加密与 UI 相关表）。"""
from __future__ import annotations

import sqlite3
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DB_PATH = ROOT / "data" / "research.db"

SCHEMA = """
CREATE TABLE IF NOT EXISTS fund_meta (
  code TEXT PRIMARY KEY, name TEXT, fund_type TEXT, updated_at TEXT
);

CREATE TABLE IF NOT EXISTS fund_nav (
  code TEXT NOT NULL, nav_date TEXT NOT NULL,
  unit_nav REAL, acc_nav REAL, pct REAL,
  PRIMARY KEY(code, nav_date)
);

CREATE TABLE IF NOT EXISTS fund_alloc (
  code TEXT NOT NULL, period TEXT NOT NULL,
  stock_ratio REAL, cash_ratio REAL,
  PRIMARY KEY(code, period)
);

CREATE TABLE IF NOT EXISTS fund_holding (
  code TEXT NOT NULL, period TEXT NOT NULL, secid TEXT NOT NULL,
  stock_code TEXT, stock_name TEXT, weight REAL, rank INTEGER,
  PRIMARY KEY(code, period, secid)
);

CREATE TABLE IF NOT EXISTS stock_daily (
  secid TEXT NOT NULL, trade_date TEXT NOT NULL, close REAL,
  PRIMARY KEY(secid, trade_date)
);

CREATE TABLE IF NOT EXISTS collect_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task TEXT, key TEXT, ok INTEGER, detail TEXT, at TEXT
);
"""


def connect(path: Path | None = None) -> sqlite3.Connection:
    p = path or DB_PATH
    p.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(p)
    con.executescript(SCHEMA)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    return con


def upsert_nav(con: sqlite3.Connection, code: str, rows) -> int:
    con.executemany(
        "INSERT OR REPLACE INTO fund_nav(code,nav_date,unit_nav,acc_nav,pct) VALUES(?,?,?,?,?)",
        [(code, r.nav_date.isoformat(), r.unit_nav, r.acc_nav, r.pct) for r in rows],
    )
    con.commit()
    return len(rows)


def upsert_alloc(con: sqlite3.Connection, code: str, mapping: dict[str, float]) -> int:
    con.executemany(
        "INSERT OR REPLACE INTO fund_alloc(code,period,stock_ratio,cash_ratio) VALUES(?,?,?,NULL)",
        [(code, period, ratio) for period, ratio in mapping.items()],
    )
    con.commit()
    return len(mapping)


def upsert_holdings(con: sqlite3.Connection, code: str, rows) -> int:
    con.executemany(
        "INSERT OR REPLACE INTO fund_holding(code,period,secid,stock_code,stock_name,weight,rank)"
        " VALUES(?,?,?,?,?,?,?)",
        [(code, r.report_period, r.secid, r.code, r.name, r.weight, 0) for r in rows],
    )
    con.commit()
    return len(rows)


def upsert_klines(con: sqlite3.Connection, secid: str, closes: dict[date, float]) -> int:
    con.executemany(
        "INSERT OR REPLACE INTO stock_daily(secid,trade_date,close) VALUES(?,?,?)",
        [(secid, d.isoformat(), c) for d, c in closes.items()],
    )
    con.commit()
    return len(closes)


def upsert_meta(con: sqlite3.Connection, code: str, name: str, fund_type: str = "") -> None:
    """注意：不要用 INSERT OR REPLACE —— 空 fund_type 会把已抓取的类型覆盖掉。"""
    con.execute(
        "INSERT INTO fund_meta(code,name,fund_type,updated_at) VALUES(?,?,?,datetime('now'))"
        " ON CONFLICT(code) DO UPDATE SET name=excluded.name, updated_at=excluded.updated_at,"
        " fund_type=CASE WHEN excluded.fund_type != '' THEN excluded.fund_type ELSE fund_meta.fund_type END",
        (code, name, fund_type),
    )
    con.commit()


def log(con: sqlite3.Connection, task: str, key: str, ok: bool, detail: str = "") -> None:
    con.execute(
        "INSERT INTO collect_log(task,key,ok,detail,at) VALUES(?,?,?,?,datetime('now'))",
        (task, key, 1 if ok else 0, detail),
    )
    con.commit()


# --------------------------------------------------------------------------- #
# 读取（回测用）
# --------------------------------------------------------------------------- #
def load_nav(con: sqlite3.Connection, code: str) -> dict[str, float]:
    """{nav_date: pct}"""
    cur = con.execute("SELECT nav_date, pct FROM fund_nav WHERE code=? ORDER BY nav_date", (code,))
    return {d: p for d, p in cur.fetchall()}


def load_nav_series(con: sqlite3.Connection, code: str) -> dict[str, float]:
    """{nav_date: unit_nav}"""
    cur = con.execute("SELECT nav_date, unit_nav FROM fund_nav WHERE code=? ORDER BY nav_date", (code,))
    return {d: v for d, v in cur.fetchall()}


def load_alloc(con: sqlite3.Connection, code: str) -> dict[str, float]:
    cur = con.execute("SELECT period, stock_ratio FROM fund_alloc WHERE code=?", (code,))
    return {p: r for p, r in cur.fetchall() if r is not None}


def load_holdings(con: sqlite3.Connection, code: str) -> dict[str, list[tuple[str, float]]]:
    """{period: [(secid, weight_pct), ...]}"""
    cur = con.execute(
        "SELECT period, secid, weight FROM fund_holding WHERE code=? ORDER BY period, weight DESC", (code,)
    )
    out: dict[str, list[tuple[str, float]]] = {}
    for period, secid, w in cur.fetchall():
        out.setdefault(period, []).append((secid, w))
    return out


def load_klines(con: sqlite3.Connection, secid: str) -> dict[str, float]:
    cur = con.execute("SELECT trade_date, close FROM stock_daily WHERE secid=? ORDER BY trade_date", (secid,))
    return {d: c for d, c in cur.fetchall()}


def stats(con: sqlite3.Connection) -> dict[str, int]:
    out = {}
    for t in ("fund_meta", "fund_nav", "fund_holding", "fund_alloc", "stock_daily"):
        out[t] = con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
    out["funds_with_nav"] = con.execute("SELECT COUNT(DISTINCT code) FROM fund_nav").fetchone()[0]
    out["secids_with_kline"] = con.execute("SELECT COUNT(DISTINCT secid) FROM stock_daily").fetchone()[0]
    return out
