package com.splittesla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Panel

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Panel(modifier.fillMaxWidth(), title = title, content = content)
}

@Composable
fun ValueRow(label: String, value: String?, highlight: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Neon.muted)
        Text(value ?: "—", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = highlight ?: Neon.text)
    }
}

fun Double?.fmt(digits: Int = 1, unit: String = ""): String? = this?.let { "%.${digits}f%s".format(it, unit) }

fun Long?.fmt(unit: String = ""): String? = this?.let { "$it$unit" }
