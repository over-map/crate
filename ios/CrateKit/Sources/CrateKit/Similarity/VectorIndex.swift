import Foundation

/// Errors reading the similarity vectors.
public enum SimilarityError: Error, Equatable, Sendable {
    case malformedVectors(reason: String)
    case unknownTrack(String)
}

/// Sound-similarity embeddings (`data/vectors.bin` + `data/vectors.idx.json`) and "More like this".
///
/// `vectors.bin` is a row-major matrix of little-endian float16, one row of ``dimensions`` values per track;
/// row *i* belongs to `vectors.idx.json[i]`.
public struct VectorIndex: Sendable {
    /// Embedding size written by the pipeline (PCA 32-d).
    public static let defaultDimensions = 32

    public let ids: [String]
    public let dimensions: Int
    private let values: [Float]
    private let norms: [Float]
    private let rowOf: [String: Int]

    /// Builds the index from the raw file contents.
    /// - Parameters:
    ///   - bin: Contents of `vectors.bin`.
    ///   - idsJSON: Contents of `vectors.idx.json` (a JSON array of track IDs).
    public init(bin: Data, idsJSON: Data, dimensions: Int = VectorIndex.defaultDimensions) throws {
        let ids: [String]
        do { ids = try JSONDecoder().decode([String].self, from: idsJSON) } catch {
            throw SimilarityError.malformedVectors(reason: "vectors.idx.json is not an array of IDs")
        }
        try self.init(bin: bin, ids: ids, dimensions: dimensions)
    }

    /// Builds the index from `vectors.bin` and the decoded ID list.
    public init(bin: Data, ids: [String], dimensions: Int = VectorIndex.defaultDimensions) throws {
        let expected = ids.count * dimensions * 2
        guard bin.count == expected else {
            throw SimilarityError.malformedVectors(reason: "expected \(expected) bytes for \(ids.count) rows, got \(bin.count)")
        }
        let values = try Float16Decoding.floats(fromLittleEndian: bin)
        self.ids = ids
        self.dimensions = dimensions
        self.values = values
        self.norms = (0..<ids.count).map { row in
            var sum: Float = 0
            for d in 0..<dimensions { let v = values[row * dimensions + d]; sum += v * v }
            return sum.squareRoot()
        }
        var rowOf = [String: Int](minimumCapacity: ids.count)
        for (i, id) in ids.enumerated() where rowOf[id] == nil { rowOf[id] = i }
        self.rowOf = rowOf
    }

    /// Number of tracks in the index.
    public var count: Int { ids.count }

    /// True when `id` has an embedding.
    public func contains(_ id: String) -> Bool { rowOf[id] != nil }

    /// The embedding of `id`, if present.
    public func vector(for id: String) -> [Float]? {
        guard let row = rowOf[id] else { return nil }
        return Array(values[row * dimensions ..< (row + 1) * dimensions])
    }

    /// Cosine similarity between two indexed tracks.
    public func cosine(_ a: String, _ b: String) -> Float? {
        guard let i = rowOf[a], let j = rowOf[b] else { return nil }
        return cosine(rows: i, j)
    }

    private func cosine(rows i: Int, _ j: Int) -> Float {
        let denominator = norms[i] * norms[j]
        guard denominator > 0 else { return 0 }
        var dot: Float = 0
        for d in 0..<dimensions { dot += values[i * dimensions + d] * values[j * dimensions + d] }
        return dot / denominator
    }

    /// The `count` tracks most similar to `seed`, best first, excluding the seed itself.
    /// - Parameter candidates: Optional restriction (e.g. tracks still in the library).
    public func nearest(to seed: String, count: Int, candidates: Set<String>? = nil) throws -> [(id: String, score: Float)] {
        guard let seedRow = rowOf[seed] else { throw SimilarityError.unknownTrack(seed) }
        var scored: [(id: String, score: Float)] = []
        scored.reserveCapacity(ids.count)
        for (row, id) in ids.enumerated() where id != seed && rowOf[id] == row {
            if let candidates, !candidates.contains(id) { continue }
            scored.append((id, cosine(rows: seedRow, row)))
        }
        scored.sort { $0.score != $1.score ? $0.score > $1.score : $0.id < $1.id }
        return Array(scored.prefix(max(0, count)))
    }

    /// The `count` indexed tracks most similar to an outside `vector` (a Daily pick), best first.
    public func nearest(vector: [Double], count: Int, candidates: Set<String>? = nil) -> [(id: String, score: Float)] {
        guard vector.count == dimensions else { return [] }
        let q = vector.map(Float.init)
        let qn = q.reduce(0) { $0 + $1 * $1 }.squareRoot()
        guard qn > 0 else { return [] }
        var scored: [(id: String, score: Float)] = []
        for (row, id) in ids.enumerated() where rowOf[id] == row && norms[row] > 0 {
            if let candidates, !candidates.contains(id) { continue }
            var dot: Float = 0
            for d in 0..<dimensions { dot += values[row * dimensions + d] * q[d] }
            scored.append((id, dot / (norms[row] * qn)))
        }
        scored.sort { $0.score != $1.score ? $0.score > $1.score : $0.id < $1.id }
        return Array(scored.prefix(max(0, count)))
    }

    /// "More like this": the top `count` similar tracks (default 100), shuffled with Fisher–Yates.
    public func moreLikeThis<R: RandomNumberGenerator>(
        seed: String, count: Int = 100, candidates: Set<String>? = nil, rng: inout R
    ) throws -> [String] {
        var result = try nearest(to: seed, count: count, candidates: candidates).map(\.id)
        QueryEngine.fisherYates(&result, rng: &rng)
        return result
    }
}
