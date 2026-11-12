package com.splittesla.core.logbook

/** Where a payment message came from. */
enum class MessageSource(val label: String) {
    SMS("문자"),
    KAKAO("카카오톡"),
    NOTIFICATION("알림"),
    MANUAL("직접 입력"),
}

/** A card or pay-app message read as a car expense. */
data class ParsedPayment(
    val kind: EntryKind,
    val amountWon: Long,
    /** The keyword that matched, such as "한국자동차환경협회" or "하이패스". */
    val keyword: String,
    /** The merchant line when the message has one, else the keyword. */
    val merchant: String,
    /** Energy, when the charging operator's message states it. */
    val kwh: Double? = null,
    val chargeType: ChargeType? = null,
    val source: MessageSource = MessageSource.SMS,
    val timeMillis: Long = 0,
    /** Stable key for this message so the same text is never booked twice. */
    val fingerprint: String = "",
)

/**
 * Reads card approvals, pay-app and KakaoTalk notifications and operator
 * texts, and decides whether one is a car expense:
 *
 * - tolls: 하이패스, 한국도로공사, 통행료 → [EntryKind.TOLL]
 * - charging: any of the user's charge keywords (환경부, 에바, 수퍼차저…) → [EntryKind.CHARGE]
 * - parking and car wash → [EntryKind.PARKING], [EntryKind.WASH]
 *
 * Cancellations, refunds and declined payments are ignored. Keywords are
 * compared with spaces removed and case folded, so "(주) 에바" and "(주)에바"
 * are the same.
 */
object PaymentParser {
    /** Charging operators and words the user starts with; editable in the logbook. */
    val DEFAULT_CHARGE_KEYWORDS: List<String> = listOf(
        "한국자동차환경협회", "(주) 에바", "(주)에바", "환경부", "에바", "테슬라", "Tesla", "수퍼차저", "슈퍼차저",
        "차지비", "해피차저", "파워큐브", "에버온", "SK일렉링크", "일렉링크", "이피트", "E-pit",
        "GS차지비", "대영채비", "채비", "스타코프", "플러그링크", "볼트업", "전기차충전", "전기충전",
    )

    val TOLL_KEYWORDS: List<String> = listOf("하이패스", "한국도로공사", "통행료", "도로공사", "톨게이트")
    val PARKING_KEYWORDS: List<String> = listOf("주차장", "주차비", "주차요금", "파킹", "Parking", "아이파킹", "카카오T주차", "모두의주차장")
    val WASH_KEYWORDS: List<String> = listOf("세차", "손세차", "카워시", "CarWash")

    /** Words that mean the money went back, or never went out. */
    private val REJECT_WORDS = listOf("취소", "환불", "거절", "승인거절", "실패", "부결", "한도초과", "결제예정", "예약", "입금")

    /** Amounts after these are balances or limits, not the payment. */
    private val NOT_PAYMENT_PREFIX = listOf("누적", "잔액", "잔여", "한도", "포인트", "적립", "가용", "총누적")

    private val AMOUNT = Regex("""(?:₩|KRW\s*)?([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,9})\s*(원|₩)?""")
    private val KWH = Regex("""([0-9]+(?:\.[0-9]+)?)\s*kWh""", RegexOption.IGNORE_CASE)

    fun normalize(s: String): String = s.replace(Regex("\\s+"), "").lowercase()

    /**
     * Reads one message. Returns null when it is not a car expense, has no
     * amount, or is a cancellation. [chargeKeywords] are the user's charge list.
     */
    fun parse(
        text: String,
        chargeKeywords: List<String> = DEFAULT_CHARGE_KEYWORDS,
        source: MessageSource = MessageSource.SMS,
        timeMillis: Long = 0,
    ): ParsedPayment? {
        if (text.isBlank()) return null
        val flat = normalize(text)
        if (REJECT_WORDS.any { flat.contains(normalize(it)) }) return null
        val amount = amount(text) ?: return null
        if (amount <= 0) return null

        fun firstHit(keys: List<String>): String? =
            keys.filter { it.isNotBlank() }.sortedByDescending { normalize(it).length }.firstOrNull { flat.contains(normalize(it)) }

        // Order matters: a 하이패스 top-up at a service area mentions 충전 too, and is a toll.
        val (kind, key) = firstHit(TOLL_KEYWORDS)?.let { EntryKind.TOLL to it }
            ?: firstHit(chargeKeywords)?.let { EntryKind.CHARGE to it }
            ?: firstHit(PARKING_KEYWORDS)?.let { EntryKind.PARKING to it }
            ?: firstHit(WASH_KEYWORDS)?.let { EntryKind.WASH to it }
            ?: return null

        val kwh = if (kind == EntryKind.CHARGE) KWH.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.1..200.0 } else null
        val type = if (kind == EntryKind.CHARGE) chargeTypeOf(flat) else null
        return ParsedPayment(
            kind = kind,
            amountWon = amount,
            keyword = key,
            merchant = merchantLine(text, key),
            kwh = kwh,
            chargeType = type,
            source = source,
            timeMillis = timeMillis,
            fingerprint = fingerprint(text),
        )
    }

    /** Supercharger by name, fast by 급속, else a public fast charger by default (home charging is not paid by card). */
    private fun chargeTypeOf(flat: String): ChargeType = when {
        listOf("테슬라", "tesla", "수퍼차저", "슈퍼차저", "supercharger").any { flat.contains(it) } -> ChargeType.SUPERCHARGER
        flat.contains("완속") -> ChargeType.SLOW
        else -> ChargeType.FAST
    }

    /**
     * The payment: the first amount marked 원 (or ₩/KRW) that is not a
     * balance or a limit. Card texts put the payment first and the running
     * total after it, as "12,340원 일시불 … 누적 523,000원".
     */
    fun amount(text: String): Long? {
        val candidates = AMOUNT.findAll(text).filter { m ->
            val marked = m.groupValues[2].isNotEmpty() || m.value.startsWith("₩") || m.value.startsWith("KRW")
            if (!marked) return@filter false
            val before = text.substring(maxOf(0, m.range.first - 6), m.range.first).replace(" ", "")
            NOT_PAYMENT_PREFIX.none { before.endsWith(it) || before.contains(it) }
        }
        return candidates.firstNotNullOfOrNull { it.groupValues[1].replace(",", "").toLongOrNull() }
    }

    private fun merchantLine(text: String, key: String): String {
        val nk = normalize(key)
        val line = text.lines().map { it.trim() }.firstOrNull { normalize(it).contains(nk) && it.length <= 40 }
        return (line ?: key).take(40)
    }

    /** Same text, same key: whitespace and the [Web발신] prefix do not matter. */
    fun fingerprint(text: String): String = normalize(text.replace("[Web발신]", "")).hashCode().toUInt().toString(16)
}

/** What booking a payment did to the logbook. */
sealed class PaymentBooking {
    abstract val entry: LogEntry

    /** An automatic charge record now carries the price actually paid. */
    data class UpdatedCharge(override val entry: LogEntry, val previousWon: Long) : PaymentBooking()

    /** A new line was added. */
    data class Added(override val entry: LogEntry) : PaymentBooking()
}

object PaymentBooker {
    private val messageLabels = MessageSource.entries.map { it.label }.toSet()

    /** A payment this long after a charge ended can still belong to it. */
    const val MATCH_AFTER_MS = 3 * 3_600_000L

    /** Charging apps sometimes notify a little before the car's state shows the end. */
    const val MATCH_BEFORE_MS = 30 * 60_000L

    /**
     * The same payment can arrive twice in different words: as an SMS and as
     * the messaging app's notification, or a KakaoTalk alert updated in
     * place. Same kind and amount from a message within a short window is
     * taken as already booked.
     */
    fun isDuplicate(entries: List<LogEntry>, p: ParsedPayment): Boolean = entries.any { e ->
        e.kind == p.kind && e.costWon == p.amountWon && e.paid &&
            kotlin.math.abs(e.timeMillis - p.timeMillis) <= if (p.kind == EntryKind.CHARGE) MATCH_AFTER_MS else DUPLICATE_MS
    }

    const val DUPLICATE_MS = 10 * 60_000L

    /**
     * Books [p] into [entries]: a charge payment fills in the latest
     * automatic charge that ended shortly before it and has no confirmed price
     * yet; anything else becomes a new line. [newId] supplies a fresh id.
     */
    fun book(entries: List<LogEntry>, p: ParsedPayment, odometerKm: Int?, newId: () -> Long): PaymentBooking {
        val memo = "${p.source.label} 자동 등록 · ${p.merchant}"
        if (p.kind == EntryKind.CHARGE) {
            val match = entries
                .filter { it.kind == EntryKind.CHARGE && it.auto && !it.paid && it.source !in messageLabels }
                .filter { p.timeMillis - it.timeMillis in -MATCH_BEFORE_MS..MATCH_AFTER_MS }
                .minByOrNull { kotlin.math.abs(p.timeMillis - it.timeMillis) }
            if (match != null) {
                val updated = match.copy(
                    costWon = p.amountWon,
                    paid = true,
                    kwh = match.kwh ?: p.kwh,
                    place = match.place.ifBlank { p.merchant },
                    memo = listOf(match.memo, "결제 확인 ${"%,d".format(p.amountWon)}원 (${p.source.label}: ${p.keyword})").filter { it.isNotBlank() }.joinToString(" · "),
                )
                return PaymentBooking.UpdatedCharge(updated, match.costWon)
            }
        }
        val entry = LogEntry(
            id = newId(),
            kind = p.kind,
            timeMillis = p.timeMillis,
            costWon = p.amountWon,
            odometerKm = odometerKm,
            kwh = p.kwh,
            chargeType = p.chargeType,
            place = p.merchant,
            memo = memo,
            auto = true,
            source = p.source.label,
            paid = true,
        )
        return PaymentBooking.Added(entry)
    }
}
