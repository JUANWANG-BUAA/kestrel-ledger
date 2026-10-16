"""估值引擎参考实现（Python）——用于验证 docs/03 §B.2 的算法精度。

模型对照（逐步增强，用于量化每一步的边际收益）：
  M1 穿透      : Σ w_i·r_i                      仅用披露重仓，不补全（低估仓位）
  M2 归一化    : Σ (w_i·k)·r_i, k=S/Σw          把重仓放大到真实股票仓位
  M3 +基准补全 : M2 思路 + (S−Σw)·r_bench       未披露部分用基准指数代理
  M4 +偏差校准 : M3 + EWMA 残差修正              docs/03 §B.3
  M5 +回归仓位 : 用多指数带约束回归替代"基准"代理   docs/03 §B.2.2 ②
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

import numpy as np

# --------------------------------------------------------------------------- #
# 基础工具
# --------------------------------------------------------------------------- #


def daily_returns(closes: dict[str, float]) -> dict[str, float]:
    """{date_str: pct%} —— 收盘价环比。"""
    items = sorted(closes.items())
    out: dict[str, float] = {}
    for i in range(1, len(items)):
        (d0, c0), (d1, c1) = items[i - 1], items[i]
        if c0 and c0 > 0:
            out[d1] = (c1 / c0 - 1.0) * 100.0
    return out


class ReturnsBook:
    """所有标的的日收益表，支持 O(1) 查询。"""

    def __init__(self, klines: dict[str, dict[str, float]]):
        self.ret: dict[str, dict[str, float]] = {
            secid: daily_returns(closes) for secid, closes in klines.items()
        }

    def get(self, secid: str, d: str) -> float | None:
        return self.ret.get(secid, {}).get(d)


@dataclass(slots=True)
class HoldingSet:
    """某一交易日在"已披露"前提下可见的重仓集合（防未来函数）。"""

    period: str
    weights: dict[str, float] = field(default_factory=dict)  # secid -> weight(%)
    stock_ratio: float | None = None

    @property
    def disclosed_sum(self) -> float:
        return sum(self.weights.values())


# --------------------------------------------------------------------------- #
# 模型
# --------------------------------------------------------------------------- #
def m1_penetration(hs: HoldingSet, book: ReturnsBook, d: str) -> float | None:
    """仅按披露权重加权（不补全仓位）——会系统性低估波动。"""
    total, seen = 0.0, 0
    for secid, w in hs.weights.items():
        r = book.get(secid, d)
        if r is None:
            continue
        total += w * r
        seen += 1
    return total / 100.0 if seen else None


def m2_normalized(hs: HoldingSet, book: ReturnsBook, d: str) -> float | None:
    """把披露重仓等比放大到真实股票仓位 S。"""
    wsum = hs.disclosed_sum
    if wsum <= 0 or hs.stock_ratio is None:
        return None
    k = hs.stock_ratio / wsum
    total, seen = 0.0, 0
    for secid, w in hs.weights.items():
        r = book.get(secid, d)
        if r is None:
            continue
        total += w * k * r
        seen += 1
    return total / 100.0 if seen else None


def m3_plus_bench(hs: HoldingSet, book: ReturnsBook, d: str, bench_ret: float | None) -> float | None:
    """披露重仓 + 未披露部分用基准指数代理（现金部分收益记 0）。"""
    wsum = hs.disclosed_sum
    if wsum <= 0:
        return None
    S = hs.stock_ratio if hs.stock_ratio is not None else wsum
    total, seen = 0.0, 0
    for secid, w in hs.weights.items():
        r = book.get(secid, d)
        if r is None:
            continue
        total += w * r
        seen += 1
    if not seen:
        return None
    # 未披露股票仓位
    other = max(0.0, S - wsum)
    if bench_ret is not None:
        total += other * bench_ret
    return total / 100.0


# --------------------------------------------------------------------------- #
# 回归代理（docs/03 §B.2.2 ②：带约束岭回归 + 时间衰减）
# --------------------------------------------------------------------------- #
class RegressionProxy:
    """用基金自身历史收益对多指数做非负岭回归，得到"未披露仓位"的风格代理。

    严格只用 t 之前的数据拟合（防未来函数）。
    """

    def __init__(self, bench_ids: list[str], window: int = 120, lam: float = 0.05):
        self.bench_ids = bench_ids
        self.window = window
        self.lam = lam
        self._cache: dict[str, np.ndarray] = {}

    def fit(self, d: str, fund_ret: dict[str, float], book: ReturnsBook) -> np.ndarray | None:
        if d in self._cache:
            return self._cache[d]
        dates = sorted(x for x in fund_ret if x < d)[-self.window :]
        if len(dates) < 40:
            return None
        y = np.array([fund_ret[x] for x in dates], dtype=float)
        X = []
        for bid in self.bench_ids:
            col = [book.get(bid, x) for x in dates]
            if any(c is None for c in col):
                return None
            X.append(col)
        X = np.array(X, dtype=float).T                      # (T, k)
        # 时间衰减权重：越近权重越大
        w = 0.98 ** np.arange(len(dates) - 1, -1, -1)
        Xw = X * w[:, None]
        yw = y * w
        k = X.shape[1]
        A = Xw.T @ Xw + self.lam * np.eye(k)
        try:
            beta = np.linalg.solve(A, Xw.T @ yw)
        except np.linalg.LinAlgError:
            return None
        beta = np.clip(beta, 0.0, None)                     # 非负约束（投影）
        self._cache[d] = beta
        return beta

    def predict(self, beta: np.ndarray, d: str, book: ReturnsBook) -> float | None:
        vals = [book.get(bid, d) for bid in self.bench_ids]
        if any(v is None for v in vals):
            return None
        return float(np.dot(beta, np.array(vals, dtype=float)))


# --------------------------------------------------------------------------- #
# 偏差校准（docs/03 §B.3）
# --------------------------------------------------------------------------- #
class BiasCalibrator:
    """EWMA 残差修正：pct_final = pct_est + b_t，b_t = θ·e_{t-1} + (1−θ)·b_{t-1}"""

    def __init__(self, theta: float = 0.25):
        self.theta = theta
        self.bias = 0.0
        self.var = 0.0
        self.n = 0

    def apply(self, est: float) -> float:
        return est + self.bias

    def update(self, est: float, actual: float) -> float:
        e = actual - est
        self.bias = self.theta * e + (1 - self.theta) * self.bias
        resid = e - self.bias
        self.var = self.theta * resid * resid + (1 - self.theta) * self.var
        self.n += 1
        return e

    @property
    def sigma(self) -> float:
        return float(np.sqrt(self.var)) if self.n > 3 else float("nan")


# --------------------------------------------------------------------------- #


# --------------------------------------------------------------------------- #
# M6：持仓漂移修正（针对实测发现的主导误差源）
# --------------------------------------------------------------------------- #
class DriftWeightEstimator:
    """用近期基金收益反推**当前真实持仓权重**，修正季报调仓漂移。

    与 RegressionProxy 的本质区别：
      RegressionProxy  → 用回归代理"未披露仓位"
      DriftWeightEstimator → 用回归修正"已披露但已过期"的权重（实测主导误差源）

        min ||y − Xβ||²_decay + λ‖β − prior‖²      s.t. β ≥ 0

    单位约定（关键）：内部全程用**小数权重**（0.0996），对外返回**百分比权重**（9.96）。
    λ 大 → 信任季报；λ 小 → 信任近期收益。λ 可随持仓新鲜度自适应。
    """

    def __init__(self, window: int = 60, decay: float = 0.97, min_coverage: float = 0.8):
        self.window = window
        self.decay = decay
        self.min_coverage = min_coverage

    def fit(
        self,
        d: str,
        nav_pct: dict[str, float],
        secids: list[str],
        prior_pct: dict[str, float],
        book: "ReturnsBook",
        bench_id: str = "1.000300",
        lam: float = 0.05,
    ) -> tuple[list[str], np.ndarray] | None:
        dates = sorted(x for x in nav_pct if x < d)[-self.window :]
        if len(dates) < max(20, self.window // 2):
            return None
        y = np.array([nav_pct[x] for x in dates], dtype=float)
        if y.std() <= 0:
            return None

        kept: list[str] = []
        cols: list[np.ndarray] = []
        for sid in secids:
            vals = [book.get(sid, x) for x in dates]
            ok = sum(v is not None for v in vals)
            if ok < self.min_coverage * len(dates):
                continue                       # 覆盖不足 -> 丢弃该股（不放弃整次拟合）
            cols.append(np.array([0.0 if v is None else v for v in vals], dtype=float))
            kept.append(sid)

        bcol = [book.get(bench_id, x) for x in dates]
        if any(c is None for c in bcol):
            return None
        cols.append(np.array(bcol, dtype=float))          # 基准列，吸收未披露仓位
        X = np.array(cols, dtype=float).T                 # (T, k+1)

        w = self.decay ** np.arange(len(dates) - 1, -1, -1)
        Xw, yw = X * w[:, None], y * w

        prior = np.array([prior_pct.get(s, 0.0) / 100.0 for s in kept], dtype=float)
        other = max(0.0, 1.0 - prior.sum())
        prior_full = np.append(prior, other)

        n = X.shape[1]
        A = Xw.T @ Xw + lam * np.eye(n)
        b = Xw.T @ yw + lam * prior_full
        try:
            beta = np.linalg.solve(A, b)
        except np.linalg.LinAlgError:
            return None
        beta = np.clip(beta, 0.0, None)
        return kept + [bench_id], beta * 100.0            # 统一转回百分比权重


def estimate_with_weights(
    secids: list[str], weights_pct: np.ndarray, d: str,
    book: "ReturnsBook", bench_id: str = "1.000300",
) -> float | None:
    """weights_pct 为百分比权重；返回估算涨跌幅 %。"""
    tot, seen = 0.0, 0
    for sid, w in zip(secids, weights_pct):
        r = book.get(sid, d)
        if r is None:
            continue
        tot += w * r
        seen += 1
    return tot / 100.0 if seen else None


class SignificantBiasCalibrator(BiasCalibrator):
    """只在偏差统计显著时才修正（M4 改进版）。

    实测发现：偏差均值≈0 时无条件修正只引入噪声，
    因此加 t 检验门槛：|bias| > z·σ/√n 才生效。
    """

    def __init__(self, theta: float = 0.25, z: float = 2.0, min_n: int = 20):
        super().__init__(theta)
        self.z = z
        self.min_n = min_n
        self.enabled = False

    def apply(self, est: float) -> float:
        return est + self.bias if self.enabled else est

    def update(self, est: float, actual: float) -> float:
        e = super().update(est, actual)
        if self.n >= self.min_n:
            se = self.sigma / max(1.0, float(np.sqrt(self.n)))
            self.enabled = bool(abs(self.bias) > self.z * se)
        return e


# --------------------------------------------------------------------------- #
# M8/M9/M10：主题基金专用 —— 因子回归（含真正 NNLS 求解）
# --------------------------------------------------------------------------- #
from scipy.optimize import lsq_linear  # noqa: E402


class NnRidgeRegressor:
    """非负岭回归（scipy.lsq_linear 精确求解）。

        min ‖Xβ − y‖²_decay + λ‖β − prior‖²   s.t. β ≥ 0

    支持候选因子筛选（按滚动窗口相关性取 Top-K），用于：
      * M9  重仓股 + 主题因子集
      * M10 仅主题因子集（完全不依赖持仓披露）
    """

    def __init__(self, window: int = 60, decay: float = 0.97, min_coverage: float = 0.8):
        self.window = window
        self.decay = decay
        self.min_coverage = min_coverage

    def fit(
        self,
        d: str,
        nav_pct: dict[str, float],
        candidates: list[str],
        prior_pct: dict[str, float],
        book: "ReturnsBook",
        lam: float = 0.5,
        top_k: int | None = None,
        min_corr: float = 0.0,
    ) -> tuple[list[str], np.ndarray] | None:
        dates = sorted(x for x in nav_pct if x < d)[-self.window :]
        if len(dates) < max(20, self.window // 2):
            return None
        y = np.array([nav_pct[x] for x in dates], dtype=float)
        if y.std() <= 0:
            return None

        kept: list[str] = []
        cols: list[np.ndarray] = []
        for sid in candidates:
            vals = [book.get(sid, x) for x in dates]
            ok = sum(v is not None for v in vals)
            if ok < self.min_coverage * len(dates):
                continue
            col = np.array([0.0 if v is None else v for v in vals], dtype=float)
            if col.std() <= 0:
                continue
            kept.append(sid)
            cols.append(col)
        if not kept:
            return None

        if min_corr > 0:
            corrs = np.array([abs(np.corrcoef(c, y)[0, 1]) for c in cols])
            order = np.argsort(-corrs)
            keep_idx = [i for i in order if corrs[i] >= min_corr]
            if top_k:
                keep_idx = keep_idx[:top_k]
            kept = [kept[i] for i in keep_idx]
            cols = [cols[i] for i in keep_idx]
            if not kept:
                return None
        elif top_k and len(kept) > top_k:
            corrs = np.array([abs(np.corrcoef(c, y)[0, 1]) for c in cols])
            keep_idx = list(np.argsort(-corrs)[:top_k])
            kept = [kept[i] for i in keep_idx]
            cols = [cols[i] for i in keep_idx]

        if not cols:
            return None
        X = np.array(cols, dtype=float).T                    # (T, N)
        w = self.decay ** np.arange(len(dates) - 1, -1, -1)
        Xw, yw = X * w[:, None], y * w
        prior = np.array([prior_pct.get(s, 0.0) / 100.0 for s in kept], dtype=float)

        n = X.shape[1]
        A = np.vstack([Xw, np.sqrt(lam) * np.eye(n)])
        b = np.concatenate([yw, np.sqrt(lam) * prior])
        try:
            res = lsq_linear(A, b, bounds=(0.0, np.inf), max_iter=200)
        except Exception:  # noqa: BLE001
            return None
        if not res.success and res.status != 0:
            return None
        return kept, res.x * 100.0
