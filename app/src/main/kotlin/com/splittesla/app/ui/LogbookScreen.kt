package com.splittesla.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.splittesla.app.AppViewModel
import com.splittesla.app.logbook.LogbookState
import com.splittesla.app.ui.theme.Caption
import com.splittesla.app.ui.theme.GlowButton
import com.splittesla.app.ui.theme.glass
import com.splittesla.app.ui.theme.Metric
import com.splittesla.app.ui.theme.Neon
import com.splittesla.app.ui.theme.Orbitron
import com.splittesla.app.ui.theme.Panel
import com.splittesla.app.ui.theme.rememberPulse
import com.splittesla.app.ui.theme.Glass
import com.splittesla.core.logbook.ChargeType
import com.splittesla.core.logbook.DueLevel
import com.splittesla.core.logbook.EntryKind
import com.splittesla.core.logbook.LogEntry
import com.splittesla.core.logbook.Logbook
import com.splittesla.core.logbook.MaintenanceDue
import com.splittesla.core.logbook.MaintenanceItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Period(val label: String) { MONTH("이번 달"), YEAR("최근 12개월"), ALL("전체") }

/** 차계부: spending summary and service reminders on the left, entries on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogbookScreen(vm: AppViewModel, modifier: Modifier) {
    val lb by vm.logbook.state.collectAsState()
    var editing by remember { mutableStateOf<LogEntry?>(null) }
    var showPrices by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportLogbook)
    }
    val now = System.currentTimeMillis()
    fun new(kind: EntryKind, item: MaintenanceItem? = null) {
        editing = LogEntry(
            id = 0, kind = kind, timeMillis = now, costWon = 0, odometerKm = lb.odometerKm,
            chargeType = if (kind == EntryKind.CHARGE) ChargeType.HOME else null,
            item = if (kind == EntryKind.MAINTENANCE) item ?: MaintenanceItem.TIRE_ROTATION else null,
        )
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryPanel(lb, now)
            AutoPanel(vm, lb)
            MonthlyPanel(lb, now)
            DuePanel(lb, now) { new(EntryKind.MAINTENANCE, it) }
        }
        Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(Modifier.fillMaxWidth(), title = "기록 추가") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton("+ 충전", { new(EntryKind.CHARGE) }, filled = true)
                    GlowButton("+ 정비", { new(EntryKind.MAINTENANCE) })
                    GlowButton("+ 통행료", { new(EntryKind.TOLL) })
                    GlowButton("+ 기타", { new(EntryKind.OTHER) })
                    GlowButton("충전 단가", { showPrices = true })
                    GlowButton("CSV 저장", { exporter.launch("차계부_${SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date())}.csv") }, enabled = lb.entries.isNotEmpty())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "현재 주행거리 " + (lb.odometerKm?.let { "%,d km".format(it) } ?: "모름") + if (lb.odometerFromCar) " (차량)" else "",
                        fontSize = 13.sp,
                        color = Neon.muted,
                        modifier = Modifier.weight(1f),
                    )
                    GlowButton("차에서 읽기", vm::refreshOdometer)
                }
            }
            lb.charging?.let { p ->
                Panel(Modifier.fillMaxWidth(), accent = Neon.cyan) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Neon.cyan.copy(alpha = rememberPulse()), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${p.type.label} 충전 중 · ${"%.1f".format(p.kwh)} kWh. 끝나면 자동으로 기록됩니다",
                            fontSize = 13.sp,
                            color = Neon.text,
                        )
                    }
                }
            }
            lb.driving?.let { p ->
                Panel(Modifier.fillMaxWidth(), accent = Neon.green) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Neon.green.copy(alpha = rememberPulse()), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        val km = p.lastOdometerKm?.let { o -> p.startOdometerKm?.let { o - it } } ?: p.gpsKm
                        Text(
                            "주행 중 · %.1f km · %s 출발. 주차(P)하면 자동으로 기록됩니다".format(km, p.startPlace ?: "?"),
                            fontSize = 13.sp,
                            color = Neon.text,
                        )
                    }
                }
            }
            if (lb.entries.isEmpty()) {
                Text(
                    "아직 기록이 없습니다. 차량 BLE가 연결된 동안 충전이 끝나면 자동으로 기록되고, 정비나 통행료는 위 버튼으로 추가하세요.",
                    fontSize = 13.sp,
                    color = Neon.muted,
                )
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(lb.entries, key = { it.id }) { e -> EntryRow(e) { editing = e } }
            }
        }
    }

    editing?.let { e ->
        EntryDialog(
            initial = e,
            prices = lb.prices,
            onSave = { vm.logbook.save(it); editing = null },
            onDelete = { vm.logbook.delete(e.id); editing = null },
            onDismiss = { editing = null },
        )
    }
    if (showPrices) PriceDialog(lb.prices, vm.logbook::setPrice) { showPrices = false }
}

@Composable
private fun SummaryPanel(lb: LogbookState, now: Long) {
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    val from = when (period) {
        Period.MONTH -> Logbook.monthStart(now)
        Period.YEAR -> Logbook.monthStart(now, 11)
        Period.ALL -> Long.MIN_VALUE
    }
    val s = Logbook.summarize(lb.entries, from)
    Panel(Modifier.fillMaxWidth(), title = "지출 요약") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Period.entries.forEach { p -> GlowButton(p.label, { period = p }, active = p == period) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Metric("TOTAL", "%,d".format(s.totalWon), "원", Neon.lime)
            Metric("CHARGED", s.chargeKwh.fmt(0) ?: "0", "kWh")
        }
        ValueRow("충전 ${s.chargeCount}회 · 평균 단가", s.wonPerKwh?.let { "%,.0f 원/kWh".format(it) })
        ValueRow("주행거리 (기록 기준)", s.distanceKm?.let { "%,d km".format(it) })
        ValueRow("km당 비용", s.wonPerKm?.let { "%,.0f 원".format(it) })
        ValueRow("전비 (충전량 기준)", s.kwhPer100Km?.let { "%.1f kWh/100km".format(it) })
        ValueRow("충전된 거리", s.chargedRangeKm.takeIf { it > 0 }?.let { "%,.0f km".format(it) })
        ValueRow("1km 충전 시 금액", s.wonPerChargedKm?.let { "%,.1f 원/km".format(it) }, Neon.lime)
        ValueRow("100km 충전 시 금액", s.wonPer100ChargedKm?.let { "%,.0f 원/100km".format(it) }, Neon.lime)
        ValueRow("충전 효율", s.chargedKmPerKwh?.let { "%.1f km/kWh".format(it) })
        ValueRow("자동 기록 주행 ${s.driveCount}회", s.driveKm.takeIf { it > 0 }?.let { "%,.1f km".format(it) })
        ValueRow("주행 전비", s.driveKmPerKwh?.let { "%.1f km/kWh".format(it) })
        EntryKind.entries.forEach { k -> s.byKind[k]?.takeIf { it > 0 }?.let { ValueRow(k.label, won(it)) } }
    }
}

/**
 * Automatic booking from the car's BLE / CAN data: drives and health checks
 * (charges record automatically when charging completes).
 */
@Composable
private fun AutoPanel(vm: AppViewModel, lb: LogbookState) {
    Panel(Modifier.fillMaxWidth(), title = "자동 기록") {
        Toggle("차량 데이터로 주행 기록 (거리·출발/도착·배터리·전비)", lb.autoDrives, vm.logbook::setAutoDrives)
        Toggle("12V·타이어·배터리 이상 점검 메모", lb.autoChecks, vm.logbook::setAutoChecks)
        Text(
            "차량 BLE가 연결된 동안 충전 완료 시 충전량(kWh)·충전 거리(+km)·위치·단가 기준 금액이 자동으로 기록됩니다.",
            fontSize = 11.sp,
            color = Neon.muted,
        )
    }
}

@Composable
private fun MonthlyPanel(lb: LogbookState, now: Long) {
    val months = Logbook.monthly(lb.entries, now, 6)
    val max = months.maxOf { it.totalWon }.coerceAtLeast(1)
    Panel(Modifier.fillMaxWidth(), title = "월별 지출") {
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            months.forEach { m ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (m.totalWon > 0) "%.0f".format(m.totalWon / 10_000.0) + "만" else "", fontSize = 10.sp, color = Neon.muted)
                    val h = 70.dp * (m.totalWon.toFloat() / max)
                    val ch = 70.dp * (m.chargeWon.toFloat() / max)
                    Box(Modifier.width(18.dp).height(h).background(Neon.strokeHi, RoundedCornerShape(4.dp)), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.width(18.dp).height(ch).background(Neon.lime, RoundedCornerShape(4.dp)))
                    }
                    Text("${m.month}월", fontSize = 11.sp, color = Neon.text)
                }
            }
        }
        Text("연두색은 충전비, 회색은 그 외 지출", fontSize = 11.sp, color = Neon.muted)
    }
}

@Composable
private fun DuePanel(lb: LogbookState, now: Long, onRecord: (MaintenanceItem) -> Unit) {
    val due = Logbook.maintenanceDue(lb.entries, now, lb.odometerKm)
        .sortedBy { listOf(DueLevel.DUE, DueLevel.SOON, DueLevel.OK, DueLevel.NO_RECORD).indexOf(it.level) }
    Panel(Modifier.fillMaxWidth(), title = "정비 알림") {
        due.forEach { d -> DueRow(d) { onRecord(d.item) } }
        Text("테슬라 권장 주기 기준입니다. 항목을 누르면 정비 기록을 추가합니다.", fontSize = 11.sp, color = Neon.muted)
    }
}

@Composable
private fun DueRow(d: MaintenanceDue, onClick: () -> Unit) {
    val color = when (d.level) {
        DueLevel.DUE -> Neon.red
        DueLevel.SOON -> Neon.amber
        DueLevel.OK -> Neon.green
        DueLevel.NO_RECORD -> Neon.faint
    }
    val date = SimpleDateFormat("yy.MM.dd", Locale.KOREA)
    val text = when {
        d.level == DueLevel.NO_RECORD -> "기록 없음"
        else -> listOfNotNull(
            d.kmLeft?.let { if (it > 0) "%,d km 남음".format(it) else "%,d km 지남".format(-it) }
                ?: d.nextKm?.let { "%,d km에".format(it) },
            d.daysLeft?.let { if (it > 0) "D-$it" else "${-it}일 지남" }?.let { "$it (${date.format(Date(d.nextMillis!!))})" },
        ).joinToString(" · ").ifEmpty { "주행거리 입력 필요" }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(d.item.label, fontSize = 13.sp, color = Neon.text, modifier = Modifier.weight(1f))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (d.level == DueLevel.NO_RECORD) Neon.muted else color)
    }
}

private fun EntryKind.color(): Color = when (this) {
    EntryKind.CHARGE -> Neon.lime
    EntryKind.MAINTENANCE -> Neon.cyan
    EntryKind.TOLL, EntryKind.PARKING -> Neon.amber
    EntryKind.WASH -> Neon.blue
    EntryKind.INSURANCE -> Neon.red
    EntryKind.OTHER -> Neon.muted
    EntryKind.DRIVE -> Neon.green
    EntryKind.CHECK -> Neon.red
}

@Composable
private fun EntryRow(e: LogEntry, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .glass(14.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = 30.dp).background(e.kind.color(), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Neon.text)
                if (e.auto) {
                    Spacer(Modifier.width(6.dp))
                    Text("자동", fontSize = 10.sp, color = Color.Black, modifier = Modifier.background(Neon.lime, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                }
            }
            val when_ = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(e.timeMillis))
            val soc = if (e.socStart != null || e.socEnd != null) "${e.socStart ?: "?"}→${e.socEnd ?: "?"}%" else null
            val details = when (e.kind) {
                EntryKind.DRIVE -> listOfNotNull(
                    when_,
                    e.distanceKm?.let { "%.1f km".format(it) },
                    listOf(e.place, e.placeEnd).filter { it.isNotBlank() }.joinToString(" → ").ifBlank { null },
                    soc,
                    e.kwh?.let { "%.1f kWh".format(it) },
                    e.kmPerKwh?.let { "%.1f km/kWh".format(it) },
                )
                else -> listOfNotNull(
                    when_,
                    e.odometerKm?.let { "%,d km".format(it) },
                    e.kwh?.let { "%.1f kWh".format(it) },
                    e.chargedRangeKm?.let { "+%.0f km".format(it) },
                    soc.takeIf { e.kind == EntryKind.CHARGE },
                    e.place.ifBlank { null },
                    e.source.ifBlank { null },
                    e.memo.ifBlank { null },
                )
            }.joinToString(" · ")
            Text(details, fontSize = 12.sp, color = Neon.muted, maxLines = 2)
            // What a km of charged range cost: the number the driver compares between chargers.
            e.wonPerChargedKm?.let { perKm ->
                Text(
                    "1km 충전 %,.1f원 · 100km 충전 %,.0f원".format(perKm, perKm * 100),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Neon.lime,
                    maxLines = 1,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (e.kind == EntryKind.DRIVE && e.costWon == 0L) (e.distanceKm?.let { "%.1fkm".format(it) } ?: "—") else won(e.costWon),
                fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Neon.text,
            )
            if (e.paid) Text("결제 확인", fontSize = 10.sp, color = Neon.green)
        }
    }
}

/** Text field that stays readable on the dark dialog. */
@Composable
private fun LogField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, number: Boolean = false, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Neon.text,
            unfocusedTextColor = Neon.text,
            focusedLabelColor = Neon.lime,
            unfocusedLabelColor = Neon.muted,
            focusedBorderColor = Neon.lime,
            unfocusedBorderColor = Neon.strokeHi,
            cursorColor = Neon.lime,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryDialog(
    initial: LogEntry,
    prices: Map<ChargeType, Int>,
    onSave: (LogEntry) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply { isLenient = false } }
    val initialDate = remember(initial) { dateFmt.format(Date(initial.timeMillis)) }
    var kind by remember(initial) { mutableStateOf(initial.kind) }
    var chargeType by remember(initial) { mutableStateOf(initial.chargeType ?: ChargeType.HOME) }
    var item by remember(initial) { mutableStateOf(initial.item ?: MaintenanceItem.TIRE_ROTATION) }
    var date by remember(initial) { mutableStateOf(initialDate) }
    var cost by remember(initial) { mutableStateOf(if (initial.costWon > 0) initial.costWon.toString() else "") }
    var odo by remember(initial) { mutableStateOf(initial.odometerKm?.toString().orEmpty()) }
    var kwh by remember(initial) { mutableStateOf(initial.kwh?.let { "%.1f".format(it) }.orEmpty()) }
    var place by remember(initial) { mutableStateOf(initial.place) }
    var memo by remember(initial) { mutableStateOf(initial.memo) }
    var tried by remember(initial) { mutableStateOf(false) }

    val kwhValue = kwh.replace(',', '.').toDoubleOrNull()
    val estimated = if (kind == EntryKind.CHARGE && kwhValue != null) Math.round(kwhValue * (prices[chargeType] ?: chargeType.defaultWonPerKwh)) else null
    val costValue = cost.filter { it.isDigit() }.toLongOrNull() ?: estimated
    val parsedDate = runCatching { dateFmt.parse(date) }.getOrNull()
    val dateError = if (parsedDate == null) "yyyy-MM-dd 형식으로 입력하세요" else null
    val costError = if (tried && costValue == null) "금액을 입력하세요" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.dense,
        titleContentColor = Neon.text,
        textContentColor = Neon.text,
        title = { Text(if (initial.id == 0L) "기록 추가" else "기록 수정") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EntryKind.entries.forEach { k -> GlowButton(k.label, { kind = k }, active = k == kind) }
                }
                when (kind) {
                    EntryKind.CHARGE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChargeType.entries.forEach { t -> GlowButton(t.label, { chargeType = t }, active = t == chargeType, accent = Neon.cyan) }
                    }
                    EntryKind.MAINTENANCE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MaintenanceItem.entries.forEach { m -> GlowButton(m.label, { item = m }, active = m == item, accent = Neon.cyan) }
                    }
                    else -> Unit
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogField(date, { date = it }, "날짜", Modifier.weight(1f), error = dateError)
                    LogField(odo, { v -> odo = v.filter { it.isDigit() } }, "주행거리 (km)", Modifier.weight(1f), number = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind == EntryKind.CHARGE) {
                        LogField(kwh, { v -> kwh = v.filter { it.isDigit() || it == '.' || it == ',' } }, "충전량 (kWh)", Modifier.weight(1f), number = true)
                    }
                    LogField(
                        cost, { v -> cost = v.filter { it.isDigit() } },
                        if (cost.isEmpty() && estimated != null) "금액 (자동 ${"%,d".format(estimated)}원)" else "금액 (원)",
                        Modifier.weight(1f), number = true, error = costError,
                    )
                }
                LogField(place, { place = it }, "장소", Modifier.fillMaxWidth())
                LogField(memo, { memo = it }, "메모", Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            GlowButton("저장", {
                tried = true
                val day = parsedDate ?: return@GlowButton
                val c = costValue ?: return@GlowButton
                // Keep the original time when the day is unchanged; a back-dated entry goes at noon.
                val time = if (date == initialDate) initial.timeMillis else day.time + 12 * 3_600_000L
                onSave(
                    initial.copy(
                        kind = kind, timeMillis = time, costWon = c,
                        odometerKm = odo.toIntOrNull(),
                        kwh = if (kind == EntryKind.CHARGE) kwhValue else null,
                        chargeType = if (kind == EntryKind.CHARGE) chargeType else null,
                        item = if (kind == EntryKind.MAINTENANCE) item else null,
                        place = place.trim(), memo = memo.trim(),
                    ),
                )
            }, filled = true)
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial.id != 0L) GlowButton("삭제", onDelete, accent = Neon.red)
                GlowButton("취소", onDismiss)
            }
        },
    )
}

@Composable
private fun PriceDialog(prices: Map<ChargeType, Int>, onSet: (ChargeType, Int) -> Unit, onDismiss: () -> Unit) {
    val values = remember { ChargeType.entries.associateWith { mutableStateOf((prices[it] ?: it.defaultWonPerKwh).toString()) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.dense,
        titleContentColor = Neon.text,
        textContentColor = Neon.text,
        title = { Text("충전 단가 (원/kWh)") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("자동 기록된 충전의 금액을 이 단가로 계산합니다. 요금이 바뀌면 고쳐 주세요.", style = Caption.copy(fontFamily = null, letterSpacing = 0.sp))
                ChargeType.entries.forEach { t ->
                    val v = values.getValue(t)
                    LogField(v.value, { s -> v.value = s.filter { it.isDigit() } }, t.label, Modifier.fillMaxWidth(), number = true)
                }
            }
        },
        confirmButton = {
            GlowButton("저장", {
                values.forEach { (t, v) -> v.value.toIntOrNull()?.let { onSet(t, it) } }
                onDismiss()
            }, filled = true)
        },
        dismissButton = { GlowButton("취소", onDismiss) },
    )
}

private fun won(v: Long) = "%,d원".format(v)
