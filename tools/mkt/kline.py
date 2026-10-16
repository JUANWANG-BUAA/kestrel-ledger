"""统一 K 线取数：多源 + 熔断（fail-fast，不做长时间指数退避）。

设计依据（实测）：
  * push2his 存在**连接级持久限流**——触发后即使冷却 45s，单次请求仍被 RemoteDisconnected。
    因此重试策略必须是"快速失败 + 拉黑主机一段时间"，而不是硬重试。
  * 腾讯为 K 线主源（无该限流），新浪为备源（字段命名明确，可交叉校验）。
"""
from __future__ import annotations

import json
import logging
import time
from datetime import date, datetime

from mkt.http import HTTP
from mkt.sources import tencent as tx

log = logging.getLogger("mkt.kline")


class CircuitBreaker:
    """主机级熔断：连续失败 N 次 -> 拉黑 open_seconds。"""

    def __init__(self, threshold: int = 4, open_seconds: float = 900.0):
        self.threshold = threshold
        self.open_seconds = open_seconds
        self._fails: dict[str, int] = {}
        self._until: dict[str, float] = {}

    def is_open(self, host: str) -> bool:
        return time.monotonic() < self._until.get(host, 0.0)

    def record(self, host: str, ok: bool) -> None:
        if ok:
            self._fails[host] = 0
            return
        self._fails[host] = self._fails.get(host, 0) + 1
        if self._fails[host] >= self.threshold:
            self._until[host] = time.monotonic() + self.open_seconds
            log.warning("[熔断] %s 连续失败 %d 次，静默 %.0f 秒", host, self._fails[host], self.open_seconds)

    def states(self) -> dict[str, str]:
        return {h: ("OPEN" if self.is_open(h) else "CLOSED") for h in self._fails}


BREAKER = CircuitBreaker()

_TX_HOST = "web.ifzq.gtimg.cn"
_SINA_HOST = "quotes.sina.cn"
_EM_HOST = "push2his.eastmoney.com"


def _from_sina(secid: str) -> dict[date, float]:
    """新浪备源：字段名为 day/open/high/low/close/volume，可直接交叉校验。"""
    sym = tx.em_secid_to_tx(secid)
    if not sym or sym.startswith("us") or sym.startswith("jp") or sym.startswith("kr"):
        return {}
    txt = HTTP.get(
        "https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData",
        params={"symbol": sym, "scale": 240, "ma": "no", "datalen": 900},
        referer="https://finance.sina.com.cn/",
        ttl=12 * 3600,
        retries=2,
    )
    try:
        rows = json.loads(txt)
    except json.JSONDecodeError:
        return {}
    out: dict[date, float] = {}
    for r in rows or []:
        try:
            out[datetime.strptime(r["day"], "%Y-%m-%d").date()] = float(r["close"])
        except (KeyError, ValueError, TypeError):
            continue
    return out


def _from_em(secid: str) -> dict[date, float]:
    txt = HTTP.get(
        "https://push2his.eastmoney.com/api/qt/stock/kline/get",
        params={
            "secid": secid,
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
            "klt": 101,
            "fqt": 1,
            "end": "20500101",
            "lmt": 900,
        },
        referer="https://quote.eastmoney.com/",
        ttl=12 * 3600,
        retries=1,
    )
    data = json.loads(txt)
    rows = ((data or {}).get("data") or {}).get("klines") or []
    out: dict[date, float] = {}
    for line in rows:
        p = line.split(",")
        if len(p) >= 3:
            try:
                out[datetime.strptime(p[0], "%Y-%m-%d").date()] = float(p[2])
            except ValueError:
                continue
    return out


def fetch(secid: str, allow_em: bool = False) -> tuple[dict[date, float], str]:
    """返回 (closes, source)。source ∈ {tencent, sina, eastmoney, none}"""
    # 1) 腾讯
    if not BREAKER.is_open(_TX_HOST):
        try:
            d = tx.kline_daily(secid)
            # 空结果≠主机故障（如部分 ETF/指数无 qfq 数据），不计入熔断
            if d:
                BREAKER.record(_TX_HOST, True)
                return d, "tencent"
            BREAKER.record(_TX_HOST, True)
        except Exception as e:  # noqa: BLE001
            BREAKER.record(_TX_HOST, False)
            log.debug("tencent %s 失败: %s", secid, e)

    # 2) 新浪
    if not BREAKER.is_open(_SINA_HOST):
        try:
            d = _from_sina(secid)
            BREAKER.record(_SINA_HOST, True)
            if d:
                return d, "sina"
        except Exception as e:  # noqa: BLE001
            BREAKER.record(_SINA_HOST, False)
            log.debug("sina %s 失败: %s", secid, e)

    # 3) 东财（默认关闭：已知会被持久限流，只在显式允许时尝试）
    if allow_em and not BREAKER.is_open(_EM_HOST):
        try:
            d = _from_em(secid)
            BREAKER.record(_EM_HOST, bool(d))
            if d:
                return d, "eastmoney"
        except Exception as e:  # noqa: BLE001
            BREAKER.record(_EM_HOST, False)
            log.debug("eastmoney %s 失败: %s", secid, e)

    return {}, "none"
