package com.rbmr.taigasleep.debug

import androidx.compose.runtime.Composable
import com.rbmr.taigasleep.GuidanceStore

/** Release twin of the debug guidance preview picker; never shown, since GUIDANCE_PREVIEW is off. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun GuidancePreviewPicker(store: GuidanceStore, onDismiss: () -> Unit) = Unit
