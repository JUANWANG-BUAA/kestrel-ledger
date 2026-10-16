"""限流 + 磁盘缓存 + 重试 的 HTTP 客户端。

设计目标（对应 docs/02 §A.0）：
  1. 单域名限流，避免软限流/封禁
  2. 磁盘缓存，重复实验不重复请求
  3. GBK/UTF-8 自动解码（新浪/腾讯为 GBK）
  4. 指数退避 + 备源
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import random
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import requests

log = logging.getLogger("mkt.http")

ROOT = Path(__file__).resolve().parents[2]
CACHE_DIR = ROOT / "data" / "cache"
CACHE_DIR.mkdir(parents=True, exist_ok=True)

DEFAULT_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
)

# host -> (referer, encoding)
HOST_PROFILE: dict[str, tuple[str, str]] = {
    "fund.eastmoney.com": ("https://fund.eastmoney.com/", "utf-8"),
    "fundf10.eastmoney.com": ("https://fundf10.eastmoney.com/", "utf-8"),
    "api.fund.eastmoney.com": ("https://fundf10.eastmoney.com/", "utf-8"),
    "fundmobapi.eastmoney.com": ("https://fund.eastmoney.com/", "utf-8"),
    "fundsuggest.eastmoney.com": ("https://fund.eastmoney.com/", "utf-8"),
    "push2.eastmoney.com": ("https://quote.eastmoney.com/", "utf-8"),
    "push2delay.eastmoney.com": ("https://quote.eastmoney.com/", "utf-8"),
    "push2his.eastmoney.com": ("https://quote.eastmoney.com/", "utf-8"),
    "searchapi.eastmoney.com": ("https://www.eastmoney.com/", "utf-8"),
    "www.sge.com.cn": ("https://www.sge.com.cn/sjzx/mrhq", "utf-8"),
    "qt.gtimg.cn": ("https://gu.qq.com/", "gbk"),
    "hq.sinajs.cn": ("https://finance.sina.com.cn/", "gbk"),
}


@dataclass
class RateLimiter:
    """按 host 的最小间隔限流；支持每主机覆盖。"""

    min_interval: float = 0.45
    overrides: dict[str, float] = field(default_factory=dict)
    _last: dict[str, float] = field(default_factory=dict)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def wait(self, host: str) -> None:
        """获取一个发送配额。

        ⚠️ 关键：**必须在锁外 sleep** —— 早期版本在锁内 sleep 会把所有线程串行化，
        多线程时吞吐等于单线程（实测 453 只标的要跑 10 分钟）。
        """
        interval = self.overrides.get(host, self.min_interval)
        with self._lock:
            now = time.monotonic()
            prev = self._last.get(host, 0.0)
            start = max(now, prev + interval)
            self._last[host] = start + random.uniform(0, 0.02)   # 预留下一个槽位
            delay = start - now
        if delay > 0:
            time.sleep(delay)


# 每主机节奏（实测得出）：push2his 极敏感，腾讯/新浪宽松
HOST_INTERVAL = {
    "push2his.eastmoney.com": 3.0,
    "push2.eastmoney.com": 0.6,
    "push2delay.eastmoney.com": 0.6,
    "web.ifzq.gtimg.cn": 0.22,
    "quotes.sina.cn": 0.35,
    "hq.sinajs.cn": 0.5,
    "www.sge.com.cn": 0.8,
}


class HttpClient:
    def __init__(self, min_interval: float = 0.45, cache_ttl: int = 24 * 3600, retries: int = 2):
        self.limiter = RateLimiter(min_interval=min_interval, overrides=dict(HOST_INTERVAL))
        self.cache_ttl = cache_ttl
        self.default_retries = retries
        self.sess = requests.Session()
        self.stats = {"hit": 0, "miss": 0, "error": 0, "bytes": 0}

    # ---------- cache ----------
    @staticmethod
    def _key(method: str, url: str, body: Any) -> str:
        raw = f"{method}|{url}|{json.dumps(body, sort_keys=True, ensure_ascii=False)}"
        return hashlib.sha1(raw.encode()).hexdigest()

    def _cache_get(self, key: str) -> str | None:
        p = CACHE_DIR / f"{key}.txt"
        if not p.exists():
            return None
        if time.time() - p.stat().st_mtime > self.cache_ttl:
            return None
        self.stats["hit"] += 1
        return p.read_text(encoding="utf-8")

    def _cache_put(self, key: str, text: str, url: str) -> None:
        (CACHE_DIR / f"{key}.txt").write_text(text, encoding="utf-8")
        (CACHE_DIR / f"{key}.meta").write_text(
            json.dumps({"url": url, "ts": time.time()}, ensure_ascii=False), encoding="utf-8"
        )

    # ---------- request ----------
    def get(
        self,
        url: str,
        *,
        params: dict | None = None,
        data: dict | None = None,
        referer: str | None = None,
        encoding: str | None = None,
        ttl: int | None = None,
        use_cache: bool = True,
        retries: int | None = None,
    ) -> str:
        from urllib.parse import urlparse

        host = urlparse(url).netloc
        ref_default, enc_default = HOST_PROFILE.get(host, ("", "utf-8"))
        ref = referer or ref_default
        enc = encoding or enc_default

        key = self._key("GET" if data is None else "POST", url, params or data)
        if use_cache:
            cached = self._cache_get(key)
            if cached is not None and (ttl is None or ttl >= self.cache_ttl):
                return cached

        headers = {
            "User-Agent": DEFAULT_UA,
            "Accept": "*/*",
            "Accept-Language": "zh-CN,zh;q=0.9",
        }
        if ref:
            headers["Referer"] = ref

        last_err: Exception | None = None
        n_retries = self.default_retries if retries is None else retries
        for attempt in range(max(1, n_retries)):
            try:
                self.limiter.wait(host)
                if data is None:
                    r = self.sess.get(url, params=params, headers=headers, timeout=15)
                else:
                    r = self.sess.post(url, data=data, headers=headers, timeout=15)
                self.stats["miss"] += 1
                self.stats["bytes"] += len(r.content)
                if r.status_code != 200:
                    raise RuntimeError(f"HTTP {r.status_code}")
                text = r.content.decode(enc, errors="replace")
                if use_cache:
                    self._cache_put(key, text, url)
                return text
            except Exception as e:  # noqa: BLE001
                last_err = e
                self.stats["error"] += 1
                # 连接被服务端断开（限流特征）→ 换连接，快速失败，不做长时间退避
                if isinstance(e, requests.exceptions.ConnectionError):
                    try:
                        self.sess.close()
                    except Exception:  # noqa: BLE001
                        pass
                    self.sess = requests.Session()
                if attempt < n_retries - 1:
                    sleep = 0.6 * (2**attempt) + random.uniform(0, 0.3)
                    log.debug("请求失败(%s/%s) %s: %s -> %.1fs 后重试", attempt + 1, n_retries, url, e, sleep)
                    time.sleep(sleep)
        raise RuntimeError(f"请求最终失败: {url} | {last_err}")


HTTP = HttpClient()
