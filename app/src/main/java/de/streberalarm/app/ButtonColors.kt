package de.streberalarm.app

import androidx.compose.material3.ButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Button-only override: white information surfaces keep their original palette. */
internal fun buttonColorsForLook(look: AppLook, defaults: ButtonColors): ButtonColors =
    if (look == AppLook.BAYERN) defaults.copy(
        containerColor = Color(0xFFB51225),
        contentColor = Color.White,
    ) else if (look.isYouth) {
        val c = lookScheme(look)
        // Opaque surfaces avoid inherited white labels and keep error/delete text readable.
        defaults.copy(containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer,
            disabledContainerColor = c.surfaceVariant, disabledContentColor = c.onSurfaceVariant)
    } else defaults

@Composable
internal fun bayernButtonColors(defaults: ButtonColors): ButtonColors =
    buttonColorsForLook(LocalLook.current, defaults)

internal fun actionButtonColors(look: AppLook, defaults: ButtonColors): ButtonColors =
    if (look.isYouth) buttonColorsForLook(look, defaults) else defaults
