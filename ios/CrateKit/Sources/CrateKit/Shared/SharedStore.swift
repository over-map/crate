import Foundation

/// A preset as the widget and Siri see it: identity, display lines and the tracks it matched when the app last
/// computed it. The widget can't run the query engine on the full library, so the app resolves each preset's
/// match set and writes it here (spec §7.6: widgets read the app's data through the App Group).
public struct SharedPreset: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var icon: String
    public var color: String
    public var inWidget: Bool
    /// Small widget line: "Hard Techno, Breaks · 136–142".
    public var summary: String
    /// Lock Screen line: "136–142 · ϟ6–10".
    public var detail: String
    /// Every matching track URI; a send samples `limit` of them.
    public var uris: [String]
    public var limit: Int

    public init(id: String, name: String, icon: String = "bookmark", color: String = "ink", inWidget: Bool = false,
                summary: String = "", detail: String = "", uris: [String] = [], limit: Int = 100) {
        self.id = id; self.name = name; self.icon = icon; self.color = color; self.inWidget = inWidget
        self.summary = summary; self.detail = detail; self.uris = uris; self.limit = limit
    }

    enum CodingKeys: String, CodingKey {
        case id, name, icon, color, summary, detail, uris, limit
        case inWidget = "in_widget"
    }

    /// Tracks a send would write: `min(limit, matches)`.
    public var sendCount: Int { min(limit, uris.count) }

    /// The circular Lock Screen label: the number in the name ("Dark 138+" → "138+", "Sunday 90–110" → "90"),
    /// else initials ("Italian summer" → "IS"), else the first three letters ("Warehouse" → "WAR").
    public var shortLabel: String {
        let words = name.split(separator: " ").map(String.init)
        if let n = words.first(where: { $0.contains(where: \.isNumber) }) {
            return n.count <= 4 ? n : String(n.prefix { $0.isNumber || $0 == "+" })
        }
        if words.count > 1 { return words.prefix(2).compactMap(\.first).map { String($0).uppercased() }.joined() }
        return String(name.prefix(3)).uppercased()
    }
}

/// `Shared/presets.json` in the App Group. Written by the app whenever presets or the library change.
public struct PresetSnapshot: Codable, Sendable, Equatable {
    public var schema: Int
    public var generatedAt: Date
    public var nowPlaylistID: String?
    public var presets: [SharedPreset]

    public init(generatedAt: Date = Date(), nowPlaylistID: String? = nil, presets: [SharedPreset] = []) {
        schema = 1; self.generatedAt = generatedAt; self.nowPlaylistID = nowPlaylistID; self.presets = presets
    }

    enum CodingKeys: String, CodingKey {
        case schema, presets
        case generatedAt = "generated_at"
        case nowPlaylistID = "now_playlist_id"
    }

    /// The medium widget's presets: the first four marked "In widget".
    public var widgetPresets: [SharedPreset] { Array(presets.filter(\.inWidget).prefix(4)) }

    public func preset(id: String) -> SharedPreset? { presets.first { $0.id == id } }

    /// Spoken or typed names ("run 160", "Dark 138") to a preset: exact match first, then prefix, then contains,
    /// ignoring case, accents, spaces and punctuation.
    public func preset(named query: String) -> SharedPreset? {
        let q = Self.fold(query)
        guard !q.isEmpty else { return nil }
        return presets.first { Self.fold($0.name) == q }
            ?? presets.first { Self.fold($0.name).hasPrefix(q) }
            ?? presets.first { Self.fold($0.name).contains(q) }
    }

    static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).filter { $0.isLetter || $0.isNumber }
    }
}

/// The last widget/Siri send, so every widget shows the same Sending / Sent / Error state.
public struct SendStatus: Codable, Sendable, Equatable {
    public enum Phase: String, Codable, Sendable { case sending, sent, failed }

    public var phase: Phase
    public var presetID: String
    public var count: Int?
    public var failure: SendFailure?
    public var at: Date

    public init(phase: Phase, presetID: String, count: Int? = nil, failure: SendFailure? = nil, at: Date = Date()) {
        self.phase = phase; self.presetID = presetID; self.count = count; self.failure = failure; self.at = at
    }

    enum CodingKeys: String, CodingKey {
        case phase, count, failure, at
        case presetID = "preset_id"
    }

    /// How long "Sent" stays before the widget returns to idle.
    public static let sentHold: TimeInterval = 30
    /// A "sending" older than this was interrupted (the extension was killed); show idle instead.
    public static let sendingTimeout: TimeInterval = 60

    /// The state a widget should show at `date`: sent holds 30 s, an error stays until the next success.
    public func visible(at date: Date) -> SendStatus? {
        switch phase {
        case .sending: date.timeIntervalSince(at) < Self.sendingTimeout ? self : nil
        case .sent: date.timeIntervalSince(at) < Self.sentHold ? self : nil
        case .failed: self
        }
    }
}

/// Why a widget or Siri send didn't happen. Raw values are stored in the status file.
public enum SendFailure: String, Codable, Sendable, Error, Equatable {
    case loginExpired, noTracks, unknownPreset, noData, network

    /// Headline: "Spotify login expired".
    public var title: String {
        switch self {
        case .loginExpired: "Spotify login expired"
        case .noTracks: "No tracks match"
        case .unknownPreset: "Preset not found"
        case .noData: "Open Crate once"
        case .network: "Couldn't reach Spotify"
        }
    }

    /// Plain words with the one fix (design 4i). `preset` is the preset name when known.
    public func explanation(preset: String?) -> String {
        switch self {
        case .loginExpired: "Spotify login expired. Open Crate to sign in again; Now wasn't changed."
        case .noTracks: "No tracks match \(preset ?? "this preset") right now."
        case .unknownPreset: "That preset isn't in Crate any more. Pick another one."
        case .noData: "Open Crate once so the widget can load your presets."
        case .network: "Couldn't reach Spotify. Now wasn't changed; try again in a moment."
        }
    }

    public init(_ error: Error) {
        switch error {
        case SpotifyError.notLoggedIn, SpotifyError.unauthorized: self = .loginExpired
        case let failure as SendFailure: self = failure
        default: self = .network
        }
    }
}

/// Files in `Shared/` inside the App Group container, read and written by the app, widget and Siri.
public struct SharedStore: Sendable {
    public let directory: URL

    public init(directory: URL) { self.directory = directory }

    /// The store in the App Group container, or `nil` if the group is unavailable.
    public static func appGroup(_ identifier: String = CrateIdentity.appGroup) -> SharedStore? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
            .map { SharedStore(directory: $0.appendingPathComponent("Shared", isDirectory: true)) }
    }

    private struct NowFile: Codable { var id: String }

    public func loadSnapshot() -> PresetSnapshot? { read("presets.json") }
    public func save(_ snapshot: PresetSnapshot) { write(snapshot, "presets.json") }

    public func loadStatus() -> SendStatus? { read("status.json") }
    public func save(_ status: SendStatus) { write(status, "status.json") }

    /// The Now playlist ID found or created by a widget/Siri send (the app copies it into presets.json).
    public func loadNowPlaylistID() -> String? { (read("now.json") as NowFile?)?.id }
    public func saveNowPlaylistID(_ id: String) { write(NowFile(id: id), "now.json") }

    private func read<T: Decodable>(_ name: String) -> T? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(name)) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(T.self, from: data)
    }

    private func write<T: Encodable>(_ value: T, _ name: String) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try encoder.encode(value).write(to: directory.appendingPathComponent(name), options: .atomic)
        } catch {
            // Losing a status write only costs the widget one stale frame.
        }
    }
}
