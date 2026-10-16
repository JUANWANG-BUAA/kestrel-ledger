"""腾讯行情（K 线 / 实时）。

实测结论（2026-10-15）：
  * `web.ifzq.gtimg.cn/appstock/app/fqkline/get` 支持 A股 / 港股 / 日股 / 韩股 / 美股 / 指数
  * 返回字段顺序为 `[date, open, close, high, low, volume]`
  * 已与新浪同标的逐日交叉校验，收盘价**完全一致**（600519 抽检 4/4 通过）
  * 无 push2his 那种连接级限流，是 K 线的**主源**

代码格式：sh600519 / sz000858 / hk00700 / usAAPL / jp6758 / kr005930
"""
from __future__ import annotations

import json
import logging
from datetime import date, datetime

from mkt.http import HTTP

log = logging.getLogger("mkt.tx")


def em_secid_to_tx(secid: str) -> str | None:
    """东财 secid -> 腾讯代码。

    市场号对照（实测）：
      1=沪  0=深  116=港  105/106/107=美  176=日  177=韩  178=台  100=全球指数
    """
    if "." not in secid:
        return None
    mkt, code = secid.split(".", 1)
    mkt = mkt.strip()
    code = code.strip()
    if mkt == "1":
        return f"sh{code}"
    if mkt == "0":
        # 深市：股票 00/30，指数 39/89/98；两者都是 sz 前缀
        return f"sz{code}"
    if mkt == "116":
        return f"hk{code.zfill(5)}"
    if mkt in ("105", "106", "107"):
        return f"us{code}"
    if mkt == "176":
        return f"jp{code}"
    if mkt == "177":
        return f"kr{code}"
    if mkt == "178":
        return f"tw{code}"
    if mkt == "100":
        # 全球指数：腾讯用 us 前缀的指数代码（如 usDJI / usNDX）
        return f"us{code}"
    return None


def kline_daily(secid: str, limit: int = 900) -> dict[date, float]:
    """前复权日线收盘价 {date: close}。"""
    tx_code = em_secid_to_tx(secid)
    if not tx_code:
        return {}
    txt = HTTP.get(
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
        params={"param": f"{tx_code},day,,,{limit},qfq"},
        ttl=12 * 3600,
    )
    try:
        data = json.loads(txt)
    except json.JSONDecodeError:
        return {}
    node = (data.get("data") or {}).get(tx_code) or {}
    rows = node.get("qfqday") or node.get("day") or []
    out: dict[date, float] = {}
    for r in rows:
        try:
            out[datetime.strptime(r[0], "%Y-%m-%d").date()] = float(r[2])  # index 2 = close
        except (ValueError, IndexError, TypeError):
            continue
    return out
