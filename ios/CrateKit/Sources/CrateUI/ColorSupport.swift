import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

public extension Color {
    /// sRGB colour from a 0xRRGGBB literal.
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: 1)
    }

    /// A colour that follows the appearance: `dark` is the default, `light` in light mode.
    ///
    /// The platform colours are built up front from raw components, and the provider closure only picks one:
    /// UIKit may resolve dynamic colours off the main thread (e.g. `List` row prefetching), so nothing in the
    /// closure may touch main-actor API such as `UIColor(Color)`.
    static func dynamic(dark: UInt32, light: UInt32) -> Color {
        #if canImport(UIKit)
        let d = platformColor(dark), l = platformColor(light)
        return Color(UIColor { @Sendable traits in traits.userInterfaceStyle == .light ? l : d })
        #elseif canImport(AppKit)
        let d = platformColor(dark), l = platformColor(light)
        return Color(NSColor(name: nil) { @Sendable appearance in
            appearance.bestMatch(from: [.aqua, .darkAqua]) == .aqua ? l : d
        })
        #else
        return Color(hex: dark)
        #endif
    }
}

#if canImport(UIKit)
private func platformColor(_ hex: UInt32) -> UIColor {
    UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}
#elseif canImport(AppKit)
private func platformColor(_ hex: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}
#endif

/// Dynamic Type scaling for sizes that don't match a system text style (the big numerals).
public enum CrateType {
    public static func scaled(_ size: CGFloat, _ style: Font.TextStyle, maxScale: CGFloat) -> CGFloat {
        #if canImport(UIKit)
        let ui: UIFont.TextStyle = switch style {
        case .largeTitle: .largeTitle
        case .title: .title1
        case .title2: .title2
        case .title3: .title3
        case .headline: .headline
        case .subheadline: .subheadline
        case .footnote: .footnote
        case .caption: .caption1
        case .caption2: .caption2
        default: .body
        }
        return min(UIFontMetrics(forTextStyle: ui).scaledValue(for: size), size * maxScale)
        #else
        return size
        #endif
    }
}
