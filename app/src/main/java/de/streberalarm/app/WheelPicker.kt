package de.streberalarm.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

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
        enabled = options.isNotEmpty(),
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
            Icon(Icons.Outlined.UnfoldMore, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (expanded && options.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            val initial = options.indexOf(value).coerceAtLeast(0)
            val wheel = rememberLazyListState(initialFirstVisibleItemIndex = initial)
            val scope = rememberCoroutineScope()
            val rowHeight =
                (if (options.any { it.length > 75 }) 120
                    else if (options.any { it.length > 32 }) 88 else 64)
                    .dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val selected by remember {
                derivedStateOf {
                    val layout = wheel.layoutInfo
                    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                    layout.visibleItemsInfo
                        .minByOrNull { abs(it.offset + it.size / 2 - center) }
                        ?.index ?: initial
                }
            }
            fun move(index: Int) {
                scope.launch { wheel.animateScrollToItem(index.coerceIn(options.indices)) }
            }
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Drehe das Rad. Der Wert in der Mitte zählt.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.fillMaxWidth().height(rowHeight * 3)) {
                    Box(
                        Modifier.align(Alignment.Center)
                            .fillMaxWidth()
                            .height(rowHeight)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                lookShape(20.dp),
                            )
                    )
                    LazyColumn(
                        state = wheel,
                        flingBehavior = rememberSnapFlingBehavior(wheel),
                        contentPadding = PaddingValues(vertical = rowHeight),
                        modifier =
                            Modifier.fillMaxSize().testTag("wheel-$tag").semantics {
                                stateDescription = options[selected]
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction("Vorheriger Wert") {
                                            move(selected - 1)
                                            true
                                        },
                                        CustomAccessibilityAction("Nächster Wert") {
                                            move(selected + 1)
                                            true
                                        },
                                    )
                            },
                    ) {
                        items(options.size) { index ->
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(rowHeight)
                                    .testTag("wheel-option-$index")
                                    .clickable { move(index) }
                                    .semantics { this.selected = index == selected },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    options[index],
                                    Modifier.padding(horizontal = 12.dp),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight =
                                        if (index == selected) FontWeight.ExtraBold
                                        else FontWeight.Normal,
                                    color =
                                        if (index == selected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton({ move(selected - 1) }, enabled = selected > 0) {
                        Icon(Icons.Outlined.KeyboardArrowUp, "Vorheriger Wert")
                    }
                    Text(
                        "${selected + 1} von ${options.size}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton({ move(selected + 1) }, enabled = selected < options.lastIndex) {
                        Icon(Icons.Outlined.KeyboardArrowDown, "Nächster Wert")
                    }
                }
                Action(
                    "Übernehmen",
                    {
                        onSelect(options[selected])
                        expanded = false
                    },
                    !wheel.isScrollInProgress,
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
                Spacer(Modifier.height(12.dp))
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
