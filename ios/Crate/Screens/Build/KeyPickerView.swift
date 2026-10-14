import CrateKit
import CrateUI
import SwiftUI

/// Camelot key picker sheet (2i). Owner: Build agent.
///
/// Edits a draft key + compatible flag; Done commits to `model.build`, "Any key" clears the draft.
struct KeyPickerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var key: String?
    @State private var compatible = true
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Space.l) {
                    CamelotWheel(selection: $key, compatible: compatible)
                        .frame(maxWidth: 340)
                        .padding(.top, Space.s)
                    Text("Inner A · minor    Outer B · major").panelLabelStyle().foregroundStyle(Color.textTertiary)
                        .accessibilityHidden(true)
                    card
                }
                .padding(.horizontal, Space.screenMargin)
                .padding(.bottom, Space.l)
            }
            .background(Color.bgSheet.ignoresSafeArea())
            .navigationTitle("Key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(Color.textSecondary)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("Any key") { withAnimation(CrateMotion.chip) { key = nil } }
                        .foregroundStyle(Color.textPrimary)
                        .disabled(key == nil)
                }
            }
            .safeAreaInset(edge: .bottom) { bottomBar }
        }
        .presentationDragIndicator(.visible)
        .presentationBackground(Color.bgSheet)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            key = model.build.key
            compatible = model.build.compatible
        }
    }

    private var card: some View {
        VStack(spacing: 0) {
            Toggle(isOn: $compatible) {
                VStack(alignment: .leading, spacing: Space.xxs) {
                    Text("Include compatible keys").font(.body).foregroundStyle(Color.textPrimary)
                    Text(compatibleNote).font(.footnote).foregroundStyle(Color.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(Color.accent)
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.m)
            if let key {
                Rectangle().fill(Color.separator).frame(height: 0.5)
                let counts = model.keyCounts(key)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: Space.s) { exact(key, counts.exact); Spacer(minLength: Space.s); withCompat(counts.compatible) }
                    VStack(alignment: .leading, spacing: Space.xs) { exact(key, counts.exact); withCompat(counts.compatible) }
                }
                .padding(.horizontal, Space.m)
                .frame(minHeight: Size.minTarget)
                .padding(.vertical, Space.xs)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(key): \(counts.exact) tracks, with compatible keys: \(counts.compatible)")
            }
        }
        .crateCard()
    }

    private func exact(_ key: String, _ n: Int) -> some View {
        HStack(spacing: Space.s) {
            CamelotBadge(code: key, size: .small)
            (Text(n.formatted()).fontWeight(.semibold).foregroundStyle(Color.textPrimary)
                + Text(" tracks").foregroundStyle(Color.textSecondary))
                .font(.secondary)
        }
    }

    private func withCompat(_ n: Int) -> some View {
        (Text("with compatible ").foregroundStyle(Color.textSecondary)
            + Text(n.formatted()).fontWeight(.semibold).foregroundStyle(compatible ? Color.textPrimary : Color.textSecondary))
            .font(.secondary)
    }

    private var compatibleNote: String {
        guard let key, let k = Camelot(key) else { return "Neighbouring keys and the relative key mix smoothly" }
        let others = k.compatible.dropFirst().map(\.code)
        let list = others.dropLast().joined(separator: ", ") + " and " + (others.last ?? "")
        return "\(list) mix smoothly with \(k.code)"
    }

    private var bottomBar: some View {
        let count = model.count { $0.key = key; $0.compatible = compatible }
        return HStack(spacing: Space.m) {
            VStack(alignment: .leading, spacing: 0) {
                Text(count.formatted()).font(.numeralMedium).foregroundStyle(Color.textPrimary).countTransition(count)
                Text("match all filters").font(.footnote).foregroundStyle(Color.textSecondary)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: Space.s)
            Button {
                model.build.key = key
                model.build.compatible = compatible
                dismiss()
            } label: {
                Text("Done").font(.headline).foregroundStyle(Color.onAccent)
                    .padding(.horizontal, Space.xl)
                    .frame(minHeight: Size.buttonHeight)
                    .background(Color.accent, in: RoundedRectangle(cornerRadius: Radius.button, style: .continuous))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, Space.screenMargin)
        .padding(.vertical, Space.m)
        .background(.bar)
    }
}
