import numpy as np

from crate import vectors


def test_pca_project_unit_and_roundtrip(tmp_path):
    rng = np.random.default_rng(0)
    emb = rng.normal(size=(200, 1280)).astype(np.float32)
    mean, comps = vectors.fit_pca(emb)
    assert comps.shape == (32, 1280)
    v = vectors.project(emb[0], mean, comps)
    assert abs(np.linalg.norm(v) - 1) < 1e-5
    vectors.save_pca(tmp_path / "pca.npz", mean, comps)
    m2, c2 = vectors.load_pca(tmp_path / "pca.npz")
    assert np.allclose(c2, comps)


def test_store_roundtrip_float16(tmp_path):
    s = vectors.VectorStore({"b": np.ones(32) / np.sqrt(32), "a": np.eye(32)[0]})
    assert s.save(tmp_path)
    assert not s.save(tmp_path)
    assert (tmp_path / "vectors.bin").stat().st_size == 2 * 32 * 2
    s2 = vectors.VectorStore.load(tmp_path)
    assert list(sorted(s2.rows)) == ["a", "b"]
    assert np.allclose(s2.rows["a"], np.eye(32)[0], atol=1e-3)


def test_most_similar_excludes_seed():
    s = vectors.VectorStore(
        {
            "x": np.eye(32)[0],
            "near": vectors.project(np.eye(32)[0] + 0.1 * np.eye(32)[1], np.zeros(32), np.eye(32)),
            "far": np.eye(32)[5],
        }
    )
    assert vectors.most_similar(s, "x", 2) == ["near", "far"]
