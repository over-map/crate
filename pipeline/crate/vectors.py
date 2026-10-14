"""Sound-similarity vectors: 1280-d Discogs-EffNet embeddings -> fixed PCA basis -> 32-d float16, L2-normalised.

The PCA basis (``data/pca.npz``) is fitted once on the backfilled library and then kept fixed, so a new
track or a Weekly Mix candidate projects into the same space without re-fitting. Re-fit only on purpose
(``crate vectors --fit``) because it changes every vector.

``data/vectors.bin`` holds the rows as little-endian float16; ``data/vectors.idx.json`` lists the track IDs.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

DIMS = 32


def fit_pca(embeddings: np.ndarray, dims: int = DIMS) -> tuple[np.ndarray, np.ndarray]:
    """Return (mean, components[dims × 1280]) from an N × 1280 matrix."""
    mean = embeddings.mean(axis=0)
    _, _, vt = np.linalg.svd(embeddings - mean, full_matrices=False)
    return mean.astype(np.float32), vt[:dims].astype(np.float32)


def project(embedding: np.ndarray, mean: np.ndarray, components: np.ndarray) -> np.ndarray:
    v = components @ (np.asarray(embedding, dtype=np.float32) - mean)
    n = np.linalg.norm(v)
    return (v / n if n > 0 else v).astype(np.float32)


def save_pca(path: Path, mean: np.ndarray, components: np.ndarray) -> None:
    np.savez(path, mean=mean, components=components)


def load_pca(path: Path) -> tuple[np.ndarray, np.ndarray] | None:
    if not path.exists():
        return None
    d = np.load(path)
    return d["mean"], d["components"]


class VectorStore:
    """Track ID -> 32-d unit vector, persisted as vectors.bin + vectors.idx.json."""

    def __init__(self, rows: dict[str, np.ndarray] | None = None):
        self.rows = rows or {}

    @classmethod
    def load(cls, data_dir: Path) -> VectorStore:
        idx_path, bin_path = data_dir / "vectors.idx.json", data_dir / "vectors.bin"
        if not idx_path.exists():
            return cls()
        ids = json.loads(idx_path.read_text())
        mat = np.frombuffer(bin_path.read_bytes(), dtype="<f2").reshape(len(ids), DIMS).astype(np.float32)
        return cls(dict(zip(ids, mat, strict=True)))

    def save(self, data_dir: Path, keep: set[str] | None = None) -> bool:
        """Write both files (rows not in ``keep`` are dropped). Returns True if anything changed."""
        ids = sorted(i for i in self.rows if keep is None or i in keep)
        mat = np.stack([self.rows[i] for i in ids]).astype("<f2") if ids else np.zeros((0, DIMS), "<f2")
        blob, idx = mat.tobytes(), json.dumps(ids) + "\n"
        bin_path, idx_path = data_dir / "vectors.bin", data_dir / "vectors.idx.json"
        if bin_path.exists() and idx_path.exists() and bin_path.read_bytes() == blob and idx_path.read_text() == idx:
            return False
        bin_path.write_bytes(blob)
        idx_path.write_text(idx)
        return True

    def matrix(self, ids: list[str]) -> np.ndarray:
        return np.stack([self.rows[i] for i in ids]) if ids else np.zeros((0, DIMS), np.float32)


def most_similar(store: VectorStore, seed: str, n: int = 100) -> list[str]:
    """Top ``n`` tracks by cosine similarity to ``seed`` (excluding it). Same as the app's "More like this"."""
    ids = [i for i in store.rows if i != seed]
    if seed not in store.rows or not ids:
        return []
    sims = store.matrix(ids) @ store.rows[seed]
    order = np.argsort(-sims, kind="stable")[:n]
    return [ids[i] for i in order]
