package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import de.streberalarm.core.*

@Composable
fun EstimatePage(profile: Profile, exam: Assessment, save: (Int?) -> Unit) {
    var value by rememberSaveable { mutableStateOf(exam.estimate?.toString().orEmpty()) }
    Page(
        if (exam.stage == Stage.GRADED) "Welche Note hattest du erwartet?"
        else "Wie lief dein Test?",
        exam.title,
    ) {
        Text("Was glaubst du: Welche Note hast du geschrieben?")
        Text("Dein Gefühl zählt nicht zum Notenschnitt.")
        GradePick("Deine gefühlte Note", value, profile.points) { value = it }
        Action("Einschätzung speichern", { save(value.toInt()) }, value.isNotBlank())
        TextButton({ save(null) }, Modifier.fillMaxWidth()) { Text("Weiß ich nicht") }
    }
}
