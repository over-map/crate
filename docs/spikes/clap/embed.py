"""Spike: CLAP audio embeddings for every tagged track (Deezer preview, 3 × 10 s windows, averaged).

Usage: python embed.py <library.json> <out.npz>
Resumable: rows already in out.npz are skipped. Previews are deleted right after use.
"""

import json
import sys
import tempfile
import time
from pathlib import Path

import httpx
import numpy as np
import soundfile as sf
import torch
from transformers import ClapModel, ClapProcessor

MODEL = "laion/larger_clap_general"
REVISION = "ada0c23a36c4e8582805bb38fec3905903f18b41"
SR = 48000

lib = json.loads(Path(sys.argv[1]).read_text())["tracks"]
out = Path(sys.argv[2])
done: dict[str, np.ndarray] = {}
if out.exists():
    d = np.load(out)
    done = dict(zip(d["ids"].tolist(), d["emb"], strict=True))

model = ClapModel.from_pretrained(MODEL, revision=REVISION).eval()
proc = ClapProcessor.from_pretrained(MODEL, revision=REVISION)
http = httpx.Client(timeout=30, follow_redirects=True)


def save() -> None:
    ids = sorted(done)
    np.savez(out, ids=np.array(ids), emb=np.stack([done[i] for i in ids]).astype(np.float32))


todo = [tid for tid, t in lib.items() if t.get("status") == "tagged" and tid not in done and (t.get("source") or {}).get("deezer_id")]
print(f"{len(done)} done, {len(todo)} to go", flush=True)
t0 = time.time()
with tempfile.TemporaryDirectory() as tmp:
    for n, tid in enumerate(todo, 1):
        try:
            meta = http.get(f"https://api.deezer.com/track/{lib[tid]['source']['deezer_id']}").json()
            if not meta.get("preview"):
                continue
            p = Path(tmp) / "a.mp3"
            p.write_bytes(http.get(meta["preview"]).content)
            audio, sr = sf.read(p, dtype="float32", always_2d=True)
            p.unlink()
            mono = audio.mean(axis=1)
            x = np.interp(np.arange(0, len(mono), sr / SR), np.arange(len(mono)), mono).astype(np.float32)
            wins = [x[i : i + 10 * SR] for i in range(0, max(1, len(x) - 10 * SR + 1), 10 * SR)][:3]
            inputs = proc(audio=wins, sampling_rate=SR, return_tensors="pt")
            with torch.no_grad():
                e = model.get_audio_features(**inputs)
            e = e.pooler_output if hasattr(e, "pooler_output") else e
            v = torch.nn.functional.normalize(e, dim=-1).mean(0)
            done[tid] = (v / v.norm()).numpy()
        except Exception as ex:  # skip and carry on
            print("skip", tid, ex, flush=True)
        if n % 50 == 0:
            save()
            print(f"{n}/{len(todo)}  {(time.time() - t0) / n:.2f}s/track", flush=True)
        time.sleep(0.12)
save()
print("done", len(done), flush=True)
