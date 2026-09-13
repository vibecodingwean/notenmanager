package de.streberalarm.core

data class PassingAdvice(val title: String, val message: String, val canRelax: Boolean = false)

/** Coaching uses the same relevant-subject checks as the lamp, never the overall mean alone. */
object PassingCoach {
    fun advice(p: Profile, d: SchoolData, examSoon: Boolean): PassingAdvice {
        if (p.school == School.GRUNDSCHULE)
            return PassingAdvice(
                "Dein nächster Schulweg",
                "Finde mit deiner Familie und deiner Lehrkraft eine Schule, die zu dir passt. Bei Meine Noten siehst du eure Möglichkeiten. Jeder Weg kann zu dir passen.",
            )
        val report = GradeOverviewCalculator.calculate(p, d)
        return when (report.light) {
            TrafficLight.GREEN ->
                if (examSoon)
                    PassingAdvice(
                        "Du bist gut dabei",
                        "Die eingetragenen Vorrückungsfächer passen gerade. Deine nächste Probe ist bald: eine kurze Runde zum Absichern reicht als nächster Schritt.",
                    )
                else
                    PassingAdvice(
                        "Läuft bei dir",
                        "Die eingetragenen Vorrückungsfächer passen gerade. Wenn für morgen nichts mehr offen ist, ist Feierabend drin. Du brauchst keinen Einser-Schnitt.",
                        true,
                    )
            TrafficLight.AMBER ->
                PassingAdvice(
                    "Knapp dran – gezielt absichern",
                    "Du musst nicht überall besser werden. " +
                        (report.improvements.firstOrNull()?.let { plan ->
                            "Nächstes Ziel im Zeugnis: " +
                                plan.goals.joinToString(" + ") { "${it.name} auf ${it.grade}" } +
                                "."
                        }
                            ?: "Kümmere dich zuerst um das schwächste Vorrückungsfach. Die Notenseite zeigt dir, worauf es ankommt."),
                )
            TrafficLight.RED ->
                PassingAdvice(
                    "Schritt für Schritt durchkommen",
                    "Dein Schnitt allein sagt nicht alles. " +
                        (report.improvements.firstOrNull()?.let { plan ->
                            "Zum Absichern im Zeugnis: " +
                                plan.goals.joinToString(" + ") { "${it.name} auf ${it.grade}" } +
                                ". Für heute ein Thema daraus nehmen – nicht alles auf einmal."
                        }
                            ?: "Schau bei Meine Noten, welche Fächer und Ausgleichswege dir helfen können. Hol dir Unterstützung für den nächsten kleinen Schritt."),
                )
            TrafficLight.UNKNOWN ->
                PassingAdvice(
                    "Sicher durchkommen",
                    "Du musst nicht überall glänzen. " +
                        if (
                            p.points ||
                                p.grade >= 10 && p.school != School.GYMNASIUM ||
                                p.grade == 9 && p.school == School.MITTELSCHULE
                        )
                            "Für deinen Abschluss zählen einzelne Mindestbedingungen. Ein guter Schnitt allein gibt noch keine Entwarnung."
                        else
                            "Für eine verlässliche Einschätzung fehlen noch Angaben. Ergänze deine Fächer und die Noten, die du schon hast.",
                )
        }
    }
}
