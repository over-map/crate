import SwiftUI

/// What the primary (Send) button shows. The caller owns the state machine.
public enum PrimaryButtonState: Equatable, Sendable {
    /// Ready: e.g. "Send 100 to Now".
    case idle(title: String)
    /// Sending with a determinate fill. `progress` is 0–1; `counter` is shown after "Sending…" ("3/8").
    case sending(progress: Double, counter: String?)
    /// Done. `message` sits above the button with a ✓ ("Sent 8 tracks to Now"); the button shows `title`
    /// ("Listen on Spotify").
    case success(message: String, title: String)
    /// Failed. `message` sits above on `errorFill`; the button shows `title` ("Retry").
    case error(message: String, title: String)
    /// Nothing to do ("No tracks to send").
    case disabled(title: String)

    fileprivate var kind: Int {
        switch self {
        case .idle: 0
        case .sending: 1
        case .success: 2
        case .error: 3
        case .disabled: 4
        }
    }
}

/// The 54 pt primary button (Send to Now) with its inline status message.
///
/// - Idle / success / error: `accent` fill, `onAccent` label.
/// - Sending: `fillControlActive` track with an `accent` fill that grows with progress; the label is
///   legible over both parts.
/// - Disabled: `fillControl`, `textTertiary`.
///
/// Plays `.success` / `.error` haptics when the state changes into success or error, and morphs with
/// `.spring(0.4, bounce 0.15)` (a crossfade with Reduce Motion).
public struct PrimaryButton: View {
    private let state: PrimaryButtonState
    private let icon: Image?
    private let secondaryTitle: String?
    private let secondaryAction: (() -> Void)?
    private let liftOffCovers: [URL]
    private let action: () -> Void
    @State private var liftOff = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// - Parameters:
    ///   - state: What to show.
    ///   - icon: Optional leading image on the success button (the official Spotify icon asset).
    ///   - secondaryTitle: Trailing action on the success message line ("Send again").
    ///   - secondaryAction: Called when the secondary title is tapped.
    ///   - liftOffCovers: Covers of what was just sent; on success they float up out of the button with a light
    ///     wave (``LiftOffEffect``, drawn by a ``View/liftOffHost()`` above the screen). Empty plays only the wave.
    ///   - action: Called on tap in every state except sending and disabled.
    public init(_ state: PrimaryButtonState, icon: Image? = nil, secondaryTitle: String? = nil,
                secondaryAction: (() -> Void)? = nil, liftOffCovers: [URL] = [], action: @escaping () -> Void) {
        self.liftOffCovers = liftOffCovers
        self.state = state
        self.icon = icon
        self.secondaryTitle = secondaryTitle
        self.secondaryAction = secondaryAction
        self.action = action
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            message
            Button(action: action) { label }
                .buttonStyle(PrimaryPressStyle(enabled: isEnabled))
                .disabled(!isEnabled)
                .accessibilityLabel(accessibilityTitle)
                .accessibilityValue(accessibilityValue)
                .accessibilityAddTraits(isSending ? .updatesFrequently : [])
                .anchorPreference(key: LiftOffKey.self, value: .bounds) { [liftOff, liftOffCovers] anchor in
                    liftOff > 0 ? LiftOffRequest(id: liftOff, covers: liftOffCovers, anchor: anchor) : nil
                }
        }
        .onChange(of: state.kind) { old, new in if new == 2 && old == 1 { liftOff += 1 } }
        .animation(CrateMotion.animation(CrateMotion.morph, reduceMotion: reduceMotion), value: state.kind)
        .sensoryFeedback(trigger: state.kind) { _, new in
            switch new {
            case 2: .success
            case 3: .error
            default: nil
            }
        }
    }

    private var isEnabled: Bool {
        switch state {
        case .sending, .disabled: false
        default: true
        }
    }

    private var isSending: Bool { if case .sending = state { true } else { false } }

    // MARK: Message line

    @ViewBuilder private var message: some View {
        switch state {
        case let .success(text, _):
            HStack(spacing: Space.s) {
                Label(text, systemImage: "checkmark")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.success)
                Spacer(minLength: Space.s)
                if let secondaryTitle, let secondaryAction {
                    Button(secondaryTitle, action: secondaryAction)
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                        .frame(minHeight: Size.minTarget)
                        .contentShape(Rectangle())
                }
            }
            .transition(.opacity)
        case let .error(text, _):
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.error)
                    .accessibilityHidden(true)
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(Color.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.vertical, Space.s + Space.xxs)
            .padding(.horizontal, Space.m)
            .background(Color.errorFill, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
            .accessibilityElement(children: .combine)
            .transition(.opacity)
        default:
            EmptyView()
        }
    }

    // MARK: Button face

    private var title: String {
        switch state {
        case let .idle(t), let .disabled(t), let .success(_, t), let .error(_, t): t
        case let .sending(_, counter): ["Sending…", counter].compactMap { $0 }.joined(separator: " ")
        }
    }

    private var label: some View {
        let shape = RoundedRectangle(cornerRadius: Radius.button, style: .continuous)
        return face
            .frame(maxWidth: .infinity)
            .frame(minHeight: Size.buttonHeight)
            .background { background }
            .clipShape(shape)
            .contentShape(shape)
    }

    @ViewBuilder private var face: some View {
        switch state {
        case let .sending(progress, _):
            let p = min(1, max(0, progress))
            faceText.frame(maxWidth: .infinity).foregroundStyle(Color.textPrimary)
                .overlay {
                    faceText.frame(maxWidth: .infinity).foregroundStyle(Color.onAccent)
                        .mask(alignment: .leading) {
                            GeometryReader { geo in Rectangle().frame(width: geo.size.width * p) }
                        }
                }
        case .disabled:
            faceText.foregroundStyle(Color.textTertiary)
        default:
            HStack(spacing: Space.s + Space.xxs) {
                if case .success = state, let icon {
                    icon.resizable().scaledToFit().frame(width: Space.xl - Space.xxs, height: Space.xl - Space.xxs)
                        .accessibilityHidden(true)
                }
                faceText
            }
            .foregroundStyle(Color.onAccent)
        }
    }

    @ViewBuilder private var background: some View {
        switch state {
        case let .sending(progress, _):
            let p = min(1, max(0, progress))
            Color.fillControlActive
                .overlay(alignment: .leading) {
                    GeometryReader { geo in Color.accent.frame(width: geo.size.width * p) }
                }
                .animation(.linear(duration: 0.2), value: p)
        case .disabled:
            Color.fillControl
        default:
            Color.accent
        }
    }

    private var faceText: some View {
        Text(title)
            .font(.headline)
            .monospacedDigit()
            .multilineTextAlignment(.center)
            .padding(.horizontal, Space.l)
            .padding(.vertical, Space.s)
    }

    private var accessibilityTitle: String {
        if case .sending = state { return "Sending" }
        return title
    }

    private var accessibilityValue: String {
        if case let .sending(progress, counter) = state {
            if let counter { return counter.replacingOccurrences(of: "/", with: " of ") }
            return "\(Int((min(1, max(0, progress)) * 100).rounded())) percent"
        }
        return ""
    }
}

private struct PrimaryPressStyle: ButtonStyle {
    let enabled: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed && enabled
        configuration.label
            .opacity(pressed ? 0.85 : 1)
            .scaleEffect(pressed && !reduceMotion ? 0.98 : 1)
            .animation(CrateMotion.animation(CrateMotion.chip, reduceMotion: reduceMotion), value: pressed)
    }
}
