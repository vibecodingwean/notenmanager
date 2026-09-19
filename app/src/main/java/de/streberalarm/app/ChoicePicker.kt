package de.streberalarm.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Pick(
    label: String,
    value: String,
    options: List<String>,
    addNew: (() -> Unit)? = null,
    tag: String = label,
    tint: androidx.compose.ui.graphics.Color? = null,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        enabled = options.isNotEmpty() || addNew != null,
        modifier =
            Modifier.fillMaxWidth().testTag("picker-$tag").semantics {
                contentDescription = "$label: $value"
            },
        shape = lookShape(20.dp),
        color = tint ?: MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value.ifEmpty { "Bitte auswählen" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (expanded) {
        // A draft belongs to this open menu. Scrolling never changes the selection.
        var draft by rememberSaveable(value) { mutableStateOf(value) }
        val list = rememberLazyListState(options.indexOf(draft).coerceAtLeast(0))
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                LazyColumn(
                    state = list,
                    modifier =
                        Modifier.fillMaxWidth()
                            .weight(1f, fill = false)
                            .selectableGroup()
                            .testTag("choices-$tag")
                            .semantics { stateDescription = draft },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(options.size) { index ->
                        val option = options[index]
                        val selected = option == draft
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                    lookShape(14.dp),
                                )
                                .testTag("choice-option-$index")
                                .selectable(
                                    selected,
                                    role = Role.RadioButton,
                                    onClick = { draft = option },
                                )
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected, onClick = null)
                            Text(
                                option,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                color =
                                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Action(
                    "Übernehmen",
                    {
                        onSelect(draft)
                        expanded = false
                    },
                    draft in options,
                )
                if (addNew != null)
                    TextButton(
                        {
                            expanded = false
                            addNew()
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Text("Fach hinzufügen")
                    }
                TextButton({ expanded = false }, Modifier.fillMaxWidth()) { Text("Abbrechen") }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun GradePick(label: String, value: String, points: Boolean, change: (String) -> Unit) {
    val grades = (if (points) 0..15 else 1..6).map { it.toString() }
    Pick(label, value.ifBlank { "Noch offen" }, listOf("Noch offen") + grades) {
        change(if (it == "Noch offen") "" else it)
    }
}
