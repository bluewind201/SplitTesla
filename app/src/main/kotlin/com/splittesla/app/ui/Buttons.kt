package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** A pending "are you sure?" question for an action that opens, moves or unlocks the car. */
@Stable
class Confirmer {
    var pending by mutableStateOf<Pair<String, () -> Unit>?>(null)
        private set

    /** Returns a click handler that asks first when [ask] is true. */
    fun guard(question: String, ask: Boolean = true, action: () -> Unit): () -> Unit =
        { if (ask) pending = question to action else action() }

    fun dismiss() {
        pending = null
    }
}

@Composable
fun rememberConfirmer(): Confirmer {
    val confirmer = remember { Confirmer() }
    confirmer.pending?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = confirmer::dismiss,
            title = { Text("실행할까요?") },
            text = { Text(question) },
            confirmButton = {
                TextButton(onClick = { confirmer.dismiss(); action() }) { Text("실행") }
            },
            dismissButton = { TextButton(onClick = confirmer::dismiss) { Text("취소") } },
        )
    }
    return confirmer
}
