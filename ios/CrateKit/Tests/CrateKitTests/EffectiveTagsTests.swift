import Testing
@testable import CrateKit

@Suite("Override merge")
struct EffectiveTagsTests {
    let auto = AutoTags(
        styles: ["Electronic---Breakbeat": 0.58, "Electronic---Techno": 0.44, "Rock---Industrial": 0.3],
        bpm: 138, key: "8A", energy: 7,
        moods: ["dark": 0.81, "energetic": 0.66, "hypnotic": 0.4], vocal: 0.12
    )

    @Test func autoOnly() {
        let t = EffectiveTags(auto: auto, override: nil)
        #expect(t.primaryStyle == "Electronic---Breakbeat")
        #expect(t.primaryGenre == "Electronic")
        #expect(t.bpm == 138 && t.key == "8A" && t.energy == 7)
        #expect(t.moods == ["dark", "energetic"])
        #expect(t.vocal == false)
    }

    @Test func scalarOverridesWinFieldByField() {
        let t = EffectiveTags(auto: auto, override: TrackOverride(bpm: 140, key: "9A", vocal: true))
        #expect(t.bpm == 140 && t.key == "9A" && t.vocal == true)
        #expect(t.energy == 7, "fields not overridden keep the auto value")
    }

    @Test func moodSets() {
        let t = EffectiveTags(auto: auto, override: TrackOverride(moodsAdd: ["hypnotic"], moodsRemove: ["dark"]))
        #expect(t.moods == ["energetic", "hypnotic"])
        let untagged = EffectiveTags(auto: nil, override: TrackOverride(moodsRemove: ["dark"]))
        #expect(untagged.moods == nil, "no auto moods and nothing added = unknown")
        let added = EffectiveTags(auto: nil, override: TrackOverride(moodsAdd: ["party"]))
        #expect(added.moods == ["party"])
    }

    @Test func addedStyleIsPrimary() {
        let t = EffectiveTags(auto: auto, override: TrackOverride(stylesAdd: ["Electronic---Acid House", "Rock---Punk"]))
        #expect(t.primaryStyle == "Electronic---Acid House")
        #expect(t.primaryGenre == "Electronic")
    }

    @Test func removedStyleAndGenreAreSkipped() {
        let noBreaks = EffectiveTags(auto: auto, override: TrackOverride(stylesRemove: ["Electronic---Breakbeat"]))
        #expect(noBreaks.primaryStyle == "Electronic---Techno")
        let noElectronic = EffectiveTags(auto: auto, override: TrackOverride(genresRemove: ["Electronic"]))
        #expect(noElectronic.primaryStyle == "Rock---Industrial")
        #expect(noElectronic.primaryGenre == "Rock")
    }

    @Test func addedGenreKeepsOnlyAMatchingStyle() {
        let rock = EffectiveTags(auto: auto, override: TrackOverride(genresAdd: ["Rock"]))
        #expect(rock.primaryGenre == "Rock")
        #expect(rock.primaryStyle == nil, "Breakbeat is not a Rock style")
        let electronic = EffectiveTags(auto: auto, override: TrackOverride(genresAdd: ["Electronic"]))
        #expect(electronic.primaryStyle == "Electronic---Breakbeat")
    }

    @Test func untaggedIsUnknownEverywhere() {
        let t = EffectiveTags(auto: nil, override: nil)
        #expect(t == EffectiveTags())
    }

    @Test func tiesBreakByLabel() {
        #expect(EffectiveTags.rankedStyles(["Jazz---Fusion": 0.4, "Funk / Soul---Funk": 0.4, "Rock---Punk": 0.5])
            == ["Rock---Punk", "Funk / Soul---Funk", "Jazz---Fusion"])
    }
}
