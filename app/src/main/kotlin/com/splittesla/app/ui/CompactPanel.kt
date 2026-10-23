package com.splittesla.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.UiState
import com.splittesla.app.camera.CameraState
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import kotlin.math.roundToInt

/**
 * The narrow strip shown when the app shares the screen with a navigation app
 * (split screen with the map taking most of it): speed, the limit, the next
 * camera, battery and the last destination sent to the map, big enough to
 * read at a glance.
 */
@Composable
fun CompactPanel(vm: AppViewModel, ui: UiState) {
    val camera by CameraState.status.collectAsState()
    val gpsSpeed by CameraState.gpsSpeedKmh.collectAsState()
    val assistant by vm.assistant.state.collectAsState()
    val shared by vm.routeSync.last.collectAsState()
    val c = Cockpit.from(ui, camera, gpsSpeed)
    val over = c.overLimit

    Column(
        Modifier.fillMaxSize().background(Neon.bg).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot("S3XY", ui.commander.color())
            Dot("CAR", ui.car.color())
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                c.speedKmh?.roundToInt()?.toString() ?: "—",
                fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 60.sp,
                color = if (over) Neon.red else Neon.text,
            )
            Text("km/h", color = Neon.muted, fontSize = 12.sp)
        }

        c.activeLimit?.let { limit ->
            Box(
                Modifier.size(64.dp).background(Color.White, CircleShape).border(6.dp, Neon.red, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$limit", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }
        }

        val dist = c.cameraDistanceM
        if (camera.ahead != null && dist != null && dist < 1500) {
            Text(
                "카메라 ${dist.roundToInt()}m",
                color = Neon.amber, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center,
            )
        }

        Text(
            listOfNotNull(c.socPercent?.let { "$it%" }, c.rangeKm?.let { "${it}km" }).joinToString(" · ").ifEmpty { "배터리 —" },
            color = Neon.lime, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        )

        assistant.said?.let {
            Text(it, color = Neon.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }

        shared?.let {
            Text(it, color = Neon.cyan, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Dot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, color = Neon.muted, fontSize = 10.sp)
    }
}
