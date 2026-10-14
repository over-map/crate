from crate import consensus


def test_family_mapping():
    assert consensus.family_of_style("Electronic---Dub Techno") == "techno"
    assert consensus.family_of_style("Electronic---Nu-Disco") == "disco"
    assert consensus.family_of_style("Hip Hop---Boom Bap") == "hip hop"
    assert consensus.families_of_tags(["Techno (Raw / Deep / Hypnotic)", "Italy", "Deep-House"]) == {"techno", "house"}


def test_decide_counts_votes_and_breaks_ties_with_audio():
    c = consensus.decide(
        {"audio": {"techno"}, "discogs": {"techno", "downtempo"}, "lastfm": {"downtempo"}, "beatport": {"electronica"}}
    )
    assert c["family"] == "techno" and c["votes"] == 2 and c["sources"] == 4 and c["audio_agrees"]
    c = consensus.decide({"audio": {"house"}, "discogs": {"disco"}, "lastfm": {"disco"}})
    assert c["family"] == "disco" and c["votes"] == 2 and c["audio_agrees"] is False
    assert consensus.decide({}) is None


def test_apply_end_to_end():
    lib = {"tracks": {"a": {"artists": ["X"], "auto": {"styles": {"Electronic---Techno": 0.8}}}}}
    at = {"artists": {"X": {"lastfm": ["dub techno", "ambient"]}}}
    dc = {"tracks": {"a": {"found": True, "styles": ["Dub Techno"], "genres": ["Electronic"]}}}
    bp = {"tracks": {"a": ["Techno (Raw / Deep / Hypnotic)"]}}
    hist = consensus.apply(lib, {}, at, dc, bp)
    assert lib["tracks"]["a"]["consensus"]["family"] == "techno" and hist == {"4/4": 1}
