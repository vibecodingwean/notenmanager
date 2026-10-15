import XCTest
import SwiftUI
@testable import NotenManager

@MainActor
final class ThemeTests: XCTestCase {
    func testEveryAndroidLookKeepsItsStableIDAndPersistence() async {
        XCTAssertEqual(AppearanceStore.looks.map(\.id), ["classic", "hacker", "social", "streamer", "orbit", "pixel", "bayern", "streetart", "bubblegum", "speed"])
        let suite = "ThemeTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AppearanceStore(defaults: defaults)
        for look in AppearanceStore.looks {
            store.select(look.id)
            store.animations = false
            let restored = AppearanceStore(defaults: defaults)
            XCTAssertEqual(restored.lookID, look.id)
            XCTAssertFalse(restored.animations)
        }
        store.select("unknown")
        XCTAssertEqual(store.lookID, "speed")
        defaults.set("unknown", forKey: "appearance.look")
        XCTAssertEqual(AppearanceStore(defaults: defaults).lookID, "classic")
    }

    func testMotionGatesPreserveStaticYouthAndRespectAccessibilityAndLifecycle() async {
        for look in AppearanceStore.looks {
            for enabled in [false, true] {
                for reduceMotion in [false, true] {
                    for active in [false, true] {
                        let expected = !["classic", "streetart", "bubblegum", "speed"].contains(look.id) && enabled && !reduceMotion && active
                        XCTAssertEqual(look.motionAllowed(enabled: enabled, reduceMotion: reduceMotion, sceneActive: active), expected, look.id)
                    }
                }
            }
        }
    }

    func testCutCornersPreserveStraightCardEdges() async {
        for look in AppearanceStore.looks where ["streetart", "streamer", "speed"].contains(look.id) {
            let path = LookCardShape(look: look).path(in: CGRect(x: 0, y: 0, width: 300, height: 100))
            XCTAssertTrue(path.contains(CGPoint(x: 150, y: 1)), look.id)
            XCTAssertTrue(path.contains(CGPoint(x: 150, y: 99)), look.id)
            XCTAssertTrue(path.contains(CGPoint(x: 1, y: 50)), look.id)
            XCTAssertTrue(path.contains(CGPoint(x: 299, y: 50)), look.id)
        }
    }

    func testSemanticTextContrastOnCardsAndBackgrounds() async {
        func luminance(_ color: Color) -> Double {
            var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
            UIColor(color).getRed(&red, green: &green, blue: &blue, alpha: &alpha)
            let channels = [red, green, blue].map { value -> Double in
                let c = Double(value)
                return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
            }
            return zip(channels, [0.2126, 0.7152, 0.0722]).map(*).reduce(0, +)
        }
        for look in AppearanceStore.looks {
            for text in [look.ink, look.muted] {
                for surface in [look.surface, look.background] {
                    let a = luminance(text), b = luminance(surface)
                    XCTAssertGreaterThanOrEqual((max(a, b) + 0.05) / (min(a, b) + 0.05), 4.5, look.id)
                }
            }
        }
    }

    func testStaticPreviewRendersEveryLookWithDistinctArtwork() async throws {
        var images = Set<Data>()
        for look in AppearanceStore.looks {
            let renderer = ImageRenderer(content: LookPreview(look: look).frame(width: 240, height: 180))
            renderer.scale = 1
            let image = try XCTUnwrap(renderer.uiImage, look.id)
            XCTAssertEqual(image.size.width, 240)
            let data = try XCTUnwrap(image.pngData())
            images.insert(data)
            let attachment = XCTAttachment(image: image)
            attachment.name = "Preview-\(look.id)"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertEqual(images.count, 10, "Every look must render distinct static artwork")
    }

    func testYouthAndClassicArtworkIsUnchangedByAnimationPhase() async throws {
        for look in AppearanceStore.looks where !look.animationEligible {
            func render(_ phase: Double) throws -> Data {
                let renderer = ImageRenderer(content: LookArtwork(look: look, phase: phase).frame(width: 240, height: 180))
                renderer.scale = 1
                return try XCTUnwrap(renderer.uiImage?.pngData())
            }
            XCTAssertEqual(try render(0), try render(0.5), look.id)
        }
    }
}
