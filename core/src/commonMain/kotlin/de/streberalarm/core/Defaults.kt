package de.streberalarm.core

object Defaults {
    val states =
        listOf(
            "Bayern",
            "Baden-Württemberg",
            "Berlin",
            "Brandenburg",
            "Bremen",
            "Hamburg",
            "Hessen",
            "Mecklenburg-Vorpommern",
            "Niedersachsen",
            "Nordrhein-Westfalen",
            "Rheinland-Pfalz",
            "Saarland",
            "Sachsen",
            "Sachsen-Anhalt",
            "Schleswig-Holstein",
            "Thüringen",
        )

    fun tracks(s: School) =
        when (s) {
            School.GRUNDSCHULE -> listOf("Grundschule")
            School.GYMNASIUM -> listOf("NTG", "SG", "HG", "MuG", "WWG", "SWG")
            School.REALSCHULE ->
                listOf(
                    "I",
                    "II",
                    "IIIa",
                    "IIIb · Kunst",
                    "IIIb · Werken",
                    "IIIb · Ernährung und Gesundheit",
                    "IIIb · Sozialwesen",
                )
            else -> listOf("Technik", "Wirtschaft und Kommunikation", "Ernährung und Soziales")
        }

    // Display labels are separate from stored track keys and legal/profile matching.
    // Source: https://www.km.bayern.de/lernen/schularten/realschule/schulprofil-und-schulleben
    fun trackLabel(school: School, track: String): String =
        when (school) {
            School.REALSCHULE ->
                when (track) {
                    "I" -> "I · Mathematik, Naturwissenschaften und Technik"
                    "II" -> "II · Wirtschaft und Rechnungswesen"
                    "IIIa" -> "IIIa · Fremdsprachen (Französisch)"
                    else -> track
                }
            School.GYMNASIUM ->
                when (track) {
                    "NTG" -> "NTG · Naturwissenschaftlich-technologisch"
                    "SG" -> "SG · Sprachlich"
                    "HG" -> "HG · Humanistisch"
                    "MuG" -> "MuG · Musisch"
                    "WWG" -> "WWG · Wirtschaftswissenschaftlich"
                    "SWG" -> "SWG · Sozialwissenschaftlich"
                    else -> track
                }
            else -> track
        }

    fun grades(school: School): IntRange =
        (if (school == School.GRUNDSCHULE) 3 else if (school == School.M_ZUG) 7 else 5)..(when (
                school
            ) {
                School.GRUNDSCHULE -> 4
                School.GYMNASIUM -> 13
                School.MITTELSCHULE -> 9
                else -> 10
            })

    fun subjects(p: Profile): List<Subject> {
        val names =
            when (p.school) {
                School.GRUNDSCHULE ->
                    listOf(
                        "Deutsch",
                        "Mathematik",
                        "Heimat- und Sachunterricht",
                        "Englisch",
                        "Ethik/Religion",
                        "Kunst",
                        "Musik",
                        "Sport",
                        "Werken und Gestalten",
                    )
                School.GYMNASIUM ->
                    listOf(
                        "Deutsch",
                        "Mathematik",
                        "Englisch",
                        "Latein",
                        "Geschichte",
                        "Geographie",
                        "Biologie",
                        "Physik",
                        "Chemie",
                        "Politik und Gesellschaft",
                        "Kunst",
                        "Musik",
                        "Sport",
                        "Ethik/Religion",
                    ) +
                        when (p.track) {
                            "HG" -> listOf("Griechisch")
                            "SG" -> listOf("Französisch")
                            "WWG" -> listOf("Wirtschaft und Recht")
                            else -> emptyList()
                        }
                School.REALSCHULE ->
                    listOf(
                        "IT · Informationstechnologie",
                        "Deutsch",
                        "Mathematik",
                        "Englisch",
                        "Geschichte",
                        "Geographie",
                        "Biologie",
                        "Physik",
                        "Chemie",
                        "Kunst",
                        "Musik",
                        "Sport",
                        "Ethik/Religion",
                    ) +
                        when (p.track) {
                            "II" -> listOf("Betriebswirtschaftslehre/Rechnungswesen")
                            "IIIa" -> listOf("Französisch")
                            else ->
                                if (p.track.startsWith("IIIb"))
                                    listOf(p.track.substringAfter(" · "))
                                else emptyList()
                        }
                else ->
                    listOf(
                        "Deutsch",
                        "Mathematik",
                        "Englisch",
                        "Natur und Technik",
                        "Geschichte/Politik/Geographie",
                        "Wirtschaft und Beruf",
                        p.track,
                        "Sport",
                        "Ethik/Religion",
                        "Kunst",
                    )
            }
        return names.distinct().map { name ->
            val core =
                if (p.school == School.GRUNDSCHULE) name in Transfer.NAMES
                else if (p.school == School.GYMNASIUM)
                    name in listOf("Deutsch", "Mathematik", "Englisch", "Latein", "Physik") ||
                        name ==
                            when (p.track) {
                                "NTG" -> "Chemie"
                                "MuG" -> "Musik"
                                "SWG" -> "Politik und Gesellschaft"
                                "HG" -> "Griechisch"
                                "SG" -> "Französisch"
                                "WWG" -> "Wirtschaft und Recht"
                                else -> ""
                            }
                else
                    name in listOf("Deutsch", "Mathematik", "Englisch") ||
                        p.school == School.REALSCHULE &&
                            name ==
                                when (p.track) {
                                    "I" -> "Physik"
                                    "II" -> "Betriebswirtschaftslehre/Rechnungswesen"
                                    "IIIa" -> "Französisch"
                                    else -> p.track.substringAfter(" · ")
                                }
            Subject(
                    profileId = p.id,
                    name = name,
                    core = core,
                    promotion =
                        if (p.school == School.GRUNDSCHULE) name in Transfer.NAMES
                        else
                            name != "Sport" &&
                                !(p.school == School.GYMNASIUM &&
                                    p.grade < 7 &&
                                    p.track != "MuG" &&
                                    name == "Musik"),
                    annualSchoolworks = if (p.school == School.GYMNASIUM && !core) 0 else null,
                )
                .let { PromotionSubjects.correct(p, it) }
        }
    }

    // One-time, reference-preserving catalog update; deleted subjects are not recreated later.
    fun updateCatalog(data: SchoolData): SchoolData {
        if (data.catalogVersion >= 2) return data
        val first = updateCatalogNames(data)
        return first.copy(
            subjects =
                first.subjects.map { s ->
                    first.profiles
                        .find { it.id == s.profileId }
                        ?.let { PromotionSubjects.correct(it, s) } ?: s
                },
            catalogVersion = 2,
        )
    }

    private fun updateCatalogNames(data: SchoolData): SchoolData {
        if (data.catalogVersion >= 1) return data
        var subjects =
            data.subjects.map { subject ->
                if (
                    subject.name == "Ethik" &&
                        data.subjects.none {
                            it.profileId == subject.profileId &&
                                it.name.equals("Ethik/Religion", true)
                        }
                )
                    subject.copy(name = "Ethik/Religion")
                else subject
            }
        for (profile in
            data.profiles.filter { it.state == "Bayern" && it.school == School.REALSCHULE }) {
            if (
                subjects.none {
                    it.profileId == profile.id &&
                        (it.name.equals("IT", true) ||
                            it.name.contains("Informationstechnologie", true))
                }
            ) {
                subjects =
                    subjects +
                        Defaults.subjects(profile).first {
                            it.name == "IT · Informationstechnologie"
                        }
            }
        }
        return data.copy(subjects = subjects, catalogVersion = 1)
    }

    fun holidays(p: Profile): List<DayOff> {
        if (p.state != "Bayern" || p.year != 2026) return emptyList()
        val ranges =
            listOf(
                Triple("2026-08-03", "2026-09-14", "Sommerferien"),
                Triple("2026-11-02", "2026-11-06", "Herbstferien"),
                Triple("2026-12-24", "2027-01-08", "Weihnachtsferien"),
                Triple("2027-02-08", "2027-02-12", "Frühjahrsferien"),
                Triple("2027-03-22", "2027-04-02", "Osterferien"),
                Triple("2027-05-18", "2027-05-28", "Pfingstferien"),
                Triple("2027-08-02", "2027-09-13", "Sommerferien"),
            )
        val fixed =
            listOf(
                "2026-10-03" to "Tag der Deutschen Einheit",
                "2026-11-01" to "Allerheiligen",
                "2026-11-18" to "Buß- und Bettag · schulfrei",
                "2027-05-01" to "Tag der Arbeit",
                "2027-05-06" to "Christi Himmelfahrt",
                "2027-05-17" to "Pfingstmontag",
                "2027-05-27" to "Fronleichnam",
            )
        return ranges.map { (a, b, n) -> DayOff(profileId = p.id, from = a, to = b, reason = n) } +
            fixed.map { (d, n) -> DayOff(profileId = p.id, from = d, to = d, reason = n) }
    }
}
