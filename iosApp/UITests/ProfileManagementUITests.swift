import XCTest

final class ProfileManagementUITests: XCTestCase {
    private func scrollTo(_ label: String, in app: XCUIApplication) -> XCUIElement {
        let button = app.buttons[label]
        for _ in 0..<10 {
            if button.exists && button.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(button.isHittable, "\(label) ist nicht erreichbar")
        return button
    }

    func testCreateAndDeleteProfilesIncludingLastProfile() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()

        XCTAssertTrue(app.buttons["onboardingNext"].waitForExistence(timeout: 15))
        app.buttons["onboardingNext"].tap()
        app.buttons["createProfile"].tap()
        XCTAssertTrue(app.tabBars.buttons["Meine Noten"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Meine Noten"].tap()

        scrollTo("Weiteres Profil", in: app).tap()
        XCTAssertTrue(app.buttons["onboardingNext"].waitForExistence(timeout: 5))
        app.buttons["onboardingNext"].tap()
        app.buttons["createProfile"].tap()
        XCTAssertTrue(scrollTo("Weiteres Profil", in: app).waitForExistence(timeout: 5),
                      "Nach dem Anlegen muss das Formular schließen")

        let deleteButtons = app.buttons.matching(identifier: "Gymnasium 8. Klasse löschen")
        XCTAssertEqual(deleteButtons.count, 2)
        let profileButtons = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "profileSelect_"))
        XCTAssertEqual(profileButtons.count, 2)
        profileButtons.element(boundBy: 0).tap()
        XCTAssertFalse(app.alerts.firstMatch.exists, "Profilwahl darf keinen Löschdialog öffnen")
        XCTAssertEqual(profileButtons.element(boundBy: 0).value as? String, "Aktiv")
        deleteButtons.firstMatch.tap()
        XCTAssertTrue(app.alerts["Gymnasium 8. Klasse löschen?"].waitForExistence(timeout: 5))
        app.alerts.buttons["Abbrechen"].tap()
        XCTAssertEqual(deleteButtons.count, 2)
        XCTAssertEqual(profileButtons.element(boundBy: 0).value as? String, "Aktiv",
                       "Abbrechen darf das aktive Profil nicht ändern")

        deleteButtons.firstMatch.tap()
        app.alerts.buttons["Profil löschen"].tap()
        XCTAssertEqual(deleteButtons.count, 1)
        XCTAssertEqual(profileButtons.firstMatch.value as? String, "Aktiv")
        deleteButtons.firstMatch.tap()
        app.alerts.buttons["Profil löschen"].tap()
        XCTAssertTrue(app.buttons["onboardingNext"].waitForExistence(timeout: 10),
                      "Nach dem letzten Profil muss das Onboarding wieder erscheinen")
        app.terminate()
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(app.buttons["onboardingNext"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.alerts["Hinweis"].waitForExistence(timeout: 2),
                       "Ein absichtlich leeres Profilarchiv darf keinen Datenfehler auslösen")
    }
}
