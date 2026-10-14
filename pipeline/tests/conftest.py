import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def spec_dir() -> Path:
    return ROOT / "spec"


def load_fixture(name: str):
    return json.loads((FIXTURES / name).read_text())
