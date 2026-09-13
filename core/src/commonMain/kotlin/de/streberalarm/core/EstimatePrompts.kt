package de.streberalarm.core

/** Separate from pre-exam learning reminders and from actual grade calculation. */
object EstimatePrompts {
    fun key(exam: Assessment) = "estimate:${exam.id}:${exam.date}"

    fun skipKey(exam: Assessment) = "estimate-skip:${exam.id}:${exam.date}"

    fun candidates(data: SchoolData): List<Assessment> =
        data.assessments.filter { exam ->
            exam.stage != Stage.CANCELLED &&
                exam.estimate == null &&
                skipKey(exam) !in data.delivered &&
                data.subjects.any {
                    it.id == exam.subjectId && it.profileId == data.activeProfileId
                }
        }

    fun due(data: SchoolData, now: SchoolZonedTime): List<Assessment> =
        candidates(data)
            .filter {
                it.stage == Stage.GRADED ||
                    SchoolDate.parse(it.date).atTime(16, 0).atZone(now.zone) <= now
            }
            .sortedWith(compareBy<Assessment> { it.date }.thenBy { it.id })

    fun nextAt(
        data: SchoolData,
        now: SchoolZonedTime,
        includeOverdue: Boolean = true,
    ): SchoolZonedTime? =
        candidates(data)
            .filter { key(it) !in data.delivered }
            .mapNotNull { exam ->
                val date = SchoolDate.parse(exam.date).atTime(16, 0).atZone(now.zone)
                if (exam.stage == Stage.GRADED || date <= now) {
                    if (includeOverdue)
                        SchoolZonedTime.fromEpochMillis(now.epochMillis + 1000, now.zone.id)
                    else null
                } else date
            }
            .minOrNull()
}
