package com.splittesla.core.logbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentParserTest {
    @Test
    fun chargingCardApprovalFromEnvironmentAgency() {
        val sms = "[Web발신]\n신한카드(1234)승인 홍*동 12,340원(일시불)11/12 14:32 한국자동차환경협회 누적523,000원"
        val p = assertNotNull(PaymentParser.parse(sms, timeMillis = 1000))
        assertEquals(EntryKind.CHARGE, p.kind)
        assertEquals(12_340, p.amountWon)
        assertEquals("한국자동차환경협회", p.keyword)
        assertEquals(ChargeType.FAST, p.chargeType)
    }

    @Test
    fun evaKeywordMatchesWithOrWithoutSpace() {
        val a = assertNotNull(PaymentParser.parse("KB국민카드 승인 8,800원 (주)에바 11/12"))
        val b = assertNotNull(PaymentParser.parse("KB국민카드 승인 8,800원 (주) 에바 11/12"))
        assertEquals(EntryKind.CHARGE, a.kind)
        assertEquals(EntryKind.CHARGE, b.kind)
        assertEquals(8_800, b.amountWon)
    }

    @Test
    fun superchargerIsTyped() {
        val p = assertNotNull(PaymentParser.parse("현대카드 승인 23,450원 TESLA 수퍼차저 kWh 47.2kWh", source = MessageSource.KAKAO))
        assertEquals(ChargeType.SUPERCHARGER, p.chargeType)
        assertEquals(47.2, p.kwh)
        assertEquals(MessageSource.KAKAO, p.source)
    }

    @Test
    fun hipassBecomesToll() {
        val p = assertNotNull(PaymentParser.parse("[하이패스] 서울TG→대전TG 통행료 9,600원 결제"))
        assertEquals(EntryKind.TOLL, p.kind)
        assertEquals(9_600, p.amountWon)
        val k = assertNotNull(PaymentParser.parse("한국도로공사 4,400원 승인"))
        assertEquals(EntryKind.TOLL, k.kind)
    }

    @Test
    fun parkingAndWashAreClassified() {
        assertEquals(EntryKind.PARKING, PaymentParser.parse("삼성카드 승인 3,000원 아이파킹 강남점")?.kind)
        assertEquals(EntryKind.WASH, PaymentParser.parse("삼성카드 승인 15,000원 블루핸즈 손세차")?.kind)
    }

    @Test
    fun ignoresCancelUnrelatedAndAmountless() {
        assertNull(PaymentParser.parse("신한카드 승인취소 12,340원 환경부"))
        assertNull(PaymentParser.parse("신한카드 승인 5,500원 스타벅스"))
        assertNull(PaymentParser.parse("환경부 충전기 고장 안내"))
    }

    @Test
    fun userKeywordsAreUsed() {
        assertNull(PaymentParser.parse("승인 7,000원 우리동네충전", listOf("환경부")))
        val p = assertNotNull(PaymentParser.parse("승인 7,000원 우리동네충전", listOf("우리동네충전")))
        assertEquals(EntryKind.CHARGE, p.kind)
    }

    @Test
    fun amountSkipsBalances() {
        assertEquals(4_400, PaymentParser.amount("잔액 30,000원 하이패스 통행료 4,400원"))
        assertEquals(12_000, PaymentParser.amount("₩12,000 승인"))
    }

    @Test
    fun fingerprintIgnoresWhitespaceAndPrefix() {
        assertEquals(PaymentParser.fingerprint("[Web발신]\n승인 1,000원 환경부"), PaymentParser.fingerprint("승인  1,000원 환경부"))
    }

    @Test
    fun chargePaymentUpdatesRecentAutoCharge() {
        val hour = 3_600_000L
        val auto = LogEntry(5, EntryKind.CHARGE, 10 * hour, 16_000, kwh = 40.0, chargeType = ChargeType.FAST, auto = true, source = "차량", chargedRangeKm = 250.0)
        val p = assertNotNull(PaymentParser.parse("승인 13,880원 환경부", timeMillis = 10 * hour + 20 * 60_000))
        val booked = PaymentBooker.book(listOf(auto), p, 12_000) { 99 }
        val updated = assertIs<PaymentBooking.UpdatedCharge>(booked)
        assertEquals(13_880, updated.entry.costWon)
        assertTrue(updated.entry.paid)
        assertEquals(16_000, updated.previousWon)
        assertEquals(13_880 / 250.0, updated.entry.wonPerChargedKm!!, 1e-9)

        // Too long after, or already paid: a new line.
        val late = p.copy(timeMillis = 20 * hour)
        assertIs<PaymentBooking.Added>(PaymentBooker.book(listOf(auto), late, null) { 99 })
        assertIs<PaymentBooking.Added>(PaymentBooker.book(listOf(updated.entry), p, null) { 99 })
    }

    @Test
    fun sameAmountFromSmsAndNotificationIsBookedOnce() {
        val sms = assertNotNull(PaymentParser.parse("하이패스 통행료 2,900원", timeMillis = 0))
        val entry = PaymentBooker.book(emptyList(), sms, null) { 1 }.entry
        val noti = assertNotNull(PaymentParser.parse("메시지\n[Web발신] 하이패스 통행료 2,900원 승인", PaymentParser.DEFAULT_CHARGE_KEYWORDS, MessageSource.NOTIFICATION, 60_000))
        assertTrue(PaymentBooker.isDuplicate(listOf(entry), noti))
        assertTrue(!PaymentBooker.isDuplicate(listOf(entry), noti.copy(timeMillis = 3_600_000)))
    }

    @Test
    fun tollIsAddedAsNewEntry() {
        val p = assertNotNull(PaymentParser.parse("하이패스 통행료 2,900원", source = MessageSource.SMS, timeMillis = 5))
        val e = PaymentBooker.book(emptyList(), p, 1234) { 42 }.entry
        assertEquals(EntryKind.TOLL, e.kind)
        assertEquals(42, e.id)
        assertEquals(2_900, e.costWon)
        assertEquals(1234, e.odometerKm)
        assertEquals("문자", e.source)
        assertTrue(e.auto)
    }

    @Test
    fun splitMessagesExtractsMultiplePaymentsFromBatchText() {
        val batch = """
            [Web발신]
            신한카드 승인 12,340원 일시불 11/12 한국자동차환경협회

            2026년 11월 13일 오후 2:10, 카톡알림 : [하이패스] 서울TG→판교TG 통행료 2,000원 결제
            2026년 11월 13일 오후 5:40, 카톡알림 : KB국민카드 승인 8,800원 (주) 에바
        """.trimIndent()
        val parsed = PaymentParser.splitMessages(batch).mapNotNull { PaymentParser.parse(it) }
        assertEquals(3, parsed.size)
        assertEquals(EntryKind.CHARGE, parsed[0].kind)
        assertEquals(12_340, parsed[0].amountWon)
        assertEquals(EntryKind.TOLL, parsed[1].kind)
        assertEquals(2_000, parsed[1].amountWon)
        assertEquals(EntryKind.CHARGE, parsed[2].kind)
        assertEquals(8_800, parsed[2].amountWon)
    }
}
