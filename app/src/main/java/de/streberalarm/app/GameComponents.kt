package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun GameTile(
    title: String,
    hint: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    click: () -> Unit,
) {
    Card(
        onClick = click,
        modifier = modifier.heightIn(min = 164.dp),
        shape = lookShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(34.dp), tint = Ink)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Ink,
            )
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
fun HelpPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Panel {
        TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) content()
    }
}

fun subjectTint(subject: de.streberalarm.core.Subject?): Color =
    when {
        subject?.core == true -> Color(0xFFE5DAFF)
        subject?.promotion == true -> Color(0xFFCFEAFB)
        else -> Color(0xFFE8EBF0)
    }

fun subjectCategory(subject: de.streberalarm.core.Subject): String =
    when {
        subject.core -> "Haupt-/Kernfach"
        subject.promotion -> "Vorrückungsfach"
        else -> "Weiteres Fach"
    }

fun List<de.streberalarm.core.Subject>.alphabetical(): List<de.streberalarm.core.Subject> {
    val collator = java.text.Collator.getInstance(java.util.Locale.GERMAN)
    return sortedWith { a, b -> collator.compare(a.name, b.name) }
}
