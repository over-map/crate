import Foundation
import Testing
@testable import CrateKit

private struct CamelotVectors: Decodable {
    struct Key: Decodable { let tonic: String; let scale: String; let camelot: String }
    struct Name: Decodable { let camelot: String; let name: String }
    struct Compatible: Decodable { let camelot: String; let set: [String] }
    let keys: [Key]
    let names: [Name]
    let compatible: [Compatible]
    let invalid: [String]
}

@Suite("Camelot")
struct CamelotTests {
    private let vectors = try! JSONDecoder().decode(CamelotVectors.self, from: Repo.spec("camelot_vectors.json"))

    @Test func keysConvert() {
        for k in vectors.keys {
            #expect(Camelot(tonic: k.tonic, scale: k.scale)?.code == k.camelot, "\(k.tonic) \(k.scale)")
        }
    }

    @Test func names() {
        for n in vectors.names { #expect(Camelot(n.camelot)?.name == n.name, "\(n.camelot)") }
        #expect(Camelot("11A")?.name == "F♯ minor")
        #expect(Camelot("3B")?.name == "D♭ major")
    }

    @Test func compatibleSets() {
        for c in vectors.compatible {
            #expect(Camelot(c.camelot)?.compatible.map(\.code) == c.set, "\(c.camelot)")
        }
    }

    @Test func invalidCodes() {
        for code in vectors.invalid { #expect(Camelot(code) == nil, "\(code)"); #expect(!Camelot.isValid(code)) }
        #expect(Camelot("08A") == nil)
        #expect(Camelot(" 8a ")?.code == "8A")
    }

    @Test func allKeysRoundTripThroughNamesAndUnicodeAccidentals() {
        #expect(Camelot.all.count == 24)
        for key in Camelot.all {
            let parts = key.name.split(separator: " ")
            #expect(Camelot(tonic: String(parts[0]), scale: String(parts[1])) == key, "\(key.name)")
        }
        #expect(Camelot(tonic: "Cb", scale: "major")?.code == "1B")
        #expect(Camelot(tonic: "A", scale: "maj")?.code == "11B")
        #expect(Camelot(tonic: "H", scale: "minor") == nil)
        #expect(Camelot(tonic: "A", scale: "dorian") == nil)
    }
}
