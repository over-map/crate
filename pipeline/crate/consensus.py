"""Style consensus: do the audio model, Discogs, Last.fm and Beatport agree on what a track is?

Each source speaks its own vocabulary (Discogs's 400 styles, Last.fm free tags, Beatport genres), so every label
is mapped onto a small set of style *families*. Each source then votes for the families it names, and the result is:

    tracks[id].consensus = {"family": "techno", "votes": 3, "sources": 4, "audio_agrees": true,
                            "by_source": {"audio": ["techno"], "discogs": ["techno", "ambient"], ...}}

``votes`` = how many sources name the winning family, ``sources`` = how many sources had an opinion. The audio
model's family breaks ties. This is a confidence signal: it never overrides the audio style used by queries.
"""

from __future__ import annotations

from collections import Counter

from . import tags as tagmod
from .library import effective

# family -> (Discogs/audio style names, Last.fm/Beatport normalised tag names)
FAMILIES: dict[str, tuple[set[str], set[str]]] = {
    "techno": (
        {
            "Techno",
            "Dub Techno",
            "Minimal",
            "Deep Techno",
            "Acid",
            "Hard Techno",
            "Schranz",
            "Ambient Techno",
            "Minimal Techno",
            "Industrial",
        },
        {
            "techno",
            "minimal techno",
            "dub techno",
            "deep techno",
            "acid techno",
            "hard techno",
            "detroit techno",
            "minimal",
            "techno (peak time driving)",
            "techno (raw deep hypnotic)",
            "minimal deep tech",
        },
    ),
    "house": (
        {
            "House",
            "Deep House",
            "Tech House",
            "Progressive House",
            "Acid House",
            "Garage House",
            "Afro House",
            "Minimal House",
            "Microhouse",
            "Hard House",
            "Italo House",
            "Funky House",
            "Tropical House",
            "Euro House",
            "Beatdown",
        },
        {
            "house",
            "deep house",
            "tech house",
            "progressive house",
            "organic house",
            "afro house",
            "melodic house and techno",
            "jackin house",
            "funky house",
            "microhouse",
            "minimal house",
            "chicago house",
            "melodic house",
        },
    ),
    "downtempo": (
        {
            "Ambient",
            "Downtempo",
            "Trip Hop",
            "Chillwave",
            "New Age",
            "Drone",
            "Dark Ambient",
            "Berlin-School",
            "Balearic",
            "Neo-Classical",
            "Modern Classical",
        },
        {
            "ambient",
            "downtempo",
            "chillout",
            "trip hop",
            "lounge",
            "balearic",
            "new age",
            "drone",
            "ambient experimental",
            "neoclassical",
            "modern classical",
        },
    ),
    "electronica": (
        {
            "IDM",
            "Glitch",
            "Experimental",
            "Abstract",
            "Leftfield",
            "Electro",
            "Breakcore",
            "Musique Concrète",
            "Vaporwave",
            "Dungeon Synth",
        },
        {"electronica", "idm", "glitch", "experimental", "leftfield", "experimental electronic", "warp"},
    ),
    "disco": (
        {
            "Disco",
            "Nu-Disco",
            "Italo-Disco",
            "Boogie",
            "Hi NRG",
            "Space Disco",
            "Disco Polo",
            "Euro-Disco",
            "Italodance",
            "Freestyle",
        },
        {"disco", "nu disco", "italo disco", "space disco", "boogie", "nu disco disco", "cosmic disco"},
    ),
    "indie dance": (
        {
            "Synth-pop",
            "New Wave",
            "Darkwave",
            "Electroclash",
            "EBM",
            "Indie Pop",
            "Dance-pop",
            "Cold Wave",
            "Minimal Wave",
            "New Beat",
            "Synthwave",
        },
        {
            "indie dance",
            "synthpop",
            "synth pop",
            "new wave",
            "darkwave",
            "electroclash",
            "ebm",
            "indie pop",
            "nu rave",
            "coldwave",
        },
    ),
    "trance": (
        {"Trance", "Psy-Trance", "Progressive Trance", "Goa Trance", "Tech Trance", "Hard Trance"},
        {
            "trance",
            "psytrance",
            "psy trance",
            "goa",
            "progressive trance",
            "trance (raw deep hypnotic)",
            "raw trance",
            "trance (main floor)",
        },
    ),
    "breaks & bass": (
        {
            "Breakbeat",
            "Drum n Bass",
            "Dubstep",
            "UK Garage",
            "Bass Music",
            "Jungle",
            "Breaks",
            "Grime",
            "Future Jazz",
            "Juke",
            "Footwork",
            "Speed Garage",
            "Hardcore",
            "Progressive Breaks",
            "Big Beat",
            "Broken Beat",
            "Hardstyle",
            "Happy Hardcore",
        },
        {
            "breakbeat",
            "breaks",
            "drum and bass",
            "dubstep",
            "uk garage",
            "future garage",
            "bass",
            "jungle",
            "grime",
            "breaks breakbeat uk bass",
            "140 deep dubstep grime",
            "uk garage bassline",
            "bass club",
            "broken techno",
        },
    ),
    "hip hop": (set(), {"hip hop", "boom bap", "trip hop rap", "jazz rap", "g funk"}),
    "rock & indie": (
        set(),
        {"rock", "indie", "indie rock", "alternative", "post punk", "psychedelic rock", "krautrock", "shoegaze"},
    ),
    "jazz": (set(), {"jazz", "nu jazz", "jazz funk", "spiritual jazz", "bossa nova"}),
    "latin & brazilian": (set(), {"latin", "brazilian", "mpb", "samba", "bossa nova", "salsa", "cumbia", "latin electronic"}),
    "reggae & dub": ({"Dub"}, {"reggae", "dub", "roots reggae", "dancehall", "lovers rock"}),
    "funk & soul": (set(), {"funk", "soul", "afrobeat", "afro funk", "r and b", "rnb"}),
    "pop & cantautori": (set(), {"pop", "cantautori", "singer songwriter", "italian pop", "chanson", "schlager", "dance pop"}),
    "folk & world": (set(), {"folk", "world", "african", "country", "afro", "balkan"}),
    "classical": (set(), {"classical", "contemporary classical"}),
}

# Non-electronic genres: a Discogs/audio *genre* maps straight to a family.
GENRE_FAMILY = {
    "Hip Hop": "hip hop",
    "Rock": "rock & indie",
    "Jazz": "jazz",
    "Latin": "latin & brazilian",
    "Reggae": "reggae & dub",
    "Funk / Soul": "funk & soul",
    "Pop": "pop & cantautori",
    "Folk, World, & Country": "folk & world",
    "Classical": "classical",
}

_STYLE_TO_FAMILY = {s: f for f, (styles, _) in FAMILIES.items() for s in styles}
_TAG_TO_FAMILY = {tagmod.normalise(t): f for f, (_, ts) in FAMILIES.items() for t in ts}


def family_of_style(canonical: str) -> str | None:
    """``"Electronic---Dub Techno"`` -> ``"techno"``; non-electronic styles fall back to their genre."""
    genre, _, style = canonical.partition("---")
    return _STYLE_TO_FAMILY.get(style) if genre == "Electronic" else GENRE_FAMILY.get(genre) or _STYLE_TO_FAMILY.get(style)


def families_of_tags(raw: list[str]) -> set[str]:
    return {f for f in (_TAG_TO_FAMILY.get(tagmod.normalise(t)) for t in raw) if f}


def votes(track: dict, override: dict | None, artist_tags: dict, discogs: dict, beatport: dict, tid: str) -> dict[str, set[str]]:
    """Families each source names for one track (sources without an opinion are left out)."""
    out: dict[str, set[str]] = {}
    e = effective(track, override)
    if e.primary_style and (f := family_of_style(e.primary_style)):
        out["audio"] = {f}
    d = discogs.get(tid) or {}
    if d.get("found"):
        fams = {f for s in d.get("styles") or [] if (f := _STYLE_TO_FAMILY.get(s))}
        fams |= {f for g in d.get("genres") or [] if (f := GENRE_FAMILY.get(g))}
        if fams:
            out["discogs"] = fams
    lf = [x for a in track.get("artists") or [] for x in (artist_tags.get(a) or {}).get("lastfm") or []]
    if fams := families_of_tags(lf[:8]):  # an artist's top tags only: further down they get noisy
        out["lastfm"] = fams
    if bp := beatport.get(tid):
        if fams := families_of_tags(bp):
            out["beatport"] = fams
    return out


def decide(by_source: dict[str, set[str]]) -> dict | None:
    if not by_source:
        return None
    count = Counter(f for fams in by_source.values() for f in fams)
    audio = next(iter(by_source.get("audio", set())), None)
    top = max(count.values())
    winners = sorted(f for f, c in count.items() if c == top)
    family = audio if audio in winners else winners[0]
    return {
        "family": family,
        "votes": count[family],
        "sources": len(by_source),
        "audio_agrees": audio == family if audio else None,
        "by_source": {k: sorted(v) for k, v in sorted(by_source.items())},
    }


def apply(library: dict, overrides: dict, artist_tags: dict, discogs: dict, beatport: dict) -> Counter:
    """Write ``tracks[id].consensus`` for every track. Returns a histogram of ``votes/sources``."""
    hist: Counter = Counter()
    ov = (overrides or {}).get("tracks") or {}
    arts, dc, bp = artist_tags.get("artists", {}), discogs.get("tracks", {}), beatport.get("tracks", {})
    for tid, t in library.get("tracks", {}).items():
        c = decide(votes(t, ov.get(tid), arts, dc, bp, tid))
        if c is None:
            t.pop("consensus", None)
            continue
        t["consensus"] = c
        hist[f"{c['votes']}/{c['sources']}"] += 1
    return hist


def family(track: dict, override: dict | None) -> str | None:
    """The track's family as used by queries: the user's override, else the majority when ≥ 2 outside sources outvote
    the audio, else the audio style's family, else the consensus family (no audio opinion)."""
    if override and override.get("family"):
        return override["family"]
    if override and override.get("styles_add"):
        f = family_of_style(override["styles_add"][0])
        if f:
            return f
    c = track.get("consensus")
    audio = None
    e = effective(track, override)
    if e.primary_style:
        audio = family_of_style(e.primary_style)
    if c and c.get("audio_agrees") is False and c.get("votes", 0) >= 2:
        return c["family"]
    return audio or (c or {}).get("family")


def assign_families(library: dict, overrides: dict) -> int:
    """Write ``tracks[id].family`` for every track. Returns how many changed."""
    ov = (overrides or {}).get("tracks") or {}
    changed = 0
    for tid, t in library.get("tracks", {}).items():
        f = family(t, ov.get(tid))
        if t.get("family") != f:
            if f is None:
                t.pop("family", None)
            else:
                t["family"] = f
            changed += 1
    return changed


FAMILY_ORDER = list(FAMILIES)
# UI names (design: "Genre" in the UI, sentence case).
FAMILY_NAMES = {
    "techno": "Techno",
    "house": "House",
    "downtempo": "Downtempo",
    "electronica": "Electronica",
    "disco": "Disco",
    "indie dance": "Indie dance",
    "trance": "Trance",
    "breaks & bass": "Breaks & bass",
    "hip hop": "Hip hop",
    "rock & indie": "Rock & indie",
    "jazz": "Jazz",
    "latin & brazilian": "Latin & Brazilian",
    "reggae & dub": "Reggae & dub",
    "funk & soul": "Funk & soul",
    "pop & cantautori": "Pop & cantautori",
    "folk & world": "Folk & world",
    "classical": "Classical",
}
