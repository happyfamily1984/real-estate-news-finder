package com.realestatenewsfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class DealsTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun parseTradeAndRent() {
        val t = Molit.parse(res("trade.xml"))
        assertEquals(2, t.totalCount)
        val a = Molit.normalizeTrade("11650", t.items[0])
        assertEquals(452000L, a.price)
        assertEquals("2026-11-03", a.date)
        assertTrue(Molit.normalizeTrade("11650", t.items[1]).canceled)
        val r = Molit.parse(res("rent.xml")).items.map { Molit.normalizeRent("11650", it) }
        assertEquals(listOf("jeonse", "monthly"), r.map { it.type })
        assertEquals(a.complexId, r[0].complexId) // 단지명 띄어쓰기 차이는 같은 단지
        assertEquals("45억 2,000만", DealFmt.manwon(452000))
    }

    @Test fun notificationTarget() {
        val t = DealTarget.parse("#/complex/11650/0a1b2c3d4e5f?type=jeonse&n=abc-1")!!
        assertEquals(DealTarget("11650", "0a1b2c3d4e5f", "jeonse", "abc-1"), t)
        assertEquals(t, DealTarget.parse(t.toUri()))
        assertEquals("trade", DealTarget.parse("#/complex/11650/0a1b2c3d4e5f")!!.type)
        assertNull(DealTarget.parse("#/complexes"))
    }

    @Test fun periods() {
        assertEquals(listOf("1개월", "3개월", "6개월", "1년", "3년", "5년"), DealPeriod.entries.map { it.label })
        assertEquals("2025-11-16", DealPeriod.Y1.since(LocalDate.of(2026, 11, 16)))
        assertEquals(61, DealPeriod.Y5.fetchMonths)
    }

    private fun trade(date: String, price: Long, area: Double = 84.93, floor: Int = 10, key: String = "$date/$price/$floor", canceled: Boolean = false) =
        Deal("trade", "c1", "래미안슈르", "원문동", "4", 2008, area, floor, date, price = price, canceled = canceled, key = key)

    @Test fun summaryText() {
        val deals = listOf(
            trade("2026-02-10", 170000), trade("2026-04-02", 174000), trade("2026-08-20", 182000), trade("2026-10-19", 190000, floor = 12),
            trade("2026-09-01", 150000, area = 59.97), trade("2026-10-30", 200000, canceled = true),
        )
        val t = DealText.summary("래미안슈르", "경기도 과천시", "원문동", "trade", DealPeriod.Y1, deals, casual = true)
        assertTrue(t, t.startsWith("래미안슈르 최근 1년 매매 실거래 정리 (과천시 원문동)"))
        assertTrue(t, t.contains("거래 5건, 평균 17억 3,200만원")) // 해제 거래는 빼고 계산
        assertTrue(t, t.contains("최고 19억원 (84㎡ 12층, 10/19)"))
        assertTrue(t, t.contains("59㎡ 15억원 (1건)"))
        assertTrue(t, t.contains("84㎡ 기준, 기간 앞쪽 평균 17억 2,000만원 → 뒤쪽 평균 18억 6,000만원 (+8.1%)"))
        val none = DealText.summary("래미안슈르", "경기도 과천시", "원문동", "jeonse", DealPeriod.M1, emptyList(), casual = false)
        assertTrue(none, none.contains("최근 1개월 동안 신고된 전세 실거래가 없어요"))
    }

    @Test fun freshText() {
        val history = listOf(trade("2026-02-10", 170000), trade("2026-04-02", 174000), trade("2026-08-20", 182000), trade("2026-09-01", 150000, area = 59.97))
        val fresh = trade("2026-10-19", 190000, floor = 12, key = "new")
        val c = DealText.compare(fresh, history + fresh, setOf("new"))
        assertEquals(3, c.count)
        assertEquals(175333L, c.prevAvg)
        assertTrue(c.isRecordHigh)
        assertEquals("2026-08-20", c.last!!.date)
        val t = DealText.fresh("래미안슈르", "경기도 과천시", "원문동", listOf(fresh), history + fresh, DealPeriod.Y1, casual = true)
        assertTrue(t, t.startsWith("최근 래미안슈르 실거래 발생 (과천시 원문동)"))
        assertTrue(t, t.contains("[매매] 84㎡ 12층 19억원 (계약 10/19)"))
        assertTrue(t, t.contains("같은 면적 최근 1년 평균 17억 5,333만원(3건)보다 1억 4,667만원 높음 (+8.4%)"))
        assertTrue(t, t.contains("기간 내 최고가 18억 2,000만원을 넘은 신고가"))
        assertTrue(t, t.contains("직전 거래(8/20 10층 18억 2,000만원)보다 8,000만원 올랐음"))
        // 비교할 같은 면적 거래가 없을 때
        val alone = DealText.fresh("래미안슈르", "경기도 과천시", "원문동", listOf(trade("2026-10-01", 99000, area = 40.0, key = "x")), history, DealPeriod.M6, casual = false)
        assertTrue(alone, alone.contains("최근 6개월 동안 같은 면적 매매 거래가 없어서 비교할 값이 없어요"))
    }

    // ----- 가짜 국토부 서버로 구독 → 새 거래 확인 → 알림 -----
    @Test fun subscribeAndPoll() {
        val trades = mutableListOf<String>()
        fun item(day: Int, amount: String, floor: Int, canceled: Boolean = false) =
            "<item><aptNm>테스트 아파트</aptNm><umdNm>반포동</umdNm><jibun>1</jibun><buildYear>2009</buildYear><dealYear>2026</dealYear><dealMonth>11</dealMonth><dealDay>$day</dealDay><dealAmount>$amount</dealAmount><excluUseAr>84.9</excluUseAr><floor>$floor</floor><cdealType>${if (canceled) "O" else ""}</cdealType></item>"
        var lastUrl = ""
        val http = HttpGet { url, _ ->
            lastUrl = url
            val ym = Regex("DEAL_YMD=(\\d+)").find(url)!!.groupValues[1]
            val items = if (ym == "202611" && url.contains("/molit/trade")) trades.joinToString("") else ""
            200 to "<response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header><body><items>$items</items><totalCount>${if (items.isEmpty()) 0 else trades.size}</totalCount></body></response>"
        }
        val now = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, KST).toInstant().toEpochMilli()
        val shown = mutableListOf<Triple<String, String, String>>()
        val store = DealStore(null)
        val molit = MolitClient({ "" }, { "https://proxy.test" }, "", http, null) { now }
        val service = DealService(molit)
        val alerts = DealAlerts(store, molit, service, { _, title, body, hash -> shown += Triple(title, body, hash); true }) { now }

        trades += item(3, "452,000", 12)
        val complex = service.getComplexes("11650").single()
        assertTrue(lastUrl, lastUrl.startsWith("https://proxy.test/molit/"))
        val id = complex.getString("id")
        val (_, recent) = alerts.subscribe("11650", id, "trade")
        assertEquals(1, recent)
        assertTrue(shown.last().first.contains("알림 시작"))
        assertEquals(0, alerts.poll().sent) // 새로 신고된 거래 없음

        trades += item(10, "460,000", 7)
        val r = alerts.poll()
        assertEquals(1, r.sent)
        val (title, body, hash) = shown.last()
        assertEquals("🏠 테스트 아파트 새 실거래 1건 (매매 1)", title)
        assertTrue(body, body.contains("46억"))
        // 알림을 누르면 그 단지로 가고, 알림에 담긴 거래가 '새 거래'가 됨
        val target = DealTarget.parse(hash)!!
        assertEquals("11650" to id, target.lawdCd to target.complexId)
        val n = store.notifications.objects().last()
        assertEquals(target.notificationId, n.getString("id"))
        assertEquals(460000L, Deal.fromJson(n.getJSONArray("deals").getJSONObject(0)).price)
        assertFalse(alerts.poll().sent > 0) // 같은 거래를 두 번 알리지 않음
        assertNotNull(alerts.unsubscribe(store.subscriptions.objects().single().getString("id")))
    }
}
