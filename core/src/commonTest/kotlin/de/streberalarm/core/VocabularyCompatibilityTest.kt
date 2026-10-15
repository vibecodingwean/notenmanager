package de.streberalarm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

class VocabularyCompatibilityTest {
    @Test
    fun additiveVocabularyPayloadIsAcceptedAndPreserved() {
        val old = """{"schema":1}"""
        decodeSchoolData(old).validate()
        val vocabulary =
            """{"schema":1,"vocabularyCollections":[],"vocabularyCards":[],"vocabularyProgress":[],"vocabularySessions":[]}"""
        val data = decodeSchoolData(vocabulary)
        data.validate()
        val encoded = dataJson.parseToJsonElement(dataJson.encodeToString(data)).jsonObject
        for (key in
            listOf(
                "vocabularyCollections",
                "vocabularyCards",
                "vocabularyProgress",
                "vocabularySessions",
            )) assertEquals("[]", encoded.getValue(key).toString())
    }
}
