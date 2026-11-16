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
        assertTrue(t, t.contains("가장 최근 거래는 10/19 84㎡ 12층 19억원\n같은 면적 기간 내 최고가"))
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

    // ----- 내 돈으로 살 수 있는 집 -----
    private fun t(apt: String, price: Long, area: Double = 84.9, date: String = "2026-10-01", year: Int? = 2015, umd: String = "원문동") =
        Deal("trade", apt, apt, umd, "1", year, area, 10, date, price = price, key = "$apt/$date/$price/$area")

    @Test fun budgetList() {
        val deals = listOf(t("가", 90000), t("가", 100000, date = "2026-11-01"), t("나", 120000), t("다", 70000), t("라", 99000, area = 59.9), t("마", 60000).copy(canceled = true))
        val r = DealTools.budget(deals, 84, 100000)
        assertEquals(listOf("가" to 95000L, "다" to 70000L), r.items.map { it.aptNm to it.avg }) // 예산에 가까운 순, 해제 거래·다른 평형 제외
        assertEquals(listOf("나"), r.over.map { it.aptNm })
        assertEquals(100000L, r.items[0].last.price)
        val text = DealTools.budgetText("경기도 과천시", 84, 100000, DealPeriod.M6, r, casual = true)
        assertTrue(text, text.startsWith("과천시에서 10억원으로 살 수 있는 84㎡ 아파트 (최근 6개월 실거래 평균 기준)"))
        assertTrue(text, text.contains("거래된 3개 단지 중 2곳 가능 (66%)"))
        assertTrue(text, text.contains("1. 가 (원문동·15년) 9억 5,000만원 · 2건"))
        assertTrue(text, text.contains("가장 싼 곳은 다 7억원 (3억원 남음)"))
        assertTrue(text, text.contains("조금 모자란 곳: 나 12억원 (2억원 부족)"))
        val none = DealTools.budgetText("경기도 과천시", 84, 50000, DealPeriod.M6, DealTools.budget(deals, 84, 50000), casual = true)
        assertTrue(none, none.contains("이 예산으로 살 수 있는 곳이 없음") && none.contains("2억원 더 필요"))
        assertTrue(DealTools.inBand(58.0, 59) && DealTools.inBand(60.9, 59) && !DealTools.inBand(61.0, 59) && DealTools.inBand(84.99, 84))
    }

    // ----- 과거 가격 비교 -----
    @Test fun historyCompare() {
        val today = LocalDate.of(2026, 11, 16)
        assertEquals("202611", DealTools.historyYms(today, 0).first())
        assertEquals(12, DealTools.historyYms(today, 0).size)
        assertEquals("201704" to "201605", DealTools.historyYms(today, 10).let { it.first() to it.last() }) // 2016년 11월을 가운데 둔 12개월
        assertEquals(listOf("200704", "200605"), DealTools.historyYms(today, 20).let { listOf(it.first(), it.last()) })
        assertEquals(listOf("200608", "200601"), DealTools.historyYms(LocalDate.of(2026, 3, 1), 20).let { listOf(it.first(), it.last()) }) // 2006년 전은 뺌
        assertTrue(DealTools.historyYms(today, 30).isEmpty())

        val now = DealTools.historyPoint(today, 0, listOf(t("가", 180000), t("가", 200000), t("나", 50000)), "가", "가", "원문동", 84, 2005)
        assertEquals(2 to 190000L, now.count to now.avg)
        // 오래전 자료는 단지 번호가 달라도 동·이름이 같으면 같은 단지
        val old = listOf(Deal("trade", "옛번호", "가", "원문동", "1-1", 2005, 84.9, 5, "2016-10-01", price = 76000, key = "o"))
        val p10 = DealTools.historyPoint(today, 10, old, "가", "가", "원문동", 84, 2005)
        assertEquals(76000L, p10.avg)
        val p20 = DealTools.historyPoint(today, 20, emptyList(), "가", "가", "원문동", 84, 2005)
        assertEquals("그 무렵 같은 평형 거래 기록 없음", p20.reason)
        assertEquals("2010년 준공이라 그때는 없던 단지", DealTools.historyPoint(today, 20, emptyList(), "가", "가", "원문동", 84, 2010).reason)
        val p30 = DealTools.historyPoint(today, 30, null, "가", "가", "원문동", 84, 1990)
        assertEquals("실거래가 공개(2006년) 전이라 자료 없음", p30.reason)
        assertEquals(9.6, DealTools.cagr(190000, 76000, 10)!!, 0.05)
        val text = DealTools.historyText("가", "과천시 원문동", 84, listOf(now, p10, p20, p30), casual = true)
        assertTrue(text, text.contains("지금: 평균 19억원 (최근 1년 2건)"))
        assertTrue(text, text.contains("10년 전(2016년 무렵): 평균 7억 6,000만원 (1건)\n→ 지금까지 11억 4,000만원 올라 2.5배, 연평균 +9.6%"))
        assertTrue(text, text.contains("30년 전: 실거래가 공개(2006년) 전이라 자료 없음"))
        assertTrue(text, text.contains("정리: 10년 동안 2.5배가 됐음"))
    }

    // ----- 분양가 vs 실거래 -----
    @Test fun presaleCompare() {
        val items = DealTools.parsePresale("""{"items":[
          {"id":"1","name":"과천 푸르지오 써밋","date":"2021-12-01","movein":"2024-09","lawd":["41290"],"addr":"경기도 과천시 부림동","prices":{"59":[80000,84000],"84":[100000,110000]}},
          {"id":"2","name":"힐스테이트 과천(잔여세대)","date":"2023-03-01","lawd":["41290"],"addr":"","prices":{"84":[120000,120000]}},
          {"id":"3","name":"다른 동네 단지","date":"2022-01-01","lawd":["11680"],"addr":"","prices":{"84":[90000,90000]}}]}""")
        assertEquals(105000L, items[0].price(84))
        assertEquals(null, items[1].price(59))
        assertTrue(DealTools.sameName("과천 푸르지오 써밋", "과천푸르지오써밋"))
        assertTrue(DealTools.sameName("힐스테이트 과천(잔여세대)", "힐스테이트과천"))
        assertTrue(!DealTools.sameName("자이", "과천자이")) // 너무 짧은 이름은 정확히 같을 때만
        val deals = listOf(t("과천푸르지오써밋", 150000, year = 2024), t("과천푸르지오써밋", 160000, year = 2024), t("힐스테이트과천", 114000, year = 2025), t("오래된단지", 90000, year = 2000), t("다른 동네 단지", 99000, year = 2023))
        val m = DealTools.presaleCompare(items, "41290", deals, 84, 2026)
        assertEquals(listOf("과천푸르지오써밋", "힐스테이트과천"), m.map { it.aptNm })
        assertEquals(155000L to 50000L, m[0].avg to m[0].diff)
        assertEquals(47.6, m[0].pct, 0.05)
        val text = DealTools.presaleText("경기도 과천시", 84, DealPeriod.Y1, m, casual = true)
        assertTrue(text, text.startsWith("과천시 새 아파트, 분양가와 지금 실거래가 (84㎡)"))
        assertTrue(text, text.contains("1. 과천푸르지오써밋 (2021년 분양)\n분양가 10억 5,000만원 → 실거래 15억 5,000만원 (+48%, 5억원 올라)"))
        assertTrue(text, text.contains("정리: 2곳 중 1곳이 분양가보다 비싸게 거래, 평균 +21%\n분양가 아래로 거래된 곳도 1곳"))
    }
}
