package de.streberalarm.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

/** A narrow, validated JSON boundary for the native iOS UI. */
class IosBridge {
    fun emptyData(): String = dataJson.encodeToString(SchoolData())

    @Throws(Exception::class)
    fun normalizeData(json: String): String = encode(decode(json))

    @Throws(Exception::class)
    fun createProfile(
        json: String,
        state: String,
        school: String,
        grade: Int,
        track: String,
        year: Int,
        term: String,
    ): String {
        val data = decode(json)
        val profile = Profile(state = state, school = School.valueOf(school), grade = grade,
            track = track, year = year, term = term)
        profile.validate()
        return encode(data.copy(
            profiles = data.profiles + profile,
            activeProfileId = profile.id,
            subjects = data.subjects + Defaults.subjects(profile),
        ))
    }

    @Throws(Exception::class)
    fun selectProfile(json: String, profileId: String): String =
        encode(decode(json).copy(activeProfileId = profileId))

    @Throws(Exception::class)
    fun deleteProfile(json: String, profileId: String): String {
        val data = decode(json)
        require(data.profiles.any { it.id == profileId }) { "Schulprofil nicht gefunden." }
        val subjectIds = data.subjects.filter { it.profileId == profileId }.map { it.id }.toSet()
        val assessmentIds = data.assessments.filter { it.subjectId in subjectIds }.map { it.id }.toSet()
        val remainingProfiles = data.profiles.filterNot { it.id == profileId }
        return encode(data.removeProfileVocabulary(profileId).removeProfileFlashcards(profileId).copy(
            profiles = remainingProfiles,
            activeProfileId = if (data.activeProfileId == profileId) remainingProfiles.firstOrNull()?.id
                else data.activeProfileId,
            subjects = data.subjects.filterNot { it.id in subjectIds },
            assessments = data.assessments.filterNot { it.id in assessmentIds },
            officials = data.officials.filterNot { it.subjectId in subjectIds },
            timetables = data.timetables.filterNot { it.profileId == profileId },
            daysOff = data.daysOff.filterNot { it.profileId == profileId },
            exceptions = data.exceptions.filterNot { it.subjectId in subjectIds },
            studies = data.studies.filterNot { it.assessmentId in assessmentIds },
            documents = data.documents.filterNot { it.assessmentId in assessmentIds },
            delivered = data.delivered.filterNot { key -> assessmentIds.any { ":$it:" in key } }.toSet(),
            graduation = data.graduation.filterKeys { !it.startsWith("$profileId:") },
        ))
    }

    @Throws(Exception::class)
    fun addSubject(json: String, name: String, core: Boolean, promotion: Boolean,
        annualSchoolworks: Int): String {
        return saveSubject(json, "", name, core, promotion, annualSchoolworks,
            false, false, 1.0, 1.0, 1.0, false)
    }

    @Throws(Exception::class)
    fun saveSubject(json: String, subjectId: String, name: String, core: Boolean,
        promotion: Boolean, annualSchoolworks: Int, advanced: Boolean, examSubject: Boolean,
        smallWrittenWeight: Double, oralWeight: Double, practicalWeight: Double,
        weightsConfirmed: Boolean): String {
        val data = decode(json)
        val profile = requireNotNull(data.active()) { "Bitte zuerst ein Schulprofil anlegen." }
        val old = if (subjectId.isEmpty()) null else requireNotNull(
            data.subjects.firstOrNull { it.id == subjectId && it.profileId == profile.id }
        ) { "Fach gehört nicht zum aktiven Schulprofil." }
        val subject = (old ?: Subject(profileId = profile.id, name = name.trim())).copy(
            name = name.trim(), core = core, promotion = promotion,
            annualSchoolworks = annualSchoolworks.takeIf { it >= 0 },
            advanced = advanced, examSubject = examSubject,
            smallWrittenWeight = smallWrittenWeight, oralWeight = oralWeight,
            practicalWeight = practicalWeight, weightsConfirmed = weightsConfirmed)
        return encode(data.copy(subjects = data.subjects.filterNot { it.id == subject.id } + subject))
    }

    @Throws(Exception::class)
    fun saveAssessment(json: String, assessmentId: String, subjectId: String, title: String,
        date: String, kind: String, stage: String, notes: String, actual: Int,
        estimate: Int, weight: Double = 1.0): String {
        val data = decode(json)
        require(data.subjects.any { it.id == subjectId && it.profileId == data.activeProfileId }) {
            "Fach gehört nicht zum aktiven Schulprofil."
        }
        val old = data.assessments.firstOrNull { it.id == assessmentId }
        val parsedStage = Stage.valueOf(stage)
        val assessment = (old ?: Assessment(subjectId = subjectId, title = title, date = date)).copy(
            subjectId = subjectId,
            title = title.trim(),
            date = date,
            kind = Kind.valueOf(kind),
            stage = parsedStage,
            material = notes,
            notes = "",
            actual = actual.takeIf { it >= 0 },
            estimate = estimate.takeIf { it >= 0 },
            weight = weight,
        )
        return encode(data.copy(assessments = data.assessments.filterNot { it.id == assessment.id } + assessment))
    }

    @Throws(Exception::class)
    fun deleteAssessment(json: String, assessmentId: String): String =
        encode(decode(json).deleteAssessment(assessmentId))

    @Throws(Exception::class)
    fun addDocument(json: String, assessmentId: String, name: String, mime: String,
        file: String, size: Long): String {
        val data = decode(json)
        require(data.assessments.any { it.id == assessmentId })
        val document = Document(assessmentId = assessmentId, name = name, mime = mime,
            file = file, size = size)
        return encode(data.copy(documents = data.documents + document))
    }

    @Throws(Exception::class)
    fun deleteDocument(json: String, documentId: String): String {
        val data = decode(json)
        val document = requireNotNull(data.documents.firstOrNull { it.id == documentId })
        require(data.assessments.any { it.id == document.assessmentId &&
            data.subjects.any { subject -> subject.id == it.subjectId && subject.profileId == data.activeProfileId } })
        return encode(data.copy(documents = data.documents.filterNot { it.id == documentId }))
    }

    @Throws(Exception::class)
    fun saveTimetable(json: String, timetableId: String, validFrom: String,
        anchorMonday: String, blocksJson: String): String {
        val data = decode(json)
        val profile = requireNotNull(data.active())
        val blocks = dataJson.decodeFromString<List<Lesson>>(blocksJson)
        val old = data.timetables.firstOrNull { it.id == timetableId }
        require(old == null || old.profileId == profile.id) { "Stundenplan gehört zu einem anderen Profil." }
        val timetable = (old ?: Timetable(profileId = profile.id, validFrom = validFrom,
            anchorMonday = anchorMonday)).copy(validFrom = validFrom, anchorMonday = anchorMonday,
            blocks = blocks)
        return encode(data.copy(timetables = data.timetables.filterNot { it.id == timetable.id } + timetable))
    }

    @Throws(Exception::class)
    fun saveDayOff(json: String, dayOffId: String, from: String, to: String, reason: String): String {
        val data = decode(json)
        val profile = requireNotNull(data.active())
        val old = data.daysOff.firstOrNull { it.id == dayOffId }
        require(old == null || old.profileId == profile.id)
        val day = (old ?: DayOff(profileId = profile.id, from = from, to = to,
            reason = reason)).copy(from = from, to = to, reason = reason)
        return encode(data.copy(daysOff = data.daysOff.filterNot { it.id == day.id } + day))
    }

    @Throws(Exception::class)
    fun deleteDayOff(json: String, dayOffId: String): String {
        val data = decode(json)
        val profile = requireNotNull(data.active())
        require(data.daysOff.any { it.id == dayOffId && it.profileId == profile.id })
        return encode(data.copy(daysOff = data.daysOff.filterNot { it.id == dayOffId }))
    }

    @Throws(Exception::class)
    fun saveException(json: String, exceptionId: String, subjectId: String, date: String,
        start: String, end: String, cancelled: Boolean): String {
        val data = decode(json)
        require(data.subjects.any { it.id == subjectId && it.profileId == data.activeProfileId })
        val old = data.exceptions.firstOrNull { it.id == exceptionId }
        require(old == null || data.subjects.any { it.id == old.subjectId && it.profileId == data.activeProfileId })
        val change = (old ?: ExceptionLesson(subjectId = subjectId, date = date, start = start,
            end = end, cancelled = cancelled)).copy(subjectId = subjectId, date = date,
            start = start, end = end, cancelled = cancelled)
        return encode(data.copy(exceptions = data.exceptions.filterNot { it.id == change.id } + change))
    }

    @Throws(Exception::class)
    fun deleteException(json: String, exceptionId: String): String {
        val data = decode(json)
        val subjects = data.subjects.filter { it.profileId == data.activeProfileId }.map { it.id }.toSet()
        require(data.exceptions.any { it.id == exceptionId && it.subjectId in subjects })
        return encode(data.copy(exceptions = data.exceptions.filterNot { it.id == exceptionId }))
    }

    @Throws(Exception::class)
    fun dayLessons(json: String, date: String): String {
        val data = decode(json)
        val profile = data.active() ?: return "[]"
        val day = SchoolDate.parse(date)
        val table = data.timetables.filter { it.profileId == profile.id && it.validFrom <= date }
            .maxByOrNull { it.validFrom }
        val free = data.daysOff.any { it.profileId == profile.id && date >= it.from && date <= it.to }
        val week = table?.let {
            ((day.with(DayOfWeek.MONDAY).epochDay - SchoolDate.parse(it.anchorMonday).epochDay) / 7).mod(2) + 1
        }
        val subjects = data.subjects.filter { it.profileId == profile.id }.associateBy { it.id }
        val changes = data.exceptions.filter { it.date == date && it.subjectId in subjects }
        val regular = if (free || table == null) emptyList() else table.blocks.filter { block ->
            block.day == day.dayOfWeek.isoDayNumber &&
                (block.week == 0 || block.week == week) &&
                (block.isBreak || block.subjectId in subjects) &&
                changes.none { it.cancelled && it.subjectId == block.subjectId &&
                    it.start < block.end && it.end > block.start }
        }
        val extra = changes.filterNot { it.cancelled }.map {
            Lesson(id = it.id, subjectId = it.subjectId, day = day.dayOfWeek.isoDayNumber,
                start = it.start, end = it.end)
        }
        return buildJsonArray {
            (regular + extra).distinctBy { Triple(it.subjectId, it.start, it.end) }
                .sortedBy { it.start }.forEach { block ->
                    add(buildJsonObject {
                        put("id", block.id)
                        put("subjectId", block.subjectId)
                        put("subjectName", if (block.isBreak) "Pause" else subjects[block.subjectId]?.name ?: "Fach")
                        put("start", block.start)
                        put("end", block.end)
                        put("isBreak", block.isBreak)
                    })
                }
        }.toString()
    }

    @Throws(Exception::class)
    fun remainingLessons(json: String, assessmentId: String, now: String): Int {
        val data = decode(json)
        val exam = requireNotNull(data.assessments.firstOrNull { it.id == assessmentId })
        return Lessons.remaining(data, exam, SchoolDateTime.parse(now)).size
    }

    @Throws(Exception::class)
    fun notificationPlan(json: String, nowMillis: Long, zoneId: String): String {
        val data = decode(json)
        val now = SchoolZonedTime.fromEpochMillis(nowMillis, zoneId)
        return buildJsonArray {
            Reminders.plan(data, now).forEach { day ->
                day.notices.forEach { notice ->
                    add(buildJsonObject {
                        put("key", notice.key)
                        put("examId", notice.examId)
                        put("text", notice.text)
                        put("atMillis", day.at.epochMillis)
                    })
                }
            }
        }.toString()
    }

    @Throws(Exception::class)
    fun estimatePromptsDue(json: String, nowMillis: Long, zoneId: String): String {
        val data = decode(json)
        val now = SchoolZonedTime.fromEpochMillis(nowMillis, zoneId)
        return buildJsonArray {
            EstimatePrompts.due(data, now).forEach { exam ->
                add(buildJsonObject {
                    put("id", exam.id)
                    put("title", exam.title)
                    put("date", exam.date)
                    put("key", EstimatePrompts.key(exam))
                })
            }
        }.toString()
    }

    @Throws(Exception::class)
    fun estimatePromptNextAt(json: String, nowMillis: Long, zoneId: String): Long =
        EstimatePrompts.nextAt(decode(json), SchoolZonedTime.fromEpochMillis(nowMillis, zoneId))
            ?.epochMillis ?: -1L

    @Throws(Exception::class)
    fun estimatePromptPlan(json: String, nowMillis: Long, zoneId: String): String {
        val data = decode(json)
        val now = SchoolZonedTime.fromEpochMillis(nowMillis, zoneId)
        return buildJsonArray {
            EstimatePrompts.candidates(data)
                .filter { EstimatePrompts.key(it) !in data.delivered }
                .sortedWith(compareBy<Assessment> { it.date }.thenBy { it.id })
                .forEach { exam ->
                    val scheduled = SchoolDate.parse(exam.date).atTime(16, 0).atZone(now.zone)
                    val overdue = exam.stage == Stage.GRADED || scheduled <= now
                    val at = if (overdue)
                        now.epochMillis + 1000 else scheduled.epochMillis
                    add(buildJsonObject {
                        put("key", EstimatePrompts.key(exam))
                        put("examId", exam.id)
                        put("atMillis", at)
                        put("overdue", overdue)
                    })
                }
        }.toString()
    }

    @Throws(Exception::class)
    fun setEstimate(json: String, assessmentId: String, estimate: Int): String {
        val data = decode(json)
        require(data.assessments.any { it.id == assessmentId })
        return encode(data.copy(assessments = data.assessments.map {
            if (it.id == assessmentId) it.copy(estimate = estimate) else it
        }))
    }

    @Throws(Exception::class)
    fun skipEstimate(json: String, assessmentId: String): String {
        val data = decode(json)
        val exam = requireNotNull(data.assessments.firstOrNull { it.id == assessmentId })
        return encode(data.copy(delivered = data.delivered + EstimatePrompts.skipKey(exam)))
    }

    @Throws(Exception::class)
    fun saveOfficial(json: String, subjectId: String, period: String, value: Int,
        omitted: Boolean): String {
        val data = decode(json)
        require(data.subjects.any { it.id == subjectId && it.profileId == data.activeProfileId })
        require(period == "Jahreszeugnis" || period == Transfer.PERIOD)
        val rest = data.officials.filterNot { it.subjectId == subjectId && it.period == period }
        val next = if (value < 0 && !omitted) rest else rest + Official(subjectId = subjectId,
            period = period, value = value.takeIf { !omitted }, omitted = omitted)
        return encode(data.copy(officials = next))
    }

    @Throws(Exception::class)
    fun transferOverview(json: String): String {
        val data = decode(json)
        val profile = data.active() ?: return "{}"
        val result = Transfer.calculate(profile, data)
        return buildJsonObject {
            put("average", result.average?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            put("values", buildJsonArray { result.values.forEach {
                add(it?.let { value -> kotlinx.serialization.json.JsonPrimitive(value) } ?: JsonNull)
            } })
            put("official", result.official)
            put("gymnasium", result.gymnasium.name)
            put("realschule", result.realschule.name)
            put("supported", result.supported)
            put("duplicateSubjects", result.duplicateSubjects)
        }.toString()
    }

    @Throws(Exception::class)
    fun saveTransferProbe(json: String, route: String, german: Int, math: Int): String {
        require(route == "gym" || route == "rs")
        val data = decode(json)
        val profile = requireNotNull(data.active())
        val key = "${profile.id}:uebertritt"
        val current = data.graduation[key] ?: GraduationInput(procedure = "uebertritt")
        val numbers = current.numbers.toMutableMap()
        if (german < 0) numbers.remove("${route}Deutsch") else numbers["${route}Deutsch"] = german
        if (math < 0) numbers.remove("${route}Mathematik") else numbers["${route}Mathematik"] = math
        return encode(data.copy(graduation = data.graduation + (key to current.copy(numbers = numbers))))
    }

    @Throws(Exception::class)
    fun transferProbeGrades(json: String, route: String): String {
        require(route == "gym" || route == "rs")
        val data = decode(json)
        val profile = data.active() ?: return "{}"
        val numbers = data.graduation["${profile.id}:uebertritt"]?.numbers.orEmpty()
        return buildJsonObject {
            put("german", numbers["${route}Deutsch"]?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            put("math", numbers["${route}Mathematik"]?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        }.toString()
    }

    @Throws(Exception::class)
    fun promotionOverview(json: String, rosterComplete: Boolean, previousCompensation: Int,
        socialInternship: Int): String {
        val data = decode(json)
        val profile = data.active() ?: return "{}"
        fun tri(value: Int) = if (value < 0) null else value != 0
        return ruleResult(Rules.promotion(profile, data.subjects, data.officials,
            rosterComplete, tri(previousCompensation), tri(socialInternship)))
    }

    @Throws(Exception::class)
    fun saveGraduation(json: String, inputJson: String): String {
        val data = decode(json)
        val profile = requireNotNull(data.active())
        val input = dataJson.decodeFromString<GraduationInput>(inputJson)
        require(input.procedure in listOf("Quali", "Mittlerer Abschluss", "Abitur"))
        return encode(data.copy(graduation = data.graduation + ("${profile.id}:${input.procedure}" to input)))
    }

    @Throws(Exception::class)
    fun graduationInput(json: String, procedure: String): String {
        val data = decode(json)
        val profile = data.active() ?: return "{}"
        val input = data.graduation["${profile.id}:$procedure"] ?: GraduationInput(procedure = procedure)
        return dataJson.encodeToString(input)
    }

    @Throws(Exception::class)
    fun qualiFieldNames(json: String, inputJson: String): String {
        val data = decode(json)
        val profile = data.active() ?: return "[]"
        val input = dataJson.decodeFromString<GraduationInput>(inputJson)
        return buildJsonArray {
            Rules.qualiFields(profile, input).forEach { (key, weight) ->
                add(buildJsonObject { put("key", key); put("weight", weight) })
            }
        }.toString()
    }

    @Throws(Exception::class)
    fun certificateOverview(json: String, procedure: String): String {
        val data = decode(json)
        val profile = data.active() ?: return "{}"
        val input = data.graduation["${profile.id}:$procedure"] ?: GraduationInput(procedure = procedure)
        return ruleResult(Rules.certificate(profile, input))
    }

    private fun ruleResult(result: RuleResult): String = buildJsonObject {
        put("verdict", result.verdict.name)
        put("title", result.title)
        put("details", buildJsonArray { result.details.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        put("sources", buildJsonArray { result.sources.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        put("value", result.value?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    @Throws(Exception::class)
    fun startStudy(json: String, assessmentId: String, wall: Long, elapsed: Long, boot: Int): String =
        encode(Timers.start(decode(json), assessmentId, wall, elapsed, boot))

    @Throws(Exception::class)
    fun stopStudy(json: String, wall: Long, elapsed: Long, boot: Int): String =
        encode(Timers.stop(decode(json), wall, elapsed, boot))

    @Throws(Exception::class)
    fun correctStudy(json: String, studyId: String, minutes: Int, reason: String): String {
        val data = decode(json)
        require(data.studies.any { it.id == studyId && it.end != null })
        return encode(data.copy(studies = data.studies.map {
            if (it.id == studyId) it.copy(correctedMinutes = minutes, correction = reason) else it
        }))
    }

    @Throws(Exception::class)
    fun overview(json: String): String {
        val data = decode(json)
        val profile = data.active() ?: return "{}"
        val result = GradeOverviewCalculator.calculate(profile, data)
        return buildJsonObject {
            put("title", result.title)
            put("basis", result.basis)
            put("reason", result.reason)
            put("advice", result.advice)
            put("light", result.light.name)
            put("average", result.average?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            put("averageCount", result.averageCount)
            put("subjectCount", result.subjectCount)
            put("standings", buildJsonArray {
                result.standings.forEach { row ->
                    add(buildJsonObject {
                        put("subjectId", row.subject.id)
                        put("name", row.subject.name)
                        put("average", row.average?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                        put("grade", row.grade?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                        put("annual", row.annual)
                    })
                }
            })
        }.toString()
    }

    private fun decode(json: String): SchoolData =
        Defaults.updateCatalog(decodeSchoolData(json)).also { it.validate() }

    private fun encode(data: SchoolData): String =
        dataJson.encodeToString(data.also { it.validate() })
}
