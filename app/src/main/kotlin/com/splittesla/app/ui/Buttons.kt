package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Glass

/** A wrapping row of command buttons: label to action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonGrid(enabled: Boolean, vararg buttons: Pair<String, () -> Unit>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { (label, onClick) -> GlowButton(label, onClick, enabled = enabled) }
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
            containerColor = Glass.dense,
            title = { Text("실행할까요?") },
            text = { Text(question, color = Neon.muted) },
            confirmButton = { GlowButton("실행", { confirmer.dismiss(); action() }, filled = true) },
            dismissButton = { GlowButton("취소", confirmer::dismiss) },
        )
    }
    return confirmer
}
