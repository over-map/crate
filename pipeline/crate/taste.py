"""Taste profile: k-means clusters over library vectors (build spec §8.2), plus a tiny feedback model.

Mixed taste stays separate per cluster and is never averaged. Plain numpy, no ML framework.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

K_RANGE = range(8, 25)


@dataclass
class Taste:
    centroids: np.ndarray  # k × d, unit length
    weights: np.ndarray  # k, √size normalised to sum 1
    sizes: np.ndarray


def kmeans(x: np.ndarray, k: int, rng: np.random.Generator, iters: int = 50) -> np.ndarray:
    """Labels from k-means++ initialisation and Lloyd iterations."""
    n = len(x)
    centers = [x[rng.integers(n)]]
    for _ in range(1, k):
        d2 = np.min(((x[:, None, :] - np.array(centers)[None]) ** 2).sum(-1), axis=1)
        centers.append(x[rng.choice(n, p=d2 / d2.sum())] if d2.sum() > 0 else x[rng.integers(n)])
    c = np.array(centers)
    labels = np.zeros(n, dtype=int)
    for _ in range(iters):
        new = np.argmin(((x[:, None, :] - c[None]) ** 2).sum(-1), axis=1)
        if np.array_equal(new, labels) and _ > 0:
            break
        labels = new
        for j in range(k):
            members = x[labels == j]
            if len(members):
                c[j] = members.mean(axis=0)
    return labels


def silhouette(x: np.ndarray, labels: np.ndarray, sample: int = 1500, seed: int = 0) -> float:
    """Mean silhouette on a random sample (cosine distance on unit vectors)."""
    rng = np.random.default_rng(seed)
    idx = rng.choice(len(x), min(sample, len(x)), replace=False)
    xs, ls = x[idx], labels[idx]
    d = 1.0 - xs @ xs.T
    scores = []
    for i in range(len(xs)):
        same = ls == ls[i]
        if same.sum() <= 1:
            scores.append(0.0)
            continue
        a = d[i, same].sum() / (same.sum() - 1)
        b = min(d[i, ls == j].mean() for j in np.unique(ls) if j != ls[i])
        scores.append((b - a) / max(a, b) if max(a, b) > 0 else 0.0)
    return float(np.mean(scores))


def profile(x: np.ndarray, seed: int = 0, k_range: range = K_RANGE) -> tuple[Taste, np.ndarray]:
    """Pick k by silhouette within ``k_range`` (clamped to the data size). Returns (taste, labels)."""
    rng = np.random.default_rng(seed)
    ks = [k for k in k_range if k < len(x)] or [max(1, min(len(x), 2))]
    best: tuple[float, np.ndarray] | None = None
    for k in ks:
        labels = kmeans(x, k, rng)
        s = silhouette(x, labels) if k > 1 else 0.0
        if best is None or s > best[0]:
            best = (s, labels)
    assert best is not None
    labels = best[1]
    ks_used = np.unique(labels)
    cents, sizes = [], []
    for j in ks_used:
        m = x[labels == j].mean(axis=0)
        cents.append(m / (np.linalg.norm(m) or 1.0))
        sizes.append((labels == j).sum())
    sizes_a = np.array(sizes)
    w = np.sqrt(sizes_a)
    return Taste(np.array(cents), w / w.sum(), sizes_a), np.searchsorted(ks_used, labels)


def logistic(x: np.ndarray, y: np.ndarray, sample_weight: np.ndarray, l2: float = 1.0, iters: int = 500) -> np.ndarray:
    """L2-regularised logistic regression by gradient descent. Returns weights with bias last."""
    xb = np.hstack([x, np.ones((len(x), 1))])
    w = np.zeros(xb.shape[1])
    lr = 0.5
    for _ in range(iters):
        p = 1 / (1 + np.exp(-xb @ w))
        grad = xb.T @ ((p - y) * sample_weight) / sample_weight.sum() + l2 * np.r_[w[:-1], 0] / len(x)
        w -= lr * grad
    return w


def predict(w: np.ndarray, x: np.ndarray) -> np.ndarray:
    return 1 / (1 + np.exp(-(np.hstack([x, np.ones((len(x), 1))]) @ w)))
