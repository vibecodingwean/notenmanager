import XCTest

final class OnboardingUITests: XCTestCase {
    func testEmptyInstallDemoWorkspaceSurvivesRestartAndCanBeDeleted() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        XCTAssertTrue(app.buttons["openSettingsOnboarding"].waitForExistence(timeout: 15))
        app.buttons["openSettingsOnboarding"].tap()
        app.buttons["loadDemo"].tap()
        XCTAssertTrue(app.staticTexts["demoBadge"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.tabBars.buttons["Meine Noten"].exists)
        attach("Demo-Dein-Tag", app: app)
        XCTAssertTrue(app.buttons["openTimetable"].waitForExistence(timeout: 10))
        app.buttons["openTimetable"].tap()
        XCTAssertTrue(app.buttons["lesson-1-0-1"].waitForExistence(timeout: 10))
        attach("Demo-Stundenplan", app: app)
        app.navigationBars.buttons.firstMatch.tap()
        app.tabBars.buttons["Meine Noten"].tap()
        attach("Demo-Noten", app: app)
        let math = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Mathematik")).firstMatch
        for _ in 0..<10 where !math.isHittable { app.swipeUp() }
        XCTAssertTrue(math.isHittable)
        math.tap()
        let exam = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Brüche und Gleichungen")).firstMatch
        XCTAssertTrue(exam.waitForExistence(timeout: 10))
        exam.tap()
        XCTAssertTrue(app.buttons["Mathe · fiktiver Scan · 18/20.png"].waitForExistence(timeout: 10))
        attach("Demo-Mathe-Scan", app: app)

        app.terminate()
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(app.staticTexts["demoBadge"].waitForExistence(timeout: 15))
        app.buttons["returnToReal"].tap()
        XCTAssertTrue(app.buttons["onboardingNext"].waitForExistence(timeout: 10))
        app.buttons["openSettingsOnboarding"].tap()
        app.buttons["deleteDemo"].tap()
        app.buttons["confirmDeleteDemo"].firstMatch.tap()
        XCTAssertTrue(app.buttons["loadDemo"].exists)
    }

    func testMathFictionalScanOpensInPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        XCTAssertTrue(app.buttons["openSettingsOnboarding"].waitForExistence(timeout: 15))
        app.buttons["openSettingsOnboarding"].tap()
        app.buttons["loadDemo"].tap()
        app.tabBars.buttons["Meine Noten"].tap()

        let math = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Mathematik")).firstMatch
        for _ in 0..<10 where !math.isHittable { app.swipeUp() }
        XCTAssertTrue(math.isHittable)
        math.tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Brüche und Gleichungen")).firstMatch.tap()
        app.buttons["Mathe · fiktiver Scan · 18/20.png"].tap()
        let mathPreview = app.navigationBars["Mathe · fiktiver Scan · 18/20.png"]
        XCTAssertTrue(mathPreview.waitForExistence(timeout: 10))
        XCTAssertTrue(app.images["scanImagePreview"].exists)
        attach("Demo-Mathe-Bildvorschau", app: app)
    }

    func testEnglishFictionalScanOpensInPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        XCTAssertTrue(app.buttons["openSettingsOnboarding"].waitForExistence(timeout: 15))
        app.buttons["openSettingsOnboarding"].tap()
        app.buttons["loadDemo"].tap()
        app.tabBars.buttons["Meine Noten"].tap()
        let english = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Englisch")).firstMatch
        for _ in 0..<10 where !english.isHittable { app.swipeUp() }
        XCTAssertTrue(english.isHittable)
        english.tap()
        let exam = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Simple Past")).firstMatch
        for _ in 0..<10 where !exam.isHittable { app.swipeUp() }
        XCTAssertTrue(exam.isHittable)
        exam.tap()
        app.buttons["Englisch · fiktiver Scan · 12/12.png"].tap()
        XCTAssertTrue(app.navigationBars["Englisch · fiktiver Scan · 12/12.png"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.images["scanImagePreview"].exists)
        attach("Demo-Englisch-Bildvorschau", app: app)
    }

    private func attach(_ name: String, app: XCUIApplication) {
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = name
        image.lifetime = .keepAlways
        add(image)
    }

    private func createDefaultProfile(_ app: XCUIApplication) {
        XCTAssertTrue(app.buttons["onboardingNext"].waitForExistence(timeout: 15))
        app.buttons["onboardingNext"].tap()
        app.buttons["createProfile"].tap()
        XCTAssertTrue(app.tabBars.buttons["Meine Noten"].waitForExistence(timeout: 10))
    }

    private func scrollTo(_ identifier: String, app: XCUIApplication) -> Bool {
        let target = app.descendants(matching: .any).matching(identifier: identifier).firstMatch
        for _ in 0..<12 {
            if target.exists { return true }
            app.swipeUp()
        }
        return target.exists
    }

    func testProfilePersistsAfterColdLaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        XCTAssertTrue(app.buttons["onboardingNext"].waitForExistence(timeout: 15))
        app.buttons["onboardingNext"].tap()
        app.buttons["createProfile"].tap()
        XCTAssertTrue(app.tabBars.buttons["Meine Noten"].waitForExistence(timeout: 10))
        app.terminate()
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Meine Noten"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Meine Noten"].tap()
        XCTAssertTrue(app.staticTexts["Deutsch"].waitForExistence(timeout: 10))
    }

    func testTimetableSelectionPersistsAfterColdLaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        XCTAssertTrue(app.buttons["onboardingNext"].waitForExistence(timeout: 15))
        app.buttons["onboardingNext"].tap()
        app.buttons["createProfile"].tap()
        XCTAssertTrue(app.buttons["openTimetable"].waitForExistence(timeout: 10))
        app.buttons["openTimetable"].tap()
        XCTAssertTrue(app.buttons["lesson-1-0-1"].waitForExistence(timeout: 10))
        app.buttons["lesson-1-0-1"].tap()
        app.buttons["Deutsch"].tap()
        let save = app.buttons["saveTimetable"]
        guard save.waitForExistence(timeout: 5) else { XCTFail("Stundenplan-Speichern fehlt"); return }
        save.tap()
        app.terminate()
        app.launchArguments = []
        app.launch()
        app.buttons["openTimetable"].tap()
        XCTAssertTrue(app.staticTexts["Deutsch"].waitForExistence(timeout: 10))
    }

    func testVisibleRoutesAndUnknownOfficialResults() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        createDefaultProfile(app)
        attach("01-Today", app: app)
        app.tabBars.buttons["Meine Noten"].tap()
        attach("02-Meine-Noten", app: app)
        let official = app.buttons["openOfficial"]
        guard official.waitForExistence(timeout: 5) else { XCTFail("Zeugnis-Zugang fehlt"); return }
        official.tap()
        let german = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Deutsch,")).firstMatch
        guard german.waitForExistence(timeout: 5) else { XCTFail("Amtliche Deutschnote fehlt"); return }
        german.tap()
        let note = app.buttons["Note 2"]
        guard note.waitForExistence(timeout: 5) else { XCTFail("Note 2 fehlt"); return }
        note.tap()
        XCTAssertTrue(scrollTo("ruleResult_INCOMPLETE", app: app))
        attach("03-Zeugnis-Abschluss", app: app)
        XCTAssertTrue(scrollTo("ruleResult_UNSUPPORTED", app: app))
        app.navigationBars.buttons.firstMatch.tap()
        app.tabBars.buttons["Dein Tag"].tap()
        app.buttons["openTimetable"].tap()
        attach("04-Stundenplan", app: app)
    }

    func testLookSelectionSurvivesColdLaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        createDefaultProfile(app)
        let lookShortcut = app.buttons["openAppearanceHome"]
        XCTAssertTrue(lookShortcut.waitForExistence(timeout: 5))
        lookShortcut.tap()
        let hacker = app.buttons["appearance_hacker"]
        XCTAssertTrue(hacker.waitForExistence(timeout: 5))
        hacker.tap()
        XCTAssertTrue(hacker.isSelected)
        attach("05-Hacker-Look", app: app)
        app.terminate()
        app.launchArguments = []
        app.launch()
        app.tabBars.buttons["Meine Noten"].tap()
        let gradesShortcut = app.buttons["openAppearance"]
        XCTAssertTrue(gradesShortcut.waitForExistence(timeout: 5))
        gradesShortcut.tap()
        XCTAssertTrue(app.buttons["appearance_hacker"].isSelected)
    }

    func testAllLookPreviewsAndThemedRoutes() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        createDefaultProfile(app)
        app.tabBars.buttons["Meine Noten"].tap()
        XCTAssertTrue(scrollTo("Look auswählen", app: app))
        app.buttons["Look auswählen"].tap()
        for id in ["classic", "hacker", "social", "streamer", "orbit", "pixel", "bayern", "streetart", "bubblegum", "speed"] {
            let button = app.buttons["appearance_\(id)"]
            for _ in 0..<8 {
                if button.exists && button.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(button.isHittable, id)
            button.tap()
            XCTAssertTrue(button.isSelected, id)
            attach("Theme-\(id)-Picker", app: app)
            app.tabBars.buttons["Dein Tag"].tap()
            XCTAssertTrue(app.buttons["openTimetable"].waitForExistence(timeout: 5))
            attach("Theme-\(id)-Today", app: app)
            app.tabBars.buttons["Lernen"].tap()
            attach("Theme-\(id)-Learning", app: app)
            app.tabBars.buttons["Meine Noten"].tap()
        }
        XCTAssertTrue(scrollTo("themeAnimations", app: app))
        let toggle = app.switches["themeAnimations"]
        XCTAssertTrue(toggle.exists)
        toggle.tap()
        let savedAnimationValue = toggle.value as? String
        app.terminate()
        app.launchArguments = []
        app.launch()
        app.tabBars.buttons["Meine Noten"].tap()
        XCTAssertTrue(scrollTo("Look auswählen", app: app))
        app.buttons["Look auswählen"].tap()
        XCTAssertTrue(scrollTo("appearance_speed", app: app))
        XCTAssertTrue(app.buttons["appearance_speed"].isSelected)
        XCTAssertTrue(scrollTo("themeAnimations", app: app))
        XCTAssertEqual(app.switches["themeAnimations"].value as? String, savedAnimationValue)
    }

    func testDefaultGymnasiumSubjectRulesCanBeEditedAndPersisted() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        createDefaultProfile(app)
        app.tabBars.buttons["Meine Noten"].tap()
        let german = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Deutsch")).firstMatch
        guard german.waitForExistence(timeout: 5) else { XCTFail("Deutsch-Fach fehlt"); return }
        german.tap()
        XCTAssertTrue(app.staticTexts["Schulaufgabenanzahl fehlt. Der rechnerische Fachschnitt bleibt bis zur Eingabe offen."].exists)
        app.buttons["Fach und Gewichtungen bearbeiten"].tap()
        let annual = app.textFields["Schulaufgaben im Schuljahr (0–12)"]
        guard annual.waitForExistence(timeout: 5) else { XCTFail("Schulaufgabenfeld fehlt"); return }
        annual.tap()
        annual.typeText("4")
        app.buttons["Speichern"].tap()
        XCTAssertFalse(app.staticTexts["Schulaufgabenanzahl fehlt. Der rechnerische Fachschnitt bleibt bis zur Eingabe offen."].exists)
        app.terminate()
        app.launchArguments = []
        app.launch()
        app.tabBars.buttons["Meine Noten"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Deutsch")).firstMatch.tap()
        app.buttons["Fach und Gewichtungen bearbeiten"].tap()
        XCTAssertEqual(app.textFields["Schulaufgaben im Schuljahr (0–12)"].value as? String, "4")
    }

    func testPrimarySchoolAssessmentSavesProbeWithoutChangingKindPicker() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingReset"]
        app.launch()
        let schoolPicker = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Schulart")).firstMatch
        XCTAssertTrue(schoolPicker.waitForExistence(timeout: 15))
        schoolPicker.tap()
        app.buttons["Grundschule"].tap()
        app.buttons["onboardingNext"].tap()
        app.buttons["createProfile"].tap()
        XCTAssertTrue(app.tabBars.buttons["Meine Noten"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Meine Noten"].tap()
        let german = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Deutsch")).firstMatch
        XCTAssertTrue(german.waitForExistence(timeout: 5))
        german.tap()
        app.buttons["Leistungsnachweis hinzufügen"].tap()
        let title = app.textFields["assessmentTitle"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText("Neue Probe")
        XCTAssertTrue(app.buttons["assessmentKind"].label.contains("Probe"))
        app.buttons["saveAssessment"].tap()
        let saved = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Neue Probe")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        saved.tap()
        app.buttons["Bearbeiten"].tap()
        XCTAssertTrue(app.buttons["assessmentKind"].label.contains("Probe"))
    }

    func testUnreadableSchoolDataOpensBackupRecovery() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestingCorruptSchoolData"]
        app.launch()
        let alert = app.alerts["Hinweis"]
        if alert.waitForExistence(timeout: 5) { alert.buttons["OK"].tap() }
        XCTAssertFalse(app.buttons["onboardingNext"].exists)
        let recovery = app.buttons["openRecoveryBackup"]
        XCTAssertTrue(recovery.waitForExistence(timeout: 10))
        recovery.tap()
        let password = app.secureTextFields["Backup-Passwort (mindestens 8 Zeichen)"]
        XCTAssertTrue(password.waitForExistence(timeout: 5))
        password.tap()
        password.typeText("recovery-pass")
        let confirmation = app.secureTextFields["Passwort wiederholen"]
        confirmation.tap()
        confirmation.typeText("recovery-pass")
        XCTAssertTrue(app.buttons["Backup wiederherstellen"].isEnabled)
        XCTAssertFalse(app.buttons["Backup erstellen"].isEnabled)
    }
}
