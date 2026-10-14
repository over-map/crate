import Testing
@testable import CrateKit

@Suite("StyleShade and StyleLabel")
struct StyleTests {
    /// Expected hashes computed by hand from the FNV-1a definition (offset 0x811C9DC5, prime 0x01000193).
    @Test func fnv1aKnownValues() {
        #expect(StyleShade.fnv1a32("") == 0x811C_9DC5)
        #expect(StyleShade.fnv1a32("a") == 0xE40C_292C) // published FNV-1a test vector
        #expect(StyleShade.fnv1a32("Electronic---Techno") == 705_347_083)
        #expect(StyleShade.fnv1a32("Electronic---Breakbeat") == 2_324_854_095)
        #expect(StyleShade.fnv1a32("Funk / Soul---Funk") == 1_241_154_464)
    }

    @Test func shades() {
        #expect(StyleShade.of("Electronic---Techno") == .base)    // 705347083 % 3 = 1
        #expect(StyleShade.of("Electronic---Breakbeat") == .light) // 2324854095 % 3 = 0
        #expect(StyleShade.of("Funk / Soul---Funk") == .deep)      // 1241154464 % 3 = 2
        #expect(StyleShade.of("Electronic---Drum n Bass") == .deep)
        #expect(StyleShade.of("Electronic---Techno") == StyleShade.of("Electronic---Techno"))
    }

    @Test func labelSplitAndAliases() {
        let label = StyleLabel(canonical: "Funk / Soul---Funk", aliases: ["Funk / Soul": "Funk & Soul"])
        #expect(label.genre == "Funk / Soul")
        #expect(label.style == "Funk")
        #expect(label.genreDisplay == "Funk & Soul")
        #expect(label.display == "Funk · Funk & Soul")
        #expect(StyleLabel.genre(of: "Electronic---Drum n Bass") == "Electronic")
        #expect(StyleLabel(canonical: "Non-Music").style == "Non-Music")
        #expect(StyleLabel.canonical(genre: "Rock", style: "Shoegaze") == "Rock---Shoegaze")
    }

    @Test func moodSymbols() {
        #expect(Mood.symbol(for: "dark") == "moon.fill")
        #expect(Mood.symbol(for: "hypnotic") == "circle.dotted.circle")
        #expect(Mood.symbol(for: "epic") == "tag")
        #expect(!Mood.isChip("vocal"))
    }
}
