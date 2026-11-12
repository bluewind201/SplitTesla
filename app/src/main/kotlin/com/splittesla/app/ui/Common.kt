package com.splittesla.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Panel

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Panel(modifier.fillMaxWidth(), title = title, content = content)
}

/**
 * A settings card that shows only its title until tapped, so a long settings
 * page fits on one screen. [summary] sits on the closed title row (a saved
 * state such as "등록됨") so it can be read without opening the card.
 */
@Composable
fun FoldSection(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryColor: Color = Neon.muted,
    startOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "fold")
    Panel(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 3.dp, height = 14.dp).background(Neon.lime, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = Caption.copy(fontFamily = null, fontSize = 15.sp, letterSpacing = 0.5.sp, color = Neon.text),
                modifier = Modifier.weight(1f),
            )
            if (summary != null) {
                Text(summary, fontSize = 12.sp, color = summaryColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
            }
            Text("›", fontSize = 22.sp, color = Neon.lime, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
fun ValueRow(label: String, value: String?, highlight: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Neon.muted)
        Text(value ?: "—", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = highlight ?: Neon.text)
    }
}

fun Double?.fmt(digits: Int = 1, unit: String = ""): String? = this?.let { "%.${digits}f%s".format(it, unit) }
fun Float?.fmt(digits: Int = 1, unit: String = ""): String? = this?.toDouble().fmt(digits, unit)

fun Long?.fmt(unit: String = ""): String? = this?.let { "$it$unit" }
