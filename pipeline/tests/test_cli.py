from crate.cli import save_env


def test_save_env_replaces_in_place(tmp_path):
    p = tmp_path / ".env"
    p.write_text("A=1\nSPOTIFY_REFRESH_TOKEN=\nB=2\n")
    save_env(p, "SPOTIFY_REFRESH_TOKEN", "tok")
    assert p.read_text() == "A=1\nSPOTIFY_REFRESH_TOKEN=tok\nB=2\n"
    assert p.stat().st_mode & 0o777 == 0o600


def test_save_env_appends(tmp_path):
    p = tmp_path / ".env"
    p.write_text("A=1\n")
    save_env(p, "K", "v")
    assert p.read_text() == "A=1\nK=v\n"
