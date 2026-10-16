"""深交所官方数据（ETF 净值 / 基金行情）。

实测（2026-10-15）：
  CATALOGID=1785_child  基金净值（官方，日频）    ✅
  CATALOGID=1815_fund_child  基金行情（开收高低）  ✅
  CATALOGID=1945  ETF 列表（756 只，含申赎清单页链接）
  CATALOGID=1956  Fund Information（1069 条）

⚠️ 深交所「申购赎回清单」页 `/disclosure/fund/currency/index.html` 存在，
   但数据非 ShowReport API 提供（页面无 ShowReport 调用）→ 待 S0 进一步定位。
"""
from __future__ import annotations
import json
import logging
from datetime import date, datetime

from mkt.http import HTTP

log = logging.getLogger("mkt.szse")
BASE = "https://www.szse.cn/api/report/ShowReport/data"
REF = "https://www.szse.cn/disclosure/fund/etf/index.html"


def _show(catalog: str, code: str | None = None, page: int = 1) -> list[dict]:
    params = {"SHOWTYPE": "JSON", "CATALOGID": catalog, "TABKEY": "tab1", "PAGENO": page}
    if code:
        params["txtDm"] = code
    txt = HTTP.get(BASE, params=params, referer=REF, ttl=6 * 3600)
    try:
        blocks = json.loads(txt)
    except json.JSONDecodeError:
        return []
    if not isinstance(blocks, list) or not blocks:
        return []
    return (blocks[0] or {}).get("data") or []


def etf_list(page: int = 1) -> list[dict]:
    """ETF 列表（含代码/简称/管理人等）。"""
    return _show("1945", page=page)


def fund_nav(code: str) -> list[dict]:
    """深交所官方基金净值。返回 [{nav_date, fund_code, name, nav}]（多日）。"""
    rows = _show("1785_child", code)
    out = []
    for r in rows:
        try:
            out.append({
                "nav_date": datetime.strptime(r["nav_date"], "%Y-%m-%d").date(),
                "code": r["fund_code"],
                "name": r.get("security_short_name", ""),
                "nav": float(r["nav_per_share"]),
            })
        except (KeyError, ValueError, TypeError):
            continue
    return sorted(out, key=lambda x: x["nav_date"])


def fund_quote(code: str) -> dict | None:
    """深交所基金行情（当日开收高低）。"""
    rows = _show("1815_fund_child", code)
    if not rows:
        return None
    r = rows[0]
    try:
        return {"date": r.get("jyrq"), "code": r.get("zqdm"), "name": r.get("zqjc"),
                "open": float(r.get("qss") or 0), "close": float(r.get("ss") or 0),
                "change_pct": float(r.get("sdf") or 0), "amount": r.get("cjje")}
    except (ValueError, TypeError):
        return None
