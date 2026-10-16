"""量化方法工具箱：用于基金持仓权重估计的先进算法。

全部实现为**无 sklearn 依赖**（仅 numpy/scipy），可移植到 Kotlin。

方法清单
  KalmanWeightTracker   状态空间模型 + 卡尔曼滤波（时变权重）
  RLS                   递归最小二乘 + 遗忘因子（自适应遗忘）
  ElasticNetNN          非负弹性网（坐标下降，L1 稀疏选择）
  HuberIRLS             稳健回归（IRLS，抗异常值）
  OnlineEnsemble        Hedge/Exp3 在线集成（按历史表现动态加权）
  AdaptiveWindow        自适应窗口（用尾部表现选窗口长度）
  ChangePointDetector   CUSUM 变点检测（持仓切换时重置）
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


# --------------------------------------------------------------------------- #
# 1. 状态空间模型 + 卡尔曼滤波（时变权重）
# --------------------------------------------------------------------------- #
class KalmanWeightTracker:
    """权重随机游走模型：

        β_t = β_{t-1} + w_t,     w ~ N(0, Q)      （持仓缓慢漂移）
        y_t = x_tᵀ β_t + v_t,    v ~ N(0, R)      （净值 = 权重 × 持仓收益）

    相比滚动窗口回归：不需要选窗口长度，权重随时间连续演化，
    能捕捉"缓慢调仓"，且天然给出**置信区间**（P 矩阵）。
    """

    def __init__(self, n: int, q: float = 1e-4, r: float = 1.0,
                 prior: np.ndarray | None = None, p0: float = 1e-2):
        self.n = n
        self.q = q                      # 过程噪声（调仓速度）
        self.r = r                      # 观测噪声（净值噪声）
        self.beta = np.zeros(n) if prior is None else prior.copy()
        self.P = p0 * np.eye(n)

    def update(self, x: np.ndarray, y: float) -> np.ndarray:
        # 预测
        self.P = self.P + self.q * np.eye(self.n)
        # 更新
        Px = self.P @ x
        s = float(x @ Px) + self.r
        k = Px / s
        self.beta = self.beta + k * (y - float(x @ self.beta))
        self.P = self.P - np.outer(k, Px)
        # 注意：递归内部不裁剪 β（会破坏 P 与 β 的一致性 → 数值发散）；
        # 非负投影只在输出估计值时施加
        return self.beta

    @property
    def std(self) -> np.ndarray:
        return np.sqrt(np.diag(self.P))

    def estimate(self, x: np.ndarray) -> float:
        return float(x @ np.clip(self.beta, 0.0, None))


# --------------------------------------------------------------------------- #
# 2. 递归最小二乘 + 遗忘因子
# --------------------------------------------------------------------------- #
class RLS:
    """β_t = argmin Σ λ^{t-i} (y_i − x_iᵀβ)²    （λ = 遗忘因子）

    λ→1 记忆长、更稳；λ→小 记忆短、更灵敏。等价于滚动窗口的连续版本。
    """

    def __init__(self, n: int, lam: float = 0.97, delta: float = 10.0):
        self.n, self.lam = n, lam
        self.P = delta * np.eye(n)
        self.beta = np.zeros(n)

    def update(self, x: np.ndarray, y: float) -> np.ndarray:
        Px = self.P @ x
        k = Px / (self.lam + float(x @ Px))
        self.beta = self.beta + k * (y - float(x @ self.beta))
        self.P = (self.P - np.outer(k, Px)) / self.lam
        return self.beta

    def estimate(self, x: np.ndarray) -> float:
        return float(x @ np.clip(self.beta, 0.0, None))


# --------------------------------------------------------------------------- #
# 3. 非负弹性网（坐标下降）
# --------------------------------------------------------------------------- #
class ElasticNetNN:
    """min ‖y − Xβ‖²/(2T) + α·ρ‖β‖₁ + α(1−ρ)/2·‖β‖₂²   s.t. β ≥ 0

    L1 部分做**稀疏选择** —— 从几十到上百个候选因子中自动挑出有效的少数几个，
    比 top-K 相关性筛选更严谨（考虑联合效应）。
    """

    def __init__(self, alpha: float = 0.02, l1_ratio: float = 0.5,
                 max_iter: int = 300, tol: float = 1e-6):
        self.alpha, self.rho = alpha, l1_ratio
        self.max_iter, self.tol = max_iter, tol

    def fit(self, X: np.ndarray, y: np.ndarray,
            sample_weight: np.ndarray | None = None) -> np.ndarray:
        T, n = X.shape
        w = np.ones(T) if sample_weight is None else sample_weight
        w = w / w.sum() * T
        Xw = X * w[:, None]
        # 特征缩放（弹性网对尺度敏感）
        scale = np.sqrt((Xw * X).sum(axis=0) / T)
        scale[scale == 0] = 1.0
        Xs = X / scale
        Xsw = Xs * w[:, None]
        XtX = Xsw.T @ Xs / T
        Xty = Xsw.T @ y / T
        beta = np.zeros(n)
        l1 = self.alpha * self.rho
        l2 = self.alpha * (1 - self.rho)
        for _ in range(self.max_iter):
            beta_old = beta.copy()
            for j in range(n):
                r = Xty[j] - float(XtX[j] @ beta) + XtX[j, j] * beta[j]
                denom = XtX[j, j] + l2
                if denom <= 0:
                    beta[j] = 0.0
                    continue
                z = r / denom
                beta[j] = max(0.0, z - l1 / denom)      # 软阈值 + 非负
            if np.max(np.abs(beta - beta_old)) < self.tol:
                break
        return beta / scale


# --------------------------------------------------------------------------- #
# 4. 稳健回归（Huber IRLS）
# --------------------------------------------------------------------------- #
class HuberIRLS:
    """Huber 损失：小残差用平方、大残差用线性 —— 抗异常值（净值跳变、分红除权）。"""

    def __init__(self, c: float = 1.345, iters: int = 8, lam: float = 0.5):
        self.c, self.iters, self.lam = c, iters, lam

    def fit(self, X: np.ndarray, y: np.ndarray, prior: np.ndarray | None = None) -> np.ndarray:
        T, n = X.shape
        prior = np.zeros(n) if prior is None else prior
        beta = prior.copy()
        for _ in range(self.iters):
            resid = y - X @ beta
            sigma = 1.4826 * np.median(np.abs(resid - np.median(resid))) + 1e-9
            z = resid / sigma
            wgt = np.where(np.abs(z) <= self.c, 1.0, self.c / np.abs(z))
            A = X.T @ (X * wgt[:, None]) + self.lam * np.eye(n)
            b = X.T @ (wgt * y) + self.lam * prior
            try:
                beta = np.linalg.solve(A, b)
            except np.linalg.LinAlgError:
                break
            beta = np.clip(beta, 0.0, None)
        return beta


# --------------------------------------------------------------------------- #
# 5. 在线集成（Hedge / Exp3）
# --------------------------------------------------------------------------- #
class OnlineEnsemble:
    """Hedge 算法：对 K 个模型维护权重，按指数损失更新。

        w_k,t+1 ∝ w_k,t · exp(−η · loss_k,t)

    理论上后悔界 O(√(T log K))，是**自适应模型选择**的标准做法，
    比"固定选一个模型"稳健得多。
    """

    def __init__(self, k: int, eta: float = 0.05):
        self.k, self.eta = k, eta
        self.w = np.ones(k) / k
        self.hist: list[np.ndarray] = []

    def predict(self, preds: np.ndarray) -> float:
        return float(self.w @ preds)

    def update(self, preds: np.ndarray, actual: float, scale: float = 1.0) -> None:
        loss = np.abs(preds - actual) / max(scale, 1e-9)
        w = self.w * np.exp(-self.eta * np.clip(loss, 0, 10))
        s = w.sum()
        self.w = w / s if s > 0 else np.ones(self.k) / self.k
        self.hist.append(self.w.copy())


# --------------------------------------------------------------------------- #
# 6. 自适应窗口
# --------------------------------------------------------------------------- #
class AdaptiveWindow:
    """在候选窗口长度中，用**尾部表现**（最近 M 天滚动残差）动态选最优。

    比固定 60 天更好：市场结构变化时自动缩短，平稳时自动加长。
    """

    def __init__(self, candidates: tuple[int, ...] = (20, 40, 60, 90, 120),
                 lookback: int = 30):
        self.candidates = candidates
        self.lookback = lookback
        self.err: dict[int, list[float]] = {c: [] for c in candidates}
        self.best = candidates[len(candidates) // 2]

    def choose(self) -> int:
        scores = {}
        for c in self.candidates:
            e = self.err[c][-self.lookback:]
            if len(e) >= max(8, self.lookback // 3):
                scores[c] = float(np.mean(e))
        if scores:
            self.best = min(scores, key=scores.get)
        return self.best

    def record(self, window: int, err: float) -> None:
        self.err.setdefault(window, []).append(err)


# --------------------------------------------------------------------------- #
# 7. 变点检测（CUSUM）
# --------------------------------------------------------------------------- #
class ChangePointDetector:
    """CUSUM：检测残差均值是否发生结构性偏移（= 基金调仓）。

    触发后应：重置卡尔曼/RLS 协方差、缩短窗口、或标记"持仓可能已变"。
    """

    def __init__(self, threshold: float = 4.0, drift: float = 0.5):
        self.threshold, self.drift = threshold, drift
        self.pos = self.neg = 0.0
        self.last_change: int | None = None

    def update(self, err: float, sigma: float, t: int) -> bool:
        z = err / max(sigma, 1e-9)
        self.pos = max(0.0, self.pos + z - self.drift)
        self.neg = max(0.0, self.neg - z - self.drift)
        if self.pos > self.threshold or self.neg > self.threshold:
            self.pos = self.neg = 0.0
            self.last_change = t
            return True
        return False
