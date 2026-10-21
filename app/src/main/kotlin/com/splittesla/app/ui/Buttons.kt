package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** A wrapping row of command buttons: label to action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonGrid(enabled: Boolean, vararg buttons: Pair<String, () -> Unit>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { (label, onClick) ->
            FilledTonalButton(onClick = onClick, enabled = enabled) { Text(label) }
        }
    }
}
