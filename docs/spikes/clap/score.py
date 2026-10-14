"""Spike: rank the library for free-text keywords with CLAP text embeddings.

Usage: python score.py <library.json> <clap_emb.npz> <top_n> "keyword" ["keyword" ...]
Each keyword is embedded through a few prompt templates (averaged), then tracks are ranked by cosine.
"""

import json
import sys
from pathlib import Path

import numpy as np
import torch
from transformers import ClapModel, ClapProcessor

MODEL = "laion/larger_clap_general"
REVISION = "ada0c23a36c4e8582805bb38fec3905903f18b41"
TEMPLATES = ["{}", "{} music", "music that feels {}", "a {} song"]

lib = json.loads(Path(sys.argv[1]).read_text())["tracks"]
d = np.load(sys.argv[2])
ids, emb = d["ids"].tolist(), d["emb"]
top_n = int(sys.argv[3])
keywords = sys.argv[4:]

model = ClapModel.from_pretrained(MODEL, revision=REVISION).eval()
proc = ClapProcessor.from_pretrained(MODEL, revision=REVISION)


def text_vec(kw: str) -> np.ndarray:
    inputs = proc(text=[t.format(kw) for t in TEMPLATES], return_tensors="pt", padding=True)
    with torch.no_grad():
        e = model.get_text_features(**inputs)
    e = e.pooler_output if hasattr(e, "pooler_output") else e
    v = torch.nn.functional.normalize(e, dim=-1).mean(0)
    return (v / v.norm()).numpy()


def name(tid: str) -> str:
    t = lib[tid]
    return f"{t['title'][:34]} — {', '.join(t['artists'])[:26]}"


results = {}
for kw in keywords:
    sims = emb @ text_vec(kw)
    order = np.argsort(-sims)
    z = (sims - sims.mean()) / sims.std()
    results[kw] = [ids[i] for i in order]
    print(f"\n## {kw}   (spread: top z={z[order[0]]:.1f}, #15 z={z[order[14]]:.1f})")
    for r, i in enumerate(order[:top_n], 1):
        print(f"  {r:2d}. {name(ids[i])}")
Path("ranked.json").write_text(json.dumps(results))
