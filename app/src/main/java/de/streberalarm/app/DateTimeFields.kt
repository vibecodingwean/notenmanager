package de.streberalarm.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun DateField(label: String, value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    OutlinedButton(
        {
            val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
            DatePickerDialog(
                    if (look == AppLook.CLASSIC) context
                    else ContextThemeWrapper(context, dateDialogStyle(look)),
                    { _, y, m, d -> change(LocalDate.of(y, m + 1, d).toString()) },
                    date.year,
                    date.monthValue - 1,
                    date.dayOfMonth,
                )
                .show()
        },
        Modifier.fillMaxWidth().testTag("date-$label"),
        shape =
            if (look == AppLook.HACKER || look == AppLook.PIXEL) lookShape(0.dp)
            else ButtonDefaults.outlinedShape,
    ) {
        Icon(Icons.Outlined.CalendarMonth, null)
        Text("$label: ${dateLabel(value)}", Modifier.weight(1f))
    }
}

@Composable
fun ClockField(label: String, value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    OutlinedButton(
        {
            val time = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(8, 0))
            TimePickerDialog(
                    ContextThemeWrapper(context, clockDialogStyle(look)),
                    { _, h, m -> change(LocalTime.of(h, m).toString()) },
                    time.hour,
                    time.minute,
                    true,
                )
                .apply { setTitle(label) }
                .show()
        },
        Modifier.fillMaxWidth(),
        shape =
            if (look == AppLook.HACKER || look == AppLook.PIXEL) lookShape(0.dp)
            else ButtonDefaults.outlinedShape,
    ) {
        Icon(Icons.Outlined.Schedule, null)
        Text("$label: ${value.ifBlank { "noch offen" }}", Modifier.weight(1f))
    }
}

private fun dateDialogStyle(look: AppLook) =
    when (look) {
        AppLook.CLASSIC -> android.R.style.Theme_Material_Light_Dialog_Alert
        AppLook.HACKER -> R.style.HackerDateDialog
        AppLook.SOCIAL -> R.style.SocialDateDialog
        AppLook.STREAMER -> R.style.StreamerDateDialog
        AppLook.ORBIT -> R.style.OrbitDateDialog
        AppLook.PIXEL -> R.style.PixelDateDialog
    }

private fun clockDialogStyle(look: AppLook) =
    when (look) {
        AppLook.CLASSIC -> R.style.WheelClockDialog
        AppLook.HACKER -> R.style.HackerClockDialog
        AppLook.SOCIAL -> R.style.SocialClockDialog
        AppLook.STREAMER -> R.style.StreamerClockDialog
        AppLook.ORBIT -> R.style.OrbitClockDialog
        AppLook.PIXEL -> R.style.PixelClockDialog
    }
