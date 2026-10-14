import Foundation

/// Errors raised while reading Crate data files.
public enum CrateDataError: Error, Equatable, Sendable, LocalizedError {
    /// The file was written by a newer version of Crate than this app understands.
    case unsupportedSchema(file: String, found: Int, supported: Int)

    public var errorDescription: String? {
        switch self {
        case let .unsupportedSchema(file, found, supported):
            return "\(file) uses data schema \(found), but this app only understands up to \(supported). Update the app."
        }
    }
}

/// Decoding and encoding of the JSON files in `data/`, with the schema guard applied.
public enum CrateJSON {
    /// The newest data schema this version of CrateKit understands.
    public static let supportedSchema = 1

    private struct SchemaProbe: Decodable { let schema: Int? }

    /// Decodes `type` from `data`, refusing files whose top-level `schema` is newer than ``supportedSchema``.
    ///
    /// A missing `schema` is treated as the current one. Top-level arrays (legacy `presets.json`) skip the check.
    /// - Parameter file: The file name, used in the error message.
    public static func decode<T: Decodable>(_ type: T.Type, from data: Data, file: String) throws -> T {
        let decoder = JSONDecoder()
        if let probe = try? decoder.decode(SchemaProbe.self, from: data),
           let schema = probe.schema, schema > supportedSchema {
            throw CrateDataError.unsupportedSchema(file: file, found: schema, supported: supportedSchema)
        }
        return try decoder.decode(T.self, from: data)
    }

    /// Encodes `value` deterministically (sorted keys, no escaped slashes), as the pipeline does.
    public static func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes, .prettyPrinted]
        return try encoder.encode(value)
    }
}
