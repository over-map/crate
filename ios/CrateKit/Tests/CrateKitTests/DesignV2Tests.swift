import Foundation
import Testing
@testable import CrateKit

@Suite("Design v2 data")
struct DesignV2Tests {
    @Test func familyStyles() throws {
        let q = try JSONDecoder().decode(Query.self, from: Data(#"{"family_styles":{"house":["Electronic---Techno"]}}"#.utf8))
        #expect(q.familyStyles == ["house": ["Electronic---Techno"]] && !q.isUnconstrained)
        let houseTechno = EffectiveTags(primaryStyle: "Electronic---Techno", primaryGenre: "Electronic", family: "house")
        let technoTechno = EffectiveTags(primaryStyle: "Electronic---Techno", primaryGenre: "Electronic", family: "techno")
        #expect(QueryEngine.matches(q, tags: houseTechno))
        #expect(!QueryEngine.matches(q, tags: technoTechno), "a style counts only under its family")
        #expect(!QueryEngine.matches(q, tags: EffectiveTags(primaryStyle: "Electronic---Techno")), "unknown family")
        let empty = Query(familyStyles: ["house": []])
        #expect(!QueryEngine.matches(empty, tags: houseTechno))
        let back = try JSONDecoder().decode(Query.self, from: CrateJSON.encode(q))
        #expect(back == q)
    }

    @Test func vibeOrder() throws {
        let json = #"""
        {"schema":1,"order":["b","ghost","a","b"],"vibes":{
          "a":{"name":"Acid"},"b":{"name":"Breaks"},"z":{"name":"Calm"},"y":{"name":"Calm"},"x":{"name":"Afro"}}}
        """#
        let file = try CrateJSON.decode(VibesFile.self, from: Data(json.utf8), file: "vibes.json")
        #expect(file.order == ["b", "ghost", "a", "b"])
        #expect(file.ordered.map(\.id) == ["b", "a", "x", "y", "z"])
        #expect(VibesFile(vibes: ["q": Vibe(name: "Q")]).ordered.map(\.id) == ["q"])
        let encoded = jsonObject(try CrateJSON.encode(file))
        #expect(encoded["order"] as? [String] == ["b", "ghost", "a", "b"])
    }

    @Test func familyNames() throws {
        let tax = try CrateJSON.decode(TaxonomyFile.self, from: Data(#"{"schema":1,"families":["breaks & bass","techno"],"family_names":{"breaks & bass":"Breaks & bass"}}"#.utf8), file: "taxonomy.json")
        #expect(tax.displayName(family: "breaks & bass") == "Breaks & bass")
        #expect(tax.displayName(family: "techno") == "techno")
    }

    @Test func weeklyKindFamilyReason() throws {
        let json = #"""
        {"schema":1,"week":"2026-W42","candidates":1800,"slots":{"taste:techno":4},"tracks":[
          {"key":"k1","id":"s1","uri":"spotify:track:s1","kind":"taste","family":"techno","reason":"Like 412 of your techno tracks","score":0.8},
          {"id":"s2","kind":"wild"}]}
        """#
        let weekly = try CrateJSON.decode(WeeklyFile.self, from: Data(json.utf8), file: "weekly.json")
        #expect(weekly.candidates == 1800)
        let first = try #require(weekly.tracks.first)
        #expect(first.kind == .taste && first.family == "techno" && first.key == "k1")
        #expect(first.reason == "Like 412 of your techno tracks")
        #expect(weekly.tracks[1].kind == nil, "unknown kind decodes as nil")
        let map = try CrateJSON.decode(WeeklyFile.self, from: Data(#"{"tracks":{"s3":{"kind":"discovery"}}}"#.utf8), file: "weekly.json")
        #expect(map.tracks.first?.id == "s3" && map.tracks.first?.kind == .discovery)
    }

    @Test func dailyDate() throws {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Rome")!
        let daily = try CrateJSON.decode(WeeklyFile.self, from: Data(#"{"schema":1,"date":"2026-10-13","week":"2026-10-13","tracks":[]}"#.utf8), file: "weekly.json")
        #expect(daily.date == "2026-10-13" && daily.week == "2026-10-13")
        let day = try #require(daily.mixDay(calendar: cal))
        #expect(cal.dateComponents([.year, .month, .day, .hour], from: day) == DateComponents(year: 2026, month: 10, day: 13, hour: 0))

        // Only `week` as a day.
        let weekOnly = WeeklyFile(week: "2026-10-12")
        #expect(weekOnly.mixDay(calendar: cal).map { cal.component(.day, from: $0) } == 12)

        // Old weekly file: no date, ISO week label; falls back to generated_at's local day.
        let old = try CrateJSON.decode(WeeklyFile.self, from: Data(#"{"schema":1,"week":"2026-W42","generated_at":"2026-10-12T23:30:00Z","tracks":[]}"#.utf8), file: "weekly.json")
        #expect(old.date == nil)
        #expect(old.mixDay(calendar: cal).map { cal.component(.day, from: $0) } == 13, "23:30Z is the 13th in Rome")
        #expect(WeeklyFile(week: "2026-W42").mixDay(calendar: cal) == nil)

        #expect(WeeklyFile.parseISODay("2026-02-30", calendar: cal) == nil)
        #expect(WeeklyFile.parseISODay("2026-W42", calendar: cal) == nil)
        #expect(WeeklyFile.parseISODay("26-10-13", calendar: cal) == nil)
        // A non-string date is tolerated.
        let odd = try CrateJSON.decode(WeeklyFile.self, from: Data(#"{"date":20261013,"tracks":[]}"#.utf8), file: "weekly.json")
        #expect(odd.date == nil)
    }
}
