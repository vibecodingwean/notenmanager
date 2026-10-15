import SwiftUI

struct AppearanceView: View {
    @EnvironmentObject private var appearance: AppearanceStore

    var body: some View {
        List {
            Section {
                Text("Wähle einen Look, der zu dir passt. Deine Noten und Backups bleiben davon unabhängig.")
                    .font(.subheadline)
                    .lookMuted()
            }
            .listRowBackground(LookCardBackground())
            Section("Dein Look") {
                ForEach(AppearanceStore.looks) { look in
                    Button {
                        appearance.select(look.id)
                    } label: {
                        HStack(spacing: 12) {
                            LookPreview(look: look)
                                .frame(width: 76, height: 64)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(look.title).font(.system(.headline, design: look.fontDesign))
                                Text(look.hint).font(.caption).foregroundStyle(look.muted)
                            }
                            Spacer(minLength: 0)
                            if appearance.lookID == look.id {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(look.accent)
                                    .accessibilityHidden(true)
                            }
                        }
                        .foregroundStyle(look.ink)
                        .lookFontDesign(look.fontDesign)
                        .padding(.vertical, 7)
                    }
                    .listRowBackground(look.surface)
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(look.title), \(look.hint)")
                    .accessibilityAddTraits(appearance.lookID == look.id ? .isSelected : [])
                    .accessibilityIdentifier("appearance_\(look.id)")
                }
            }
            Section("Bewegung") {
                Toggle("Hintergrund animieren", isOn: $appearance.animations)
                    .accessibilityIdentifier("themeAnimations")
                Text(appearance.selected.isYouth
                     ? "Dieser Look ist immer statisch. Deine Animationseinstellung bleibt für andere Looks gespeichert."
                     : "Nur der Hintergrund bewegt sich. Ohne Animation bleibt dein Look erhalten. „Bewegung reduzieren“ und eine inaktive App pausieren die Bewegung ebenfalls.")
                    .font(.footnote).foregroundStyle(appearance.selected.muted)
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .transaction { transaction in
            transaction.animation = nil
            transaction.disablesAnimations = true
        }
        .navigationTitle("Aussehen")
    }
}
