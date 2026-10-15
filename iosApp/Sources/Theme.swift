import Foundation
import SwiftUI

struct Look: Identifiable {
    let id: String
    let title: String
    let hint: String
    let dark: Bool
    fileprivate let colors: LookColors

    var accent: Color { Color(hex: colors.accent) }
    var background: Color { Color(hex: colors.background) }
    var surface: Color { Color(hex: colors.surface) }
    var ink: Color { Color(hex: colors.ink) }
}

@MainActor final class AppearanceStore: ObservableObject {
    static let looks: [Look] = [
        Look(id: "classic", title: "Klassisch", hint: "Dein vertrauter Look", dark: false,
             colors: .init(accent: 0x3653BD, background: 0xF2F4FD, surface: 0xFFFFFF, ink: 0x202743)),
        Look(id: "hacker", title: "Hacker", hint: "Matrix-Code · leuchtender Röhrenbildschirm", dark: true,
             colors: .init(accent: 0x39FF14, background: 0x050505, surface: 0x090E0A, ink: 0xB8FFA3)),
        Look(id: "social", title: "Social Glow", hint: "Rosé & Pfirsich · schwebende Lichtblasen", dark: false,
             colors: .init(accent: 0x993066, background: 0xFFF3F6, surface: 0xFFFBFD, ink: 0x38243A)),
        Look(id: "streamer", title: "Streamer", hint: "Neon & Violett · leuchtende Bahnen", dark: true,
             colors: .init(accent: 0xDFB6FF, background: 0x100A20, surface: 0x231832, ink: 0xEDF5FF)),
        Look(id: "orbit", title: "Orbit", hint: "Nachtblau · ruhig ziehende Sterne", dark: true,
             colors: .init(accent: 0xA1D9FF, background: 0x080F24, surface: 0x13263E, ink: 0xEDF5FF)),
        Look(id: "pixel", title: "Pixelwelt", hint: "Grasblöcke & Wolken · dein Bau-Abenteuer", dark: false,
             colors: .init(accent: 0x285B31, background: 0xDFF2FF, surface: 0xFCF7E9, ink: 0x253124)),
        Look(id: "bayern", title: "FC Bayern", hint: "Leuchtendes Rot · Arena bei Nacht", dark: true,
             colors: .init(accent: 0xE32B36, background: 0x000000, surface: 0x000000, ink: 0xFFFFFF)),
        Look(id: "streetart", title: "STREET ART", hint: "Creme & Neonorange · Graffiti", dark: false,
             colors: .init(accent: 0x873000, background: 0xFFF7E8, surface: 0xFFFCF5, ink: 0x171511)),
        Look(id: "bubblegum", title: "BUBBLEGUM", hint: "Pink & Eisblau · Sticker", dark: false,
             colors: .init(accent: 0x84245F, background: 0xFFEFF7, surface: 0xFFFAFD, ink: 0x321044)),
        Look(id: "speed", title: "SPEED", hint: "Anthrazit & Limette · Racing", dark: true,
             colors: .init(accent: 0xC6FF37, background: 0x171A1E, surface: 0x22262B, ink: 0xFFFFFF)),
    ]

    @Published private(set) var lookID: String {
        didSet { defaults.set(lookID, forKey: Self.lookKey) }
    }
    @Published var animations: Bool {
        didSet { defaults.set(animations, forKey: Self.animationsKey) }
    }

    private static let lookKey = "appearance.look"
    private static let animationsKey = "appearance.animations"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let storedID = defaults.string(forKey: Self.lookKey) ?? "classic"
        lookID = Self.looks.contains { $0.id == storedID } ? storedID : "classic"
        animations = defaults.object(forKey: Self.animationsKey) as? Bool ?? true
    }

    func select(_ id: String) {
        guard Self.looks.contains(where: { $0.id == id }) else { return }
        lookID = id
    }

    var selected: Look { Self.looks.first { $0.id == lookID } ?? Self.looks[0] }
    var dark: Bool { selected.dark }
    var accent: Color { selected.accent }
    var background: Color { selected.background }
    var surface: Color { selected.surface }
    var ink: Color { selected.ink }
}

fileprivate struct LookColors {
    let accent: UInt32
    let background: UInt32
    let surface: UInt32
    let ink: UInt32
}

extension Color {
    fileprivate init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
    }
}

extension Look {
    var isYouth: Bool { ["streetart", "bubblegum", "speed"].contains(id) }
    var animationEligible: Bool { id != "classic" && !isYouth }
    func motionAllowed(enabled: Bool, reduceMotion: Bool, sceneActive: Bool) -> Bool {
        animationEligible && enabled && !reduceMotion && sceneActive
    }
    var fontDesign: Font.Design { ["hacker", "pixel"].contains(id) ? .monospaced : .default }
    var muted: Color { Color(hex: semantic[0]) }
    var secondary: Color { Color(hex: semantic[1]) }
    var container: Color { Color(hex: semantic[2]) }
    var outline: Color { Color(hex: semantic[3]) }
    var decoration: Color {
        Color(hex: id == "streetart" ? 0xFF6A00 : id == "bubblegum" ? 0xFF8BC6 : colors.accent)
    }
    private var semantic: [UInt32] {
        switch id {
        case "hacker": return [0xB3D9A7, 0xB1FF83, 0x183A10, 0x355D2B]
        case "social": return [0x75516A, 0x93421E, 0xFFDCEB, 0xDEC0D1]
        case "streamer": return [0xBCCAD9, 0x79E7FA, 0x34254B, 0x516174]
        case "orbit": return [0xBCCAD9, 0xFFBED3, 0x203753, 0x516174]
        case "pixel": return [0x4D5F4B, 0x75522E, 0xD3E9B8, 0xB4C79F]
        case "bayern": return [0xD4E4F4, 0x8BC5FF, 0x003B70, 0x6485A6]
        case "streetart": return [0x40382E, 0x171511, 0xFFDEC3, 0x40382E]
        case "bubblegum": return [0x50265D, 0x255568, 0xFFD8ED, 0x50265D]
        case "speed": return [0xD7DCE0, 0xFFFFFF, 0x303B20, 0xD7DCE0]
        default: return [0x535E79, 0xAF2347, 0xE1E8FF, 0xD6DCF0]
        }
    }
}

/// Cut corners mirror the Android card vocabulary; native controls retain their hit targets.
struct LookCardShape: Shape {
    let look: Look
    var radius: CGFloat = 18
    func path(in rect: CGRect) -> Path {
        if ["hacker", "pixel"].contains(look.id) { return Path(rect) }
        let cut: CGFloat = look.id == "speed" ? 14 : look.id == "streetart" ? 8 : radius / 2
        if ["speed", "streetart", "streamer"].contains(look.id) {
            let c = min(cut, min(rect.width, rect.height) / 2)
            let alternate = look.id == "streetart"
            let tl: CGFloat = alternate ? 0 : c
            let tr: CGFloat = alternate ? c : 0
            let br: CGFloat = alternate ? 0 : c
            let bl: CGFloat = alternate ? c : 0
            var p = Path()
            p.move(to: CGPoint(x: rect.minX + tl, y: rect.minY))
            for point in [CGPoint(x: rect.maxX - tr, y: rect.minY), CGPoint(x: rect.maxX, y: rect.minY + tr),
                          CGPoint(x: rect.maxX, y: rect.maxY - br), CGPoint(x: rect.maxX - br, y: rect.maxY),
                          CGPoint(x: rect.minX + bl, y: rect.maxY), CGPoint(x: rect.minX, y: rect.maxY - bl),
                          CGPoint(x: rect.minX, y: rect.minY + tl)] { p.addLine(to: point) }
            p.closeSubpath()
            return p
        }
        return Path(roundedRect: rect, cornerRadius: look.id == "bubblegum" ? radius + 8 : radius)
    }
}

struct LookCardBackground: View {
    @EnvironmentObject private var appearance: AppearanceStore
    var body: some View {
        let look = appearance.selected
        LookCardShape(look: look).fill(look.surface)
            .overlay {
                LookCardShape(look: look).stroke(
                    look.id == "classic" ? .clear : look.accent.opacity(look.id == "hacker" ? 0.65 : 0.38),
                    lineWidth: look.id == "pixel" ? 2 : 1)
            }
    }
}

private struct LookMutedModifier: ViewModifier {
    @EnvironmentObject private var appearance: AppearanceStore
    func body(content: Content) -> some View { content.foregroundStyle(appearance.selected.muted) }
}

private struct LookScreenModifier: ViewModifier {
    @EnvironmentObject private var appearance: AppearanceStore
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .foregroundStyle(appearance.ink)
            .lookFontDesign(appearance.selected.fontDesign)
            .background { LookBackdrop().ignoresSafeArea() }
            .toolbarBackground(appearance.surface, for: .navigationBar, .tabBar)
    }
}

extension View {
    func lookScreen() -> some View { modifier(LookScreenModifier()) }
    func lookMuted() -> some View { modifier(LookMutedModifier()) }
    @ViewBuilder func lookFontDesign(_ design: Font.Design) -> some View {
        if #available(iOS 16.1, *) { fontDesign(design) }
        else { font(.system(.body, design: design)) }
    }
}

struct LookBackdrop: View {
    @EnvironmentObject private var appearance: AppearanceStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        let look = appearance.selected
        let moving = look.motionAllowed(enabled: appearance.animations, reduceMotion: reduceMotion,
                                        sceneActive: scenePhase == .active)
        Group {
            if moving {
                TimelineView(.animation(minimumInterval: 1.0 / 15)) { context in
                    LookArtwork(look: look, phase: context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 24) / 24)
                }
            } else {
                LookArtwork(look: look, phase: 0.2)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// All motifs are code-native. The arena deliberately has no external club crest.
struct LookArtwork: View {
    let look: Look
    var phase: Double = 0.2
    var showcase = false
    var body: some View {
        Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(look.background))
            switch look.id {
            case "hacker": matrix(&context, size)
            case "social": social(&context, size)
            case "streamer": streamer(&context, size)
            case "orbit": orbit(&context, size)
            case "pixel": pixel(&context, size)
            case "bayern": arena(&context, size)
            case "streetart", "bubblegum", "speed": youth(&context, size)
            default: break
            }
        }
        .clipped()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
    private func line(_ context: inout GraphicsContext, _ color: Color, _ start: CGPoint, _ end: CGPoint, _ width: CGFloat = 1) {
        var p = Path(); p.move(to: start); p.addLine(to: end)
        context.stroke(p, with: .color(color), lineWidth: width)
    }
    private func circle(_ context: inout GraphicsContext, _ color: Color, _ x: CGFloat, _ y: CGFloat, _ r: CGFloat, stroke: CGFloat = 0) {
        let p = Path(ellipseIn: CGRect(x: x-r, y: y-r, width: r*2, height: r*2))
        if stroke > 0 { context.stroke(p, with: .color(color), lineWidth: stroke) }
        else { context.fill(p, with: .color(color)) }
    }
    private func matrix(_ context: inout GraphicsContext, _ size: CGSize) {
        let spacing: CGFloat = showcase ? 11 : 18
        let glyphs = Array("01アイウエオ<>:/{}[]")
        // Resolve each glyph only once per frame and reuse it across the rain columns.
        let symbols = glyphs.map { context.resolve(Text(String($0)).font(.system(size: showcase ? 9 : 12, design: .monospaced)).foregroundColor(look.accent)) }
        let rows = Int(size.height / spacing) + 2
        for col in 0...Int(size.width / spacing) {
            let drift = CGFloat((phase * Double(2 + col % 3) + Double(col) * 0.173).truncatingRemainder(dividingBy: 1))
            for row in 0..<rows {
                var ink = context
                ink.opacity = Double((row + col * 3) % 11 == 0 ? 0.42 : 0.10)
                let y = (CGFloat(row) * spacing + drift * size.height).truncatingRemainder(dividingBy: max(1, size.height))
                ink.draw(symbols[(row * 7 + col * 3) % symbols.count], at: CGPoint(x: CGFloat(col) * spacing, y: y))
            }
        }
        for y in stride(from: CGFloat(0), to: size.height, by: 4) {
            line(&context, .black.opacity(0.22), CGPoint(x: 0, y: y), CGPoint(x: size.width, y: y))
        }
    }
    private func social(_ context: inout GraphicsContext, _ size: CGSize) {
        for i in 0..<6 {
            let angle = phase * .pi * 2 + Double(i)
            let center = CGPoint(x: size.width * CGFloat(i % 3) / 2 + CGFloat(sin(angle)) * size.width * 0.09,
                                 y: size.height * CGFloat(i) / 6 + CGFloat(cos(angle)) * 32)
            let color = Color(hex: i % 2 == 0 ? 0xF66BA4 : 0xFFAA70)
            let r = size.width * 0.65
            context.fill(Path(ellipseIn: CGRect(x: center.x-r, y: center.y-r, width: r*2, height: r*2)),
                         with: .radialGradient(Gradient(colors: [color.opacity(0.22), .clear]), center: center, startRadius: 0, endRadius: r))
            circle(&context, color.opacity(0.22), center.x, center.y, showcase ? 13 : 24, stroke: 1)
        }
    }
    private func streamer(_ context: inout GraphicsContext, _ size: CGSize) {
        for i in 0..<7 {
            let y = CGFloat((Double(i) / 7 + phase).truncatingRemainder(dividingBy: 1)) * (size.height + size.width) - size.width
            let color = Color(hex: i % 2 == 0 ? 0xB965FF : 0x4FE4F1)
            let a = CGPoint(x: 0, y: y), b = CGPoint(x: size.width, y: y + size.width * 0.6)
            line(&context, color.opacity(0.07), a, b, 16)
            line(&context, color.opacity(0.35), a, b, 1)
        }
    }
    private func orbit(_ context: inout GraphicsContext, _ size: CGSize) {
        let center = CGPoint(x: size.width * 0.85, y: size.height * 0.25)
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .radialGradient(
            Gradient(colors: [Color(hex: 0x4B57AE).opacity(0.3), .clear]), center: center, startRadius: 0, endRadius: size.width))
        for i in 0..<64 {
            let x = (CGFloat((i * 73 + 19) % 137) / 137 + CGFloat(sin(phase * .pi * 2)) * 0.015) * size.width
            let y = (CGFloat((i * i * 31 + 17) % 149) / 149 + CGFloat(cos(phase * .pi * 2)) * 0.015) * size.height
            circle(&context, look.accent.opacity(i % 5 == 0 ? 0.65 : 0.3), x, y, i % 5 == 0 ? 1.6 : 0.8)
        }
    }
    private func pixel(_ context: inout GraphicsContext, _ size: CGSize) {
        let block: CGFloat = showcase ? 8 : 22
        for i in 0..<5 {
            let x = CGFloat((Double(i) * 0.27 + phase * 0.15).truncatingRemainder(dividingBy: 1)) * size.width
            let y = size.height * (0.1 + CGFloat(i % 3) * 0.13)
            context.fill(Path(CGRect(x: x, y: y, width: block * 4, height: block)), with: .color(.white.opacity(0.75)))
            context.fill(Path(CGRect(x: x+block, y: y-block, width: block*2, height: block)), with: .color(.white.opacity(0.75)))
        }
        for i in 0...Int(size.width / block) {
            let top = size.height - min(100, size.height * 0.2) - block * CGFloat(2 + (i / 3) % 3)
            context.fill(Path(CGRect(x: CGFloat(i)*block, y: top, width: block, height: size.height-top)), with: .color(Color(hex: 0x876240).opacity(0.25)))
            context.fill(Path(CGRect(x: CGFloat(i)*block, y: top, width: block, height: block/3)), with: .color(Color(hex: 0x5D963E).opacity(0.55)))
            context.fill(Path(CGRect(x: CGFloat(i)*block+block/4, y: top+block, width: block/4, height: block/4)), with: .color(Color(hex: 0xB98F61).opacity(0.5)))
        }
    }
    private func arena(_ context: inout GraphicsContext, _ size: CGSize) {
        let w = size.width/4, h = size.height/4
        for row in -1...5 { for col in -1...5 {
            let x = CGFloat(col)*w + (row % 2 == 0 ? 0 : w/2), y = CGFloat(row)*h
            var p = Path(); p.move(to: CGPoint(x: x, y: y-h/2))
            p.addQuadCurve(to: CGPoint(x: x+w/2, y: y), control: CGPoint(x: x+w*0.25, y: y-h*0.18))
            p.addQuadCurve(to: CGPoint(x: x, y: y+h/2), control: CGPoint(x: x+w*0.25, y: y+h*0.18))
            p.addQuadCurve(to: CGPoint(x: x-w/2, y: y), control: CGPoint(x: x-w*0.25, y: y+h*0.18))
            p.addQuadCurve(to: CGPoint(x: x, y: y-h/2), control: CGPoint(x: x-w*0.25, y: y-h*0.18))
            let glow = 0.75 + 0.15 * sin(phase * .pi * 2 + Double(col)*0.6 + Double(row)*0.3)
            context.fill(p, with: .radialGradient(Gradient(colors: [Color(hex: 0xFF1745).opacity(glow), Color(hex: 0xBA0025), Color(hex: 0x42000D)]), center: CGPoint(x: x, y: y), startRadius: 0, endRadius: w*0.62))
            context.stroke(p, with: .color(Color(hex: 0x150007)), lineWidth: 2)
        }}
        line(&context, Color(hex: 0xFF8699), CGPoint(x: size.width*0.6, y: 0), CGPoint(x: size.width, y: size.height*0.46), 2)
        if !showcase {
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(
                Gradient(stops: [.init(color: .black.opacity(0.45), location: 0), .init(color: .black.opacity(0.92), location: 0.07),
                                 .init(color: .black.opacity(0.92), location: 0.93), .init(color: .black.opacity(0.45), location: 1)]),
                startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0)))
        }
    }
    private func youth(_ context: inout GraphicsContext, _ size: CGSize) {
        let gutter: CGFloat = showcase ? min(20, size.width*0.2) : 10
        for i in 0..<(look.id == "bubblegum" ? 14 : 24) {
            let y = (CGFloat(i)+0.5) * size.height / (look.id == "bubblegum" ? 14 : 24)
            switch look.id {
            case "streetart":
                circle(&context, look.decoration, gutter/2, y, gutter*(0.18+CGFloat(i%3)*0.1))
                line(&context, look.ink, CGPoint(x: size.width-gutter, y: y), CGPoint(x: size.width, y: y-gutter), 2)
            case "bubblegum":
                circle(&context, i%2 == 0 ? look.decoration : Color(hex: 0x91DBF3), i%2 == 0 ? 0 : size.width, y, gutter*0.6, stroke: 3)
            default:
                line(&context, look.decoration, CGPoint(x: 0, y: y+gutter), CGPoint(x: gutter, y: y), 3)
                line(&context, .white, CGPoint(x: size.width-gutter, y: y+gutter), CGPoint(x: size.width, y: y), 2)
            }
        }
    }
}

/// Reserved decoration space keeps paint, stickers and racing stripes away from labels.
struct LookRibbon: View {
    let look: Look
    var body: some View {
        Canvas { context, size in
            if look.id == "streetart" {
                var p = Path(); p.move(to: CGPoint(x: 8, y: 6))
                for point in [CGPoint(x: size.width*0.68, y: 3), CGPoint(x: size.width*0.61, y: 12), CGPoint(x: 12, y: 16)] { p.addLine(to: point) }
                p.closeSubpath(); context.fill(p, with: .color(look.decoration))
                for i in 0..<5 {
                    context.fill(Path(CGRect(x: 20+i*13, y: 10, width: 3, height: 7+i%3*2)), with: .color(look.decoration))
                }
            } else if look.id == "bubblegum" {
                for (i, color) in [look.decoration, Color(hex: 0xB4EDFF), Color(hex: 0xFFD8ED)].enumerated() {
                    let rect = CGRect(x: 14+i*19, y: 2, width: 20, height: 20)
                    context.fill(Path(ellipseIn: rect), with: .color(color))
                    context.stroke(Path(ellipseIn: rect), with: .color(look.ink), lineWidth: 2)
                }
                context.draw(Text("✦").font(.system(size: 20)).foregroundColor(look.ink), at: CGPoint(x: size.width-24, y: 12))
            } else if look.id == "speed" {
                for i in 0..<7 {
                    let x = size.width - CGFloat(20+i*17)
                    var p = Path(); p.move(to: CGPoint(x: x, y: 18)); p.addLine(to: CGPoint(x: x+12, y: 4))
                    context.stroke(p, with: .color(i < 2 ? .white : look.decoration), lineWidth: 5)
                }
            }
        }.frame(height: 24).accessibilityHidden(true).allowsHitTesting(false)
    }
}

struct LookPreview: View {
    let look: Look
    var body: some View {
        ZStack {
            LookArtwork(look: look, showcase: true)
            if look.isYouth { LookRibbon(look: look).padding(.horizontal, 8) }
        }
        .clipShape(LookCardShape(look: look, radius: 10))
        .overlay { LookCardShape(look: look, radius: 10).stroke(look.outline.opacity(0.6), lineWidth: 1) }
        .accessibilityHidden(true)
    }
}
