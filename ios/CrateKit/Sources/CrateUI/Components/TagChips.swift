import SwiftUI

/// A picked community tag: `accent` fill, the `tag` symbol (⌗ in the mockups), the name and an optional ×.
///
/// The symbol keeps tags from ever reading as vibes. Tags combine with AND.
public struct TagChip: View {
    private let name: String
    private let onRemove: (() -> Void)?

    /// - Parameters:
    ///   - name: The normalised tag ("dub techno").
    ///   - onRemove: Adds a trailing × (and a VoiceOver "Remove" action).
    public init(_ name: String, onRemove: (() -> Void)? = nil) {
        self.name = name
        self.onRemove = onRemove
    }

    public var body: some View {
        HStack(spacing: Metric.chipInnerGap) {
            Image(systemName: "tag").font(.footnote)
            Text(name)
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark").font(.caption.weight(.semibold)).opacity(0.6)
                        .frame(minWidth: Space.l)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .chipChrome(Color.accent, foreground: Color.onAccent)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Tag \(name)")
        .accessibilityAddTraits(.isSelected)
        .modifier(OptionalAccessibilityAction(name: "Remove", action: onRemove))
    }
}

/// One autocomplete row in the tag suggestions card:
/// ⌗ · name (the matched part bold) · count if added · library total (tertiary).
///
/// Rows whose count-if-added is 0 dim to 45 %. Minimum height 44.
public struct TagSuggestionRow: View {
    private let name: String
    private let query: String
    private let countIfAdded: Int?
    private let libraryTotal: Int
    private let action: () -> Void

    /// - Parameters:
    ///   - name: The tag.
    ///   - query: What the user typed; its first case-insensitive match in `name` is bold.
    ///   - countIfAdded: Matches if this tag were added; `nil` hides it.
    ///   - libraryTotal: Tracks with this tag in the whole library.
    ///   - action: Adds the tag.
    public init(_ name: String, query: String, countIfAdded: Int?, libraryTotal: Int, action: @escaping () -> Void) {
        self.name = name
        self.query = query
        self.countIfAdded = countIfAdded
        self.libraryTotal = libraryTotal
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: Space.s + Space.xxs) {
                Image(systemName: "tag").font(.footnote).foregroundStyle(Color.textSecondary)
                Text(highlighted).font(.body).foregroundStyle(Color.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let countIfAdded {
                    Text(countIfAdded.formatted())
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(Color.textPrimary)
                        .monospacedDigit()
                        .countTransition(countIfAdded)
                }
                Text(libraryTotal.formatted())
                    .font(.system(.footnote, design: .rounded, weight: .medium))
                    .foregroundStyle(Color.textTertiary)
                    .monospacedDigit()
                    .frame(minWidth: Size.minTarget, alignment: .trailing)
            }
            .padding(.horizontal, Space.m)
            .frame(minHeight: Size.minTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(RowPressStyle())
        .opacity(countIfAdded == 0 ? Metric.dimmed : 1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(.isButton)
    }

    private var highlighted: AttributedString {
        var text = AttributedString(name)
        if !query.isEmpty, let range = text.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) {
            text[range].inlinePresentationIntent = .stronglyEmphasized
        }
        return text
    }

    private var accessibilityLabel: String {
        var parts = ["Tag \(name)"]
        if let countIfAdded { parts.append("\(countIfAdded) if added") }
        parts.append("\(libraryTotal) in library")
        return parts.joined(separator: ", ")
    }
}

/// The row shown when a typed term matches no tag: ⌗ · "No tracks tagged "x"".
public struct TagUnknownRow: View {
    private let term: String

    /// - Parameter term: What the user typed.
    public init(term: String) { self.term = term }

    public var body: some View {
        HStack(spacing: Space.s + Space.xxs) {
            Image(systemName: "tag").font(.footnote).foregroundStyle(Color.textTertiary)
            Text("No tracks tagged \u{201C}\(term)\u{201D}")
                .font(.secondary)
                .foregroundStyle(Color.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: Size.minTarget)
        .accessibilityElement(children: .combine)
    }
}

/// Pressed rows highlight with `fillControl`, like system lists.
struct RowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.fillControl : Color.clear)
    }
}
