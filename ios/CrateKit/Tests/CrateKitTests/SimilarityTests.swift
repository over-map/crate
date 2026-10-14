import Foundation
import Testing
@testable import CrateKit

@Suite("Similarity")
struct SimilarityTests {
    @Test func float16Bits() {
        #expect(Float16Decoding.float(fromBits: 0x3C00) == 1)
        #expect(Float16Decoding.float(fromBits: 0xC000) == -2)
        #expect(Float16Decoding.float(fromBits: 0x3800) == 0.5)
        #expect(Float16Decoding.float(fromBits: 0x7BFF) == 65504)
        #expect(Float16Decoding.float(fromBits: 0x0001) == Float(sign: .plus, exponent: -24, significand: 1))
        #expect(Float16Decoding.float(fromBits: 0x8000) == 0 && Float16Decoding.float(fromBits: 0x8000).sign == .minus)
        #expect(Float16Decoding.float(fromBits: 0x7C00) == .infinity)
        #expect(Float16Decoding.float(fromBits: 0x7E00).isNaN)
        #expect(abs(Float16Decoding.float(fromBits: 0x3555) - 0.33325195) < 1e-7)
    }

    @Test func littleEndianDecoding() throws {
        #expect(try Float16Decoding.floats(fromLittleEndian: Data([0x00, 0x3C, 0x00, 0xC0])) == [1, -2])
        #expect(throws: SimilarityError.self) { try Float16Decoding.floats(fromLittleEndian: Data([0x00])) }
    }

    /// Builds a 32-d float16 file where row i has `value` in dimension `dim`.
    private func makeIndex(_ rows: [(id: String, dims: [Int: UInt16])]) throws -> VectorIndex {
        var bytes = [UInt8]()
        for row in rows {
            for d in 0..<32 {
                let bits = row.dims[d] ?? 0
                bytes += [UInt8(bits & 0xFF), UInt8(bits >> 8)]
            }
        }
        let idx = try JSONEncoder().encode(rows.map(\.id))
        return try VectorIndex(bin: Data(bytes), idsJSON: idx)
    }

    @Test func nearestExcludesSeedAndRanksByCosine() throws {
        // seed = (1, 0); a = (1, 0.5) ≈ 0.894; b = (0, 1) = 0; c = (1, 0) = 1; d = (-1, 0) = -1.
        let index = try makeIndex([
            ("seed", [0: 0x3C00]), ("a", [0: 0x3C00, 1: 0x3800]), ("b", [1: 0x3C00]),
            ("c", [0: 0x3C00]), ("d", [0: 0xBC00]),
        ])
        #expect(index.count == 5 && index.vector(for: "a")?[1] == 0.5)
        let top = try index.nearest(to: "seed", count: 3)
        #expect(top.map(\.id) == ["c", "a", "b"])
        #expect(abs(top[1].score - 0.8944272) < 1e-5)
        #expect(try index.nearest(to: "seed", count: 10, candidates: ["d", "b"]).map(\.id) == ["b", "d"])

        var rng = SeededGenerator(seed: 3)
        let more = try index.moreLikeThis(seed: "seed", count: 3, rng: &rng)
        #expect(Set(more) == ["c", "a", "b"])
        #expect(throws: SimilarityError.unknownTrack("zzz")) { try index.nearest(to: "zzz", count: 1) }
    }

    @Test func sizeMismatchIsRejected() {
        #expect(throws: SimilarityError.self) {
            try VectorIndex(bin: Data(count: 63), ids: ["a"])
        }
    }
}
