"""``crate report``: docs/tag-quality-report.md for the M1/M2 gates (build spec §6.7)."""

from __future__ import annotations

import random
from collections import Counter
from typing import Any

from .library import effective, genre_of

LOW_CONF = 0.5


def _link(tid: str) -> str:
    return f"https://open.spotify.com/track/{tid}"


def _hist(values: list[float], edges: list[float]) -> list[tuple[str, int]]:
    out = []
    for lo, hi in zip(edges, edges[1:], strict=False):
        out.append((f"{lo:g}–{hi:g}", sum(lo <= v < hi for v in values)))
    return out


def _bar(n: int, top: int, width: int = 30) -> str:
    return "█" * (round(n / top * width) if top else 0)


def render(library: dict, overrides: dict, seed: int = 0, ground_truth: dict | None = None) -> str:
    tracks: dict[str, Any] = library.get("tracks") or {}
    ov = (overrides or {}).get("tracks") or {}
    eff = {tid: effective(t, ov.get(tid)) for tid, t in tracks.items()}
    status = Counter(t.get("status") for t in tracks.values())
    n = len(tracks) or 1
    lines = [
        "# Tag quality report",
        "",
        f"Generated from `data/library.json` ({library.get('generated_at', '?')}).",
        "",
        "## Coverage",
        "",
        "| Status | Tracks | Share |",
        "|---|---:|---:|",
        *[f"| {s} | {c} | {c / n:.0%} |" for s, c in status.most_common()],
        "",
    ]
    styles = Counter(e.primary_style for e in eff.values() if e.primary_style)
    genres = Counter(e.primary_genre for e in eff.values() if e.primary_genre)
    lines += [
        "## Genres (strongest style's parent)",
        "",
        "| Genre | Tracks |",
        "|---|---:|",
        *[f"| {g} | {c} |" for g, c in genres.most_common()],
        "",
    ]
    lines += ["## Top 40 styles", "", "| Style | Genre | Tracks | Examples |", "|---|---|---:|---|"]
    rng = random.Random(seed)
    for s, c in styles.most_common(40):
        ex = [tid for tid, e in eff.items() if e.primary_style == s]
        rng.shuffle(ex)
        names = ", ".join(f"[{tracks[t].get('title')}]({_link(t)})" for t in ex[:3])
        lines.append(f"| {s.split('---', 1)[1]} | {genre_of(s)} | {c} | {names} |")
    lines.append("")

    bpms = [e.bpm for e in eff.values() if e.bpm]
    energies = [e.energy for e in eff.values() if e.energy]
    keys_ = Counter(e.key for e in eff.values() if e.key)
    for title, hist in (
        ("BPM", _hist(bpms, [40, 70, 80, 90, 100, 110, 120, 125, 130, 135, 140, 150, 160, 170, 180, 250])),
        ("Energy", [(str(i), energies.count(i)) for i in range(1, 11)]),
        ("Key", sorted(keys_.items(), key=lambda kv: (int(kv[0][:-1]), kv[0][-1]))),
    ):
        top = max((c for _, c in hist), default=0)
        lines += [f"## {title}", "", "```", *[f"{lbl:>8} {c:5d} {_bar(c, top)}" for lbl, c in hist], "```", ""]

    lines += [
        "## 40 random tracks",
        "",
        "| Track | Style | BPM | Key | Energy | Moods | Vocal |",
        "|---|---|---:|---|---:|---|---|",
    ]
    tagged = [tid for tid, t in tracks.items() if t.get("status") == "tagged"]
    for tid in rng.sample(tagged, min(40, len(tagged))):
        e, t = eff[tid], tracks[tid]
        lines.append(
            f"| [{t.get('title')} — {', '.join(t.get('artists') or [])}]({_link(tid)}) | "
            f"{(e.primary_style or '').split('---')[-1]} | {e.bpm} | {e.key} | {e.energy} | "
            f"{', '.join(sorted(e.moods or []))} | {'vocal' if e.vocal else 'instr.'} |"
        )
    lines.append("")

    low = [
        tid
        for tid, t in tracks.items()
        if t.get("status") == "tagged"
        and ((t["auto"].get("bpm_conf") or 1) < LOW_CONF or (t["auto"].get("key_conf") or 1) < LOW_CONF)
    ]
    lines += [f"## Low-confidence tracks ({len(low)})", "", "BPM or key confidence under 0.5.", ""]
    lines += [
        f"- [{tracks[t].get('title')}]({_link(t)}): BPM {tracks[t]['auto'].get('bpm')} "
        f"({tracks[t]['auto'].get('bpm_conf')}), key {tracks[t]['auto'].get('key')} ({tracks[t]['auto'].get('key_conf')})"
        for t in low[:60]
    ]
    lines.append("")

    if ground_truth:
        lines += ground_truth_section(ground_truth, eff)
    return "\n".join(lines)


def ground_truth_section(gt: dict, eff: dict) -> list[str]:
    """Accuracy against the user's 30 hand-labelled tracks (spec §10 targets)."""
    rows = [r for r in gt.get("tracks", []) if r["id"] in eff and r.get("bpm") and r.get("energy") and r.get("style")]
    if not rows:
        return []
    bpm_ok = sum(1 for r in rows if eff[r["id"]].bpm and abs(eff[r["id"]].bpm - r["bpm"]) <= 0.02 * r["bpm"])
    en_ok = sum(1 for r in rows if eff[r["id"]].energy and abs(eff[r["id"]].energy - r["energy"]) <= 2)

    def same_style(found: str | None, want: str) -> bool:
        # "Techno" or "Electronic---Techno" both work in ground_truth.json
        return bool(found) and (found == want or ("---" not in want and found.split("---", 1)[1].casefold() == want.casefold()))

    st_ok = sum(1 for r in rows if same_style(eff[r["id"]].primary_style, r["style"]))
    n = len(rows)
    return [
        "## Ground truth",
        "",
        "| Measure | Result | Target |",
        "|---|---:|---:|",
        f"| BPM within ±2 % | {bpm_ok}/{n} ({bpm_ok / n:.0%}) | ≥ 90 % |",
        f"| Energy within ±2 | {en_ok}/{n} ({en_ok / n:.0%}) | ≥ 80 % |",
        f"| Style is the strongest style | {st_ok}/{n} ({st_ok / n:.0%}) | ≥ 75 % |",
        "",
    ]
