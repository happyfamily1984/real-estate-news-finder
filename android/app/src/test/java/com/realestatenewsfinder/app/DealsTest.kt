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
        // 예산별 단지 목록형: 금액 → 지역·평형 TOP N → 단지(평형, 가격) → 임장·질문
        val text = DealTools.budgetText("경기 과천시", 84, 100000, r, casual = true, seed = 0)
        assertEquals(listOf("10억만 모아보자.\n경기 과천시에서 10억으로 살 수 있는 84㎡ 아파트 TOP 2", "1. 가 (84㎡, 9.5억)\n2. 다 (84㎡, 7억)", "아이 있는 3~4인 가족이라면 저장해두고 임장 가봐.\n너는 몇 번이 제일 끌려?"), text.split("\n\n"))
        assertTrue(DealTools.budgetText("서울", 59, 100000, r, casual = true, seed = 1).let { it.startsWith("10억으로 어디까지 갈 수 있을까.") && it.contains("신혼부부나 1~2인 가구라면 저장해두고") })
        val none = DealTools.budgetText("경기 과천시", 84, 50000, DealTools.budget(deals, 84, 50000), casual = true)
        assertTrue(none, none.contains("5억으로는 아직 없음") && none.contains("가장 싼 곳이 다 (84㎡, 7억)"))
        // 시도 전체일 때는 단지 옆에 구 이름
        assertEquals("강남구 원문동", DealTools.budget(deals, 84, 100000, guOf = mapOf("가" to "강남구")).items[0].umdNm)
        // 아파트만 보기: 빌라형 이름, 4층 이하, 거래가 거의 없는 곳은 뺌
        fun many(apt: String, floor: Int, n: Int = 4) = (1..n).map { t(apt, 80000L + it, date = "2026-10-%02d".format(it)).copy(floor = floor + it) }
        val mixed = many("진짜아파트", 5) + many("행복빌", 8) + many("낮은단지", 0) + listOf(t("거래드문곳", 80000)) + many("그린빌라", 9)
        val strict = DealTools.budget(mixed, 84, 100000, aptOnly = true)
        assertEquals(listOf("진짜아파트"), strict.items.map { it.aptNm })
        assertEquals(4, strict.excluded)
        assertEquals(5, DealTools.budget(mixed, 84, 100000).items.size)
        // 기간 안 매매는 1건뿐이어도 최근 전월세 거래가 있으면 아파트로 봄
        val rents = (1..3).map { Deal("jeonse", "거래드문곳", "거래드문곳", "원문동", "1", 2015, 84.9, 10 + it, "2026-09-0$it", deposit = 50000, key = "r$it") }
        assertTrue("거래드문곳" in DealTools.budget(mixed, 84, 100000, aptOnly = true, extra = rents).items.map { it.aptNm })
        assertTrue(DealTools.looksLikeApartment("삼성쉐르빌", many("삼성쉐르빌", 5, 12)) && !DealTools.looksLikeApartment("삼성쉐르빌", many("삼성쉐르빌", 5, 4))) // 큰 주상복합은 남김
        assertTrue(DealTools.looksLikeApartment("래미안빌리지", many("래미안빌리지", 5)))
        assertTrue(DealTools.looksLikeApartment("현대홈타운", many("현대홈타운", 5)) && !DealTools.looksLikeApartment("한빛 빌 2차", many("x", 5)))
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
        // 과거 대비 현재 비교형: 연도 vs 연도 → 연도별 가격 → 한마디 → 해석 한 줄
        val text = DealTools.historyText("가", "과천시 원문동", 84, listOf(now, p10, p20, p30), casual = true, thisYear = 2026)
        assertEquals(listOf("과천시 원문동 아파트 2016년 vs 2026년 가격 비교", "가 84㎡\n2016년: 7억 6,000만원\n2026년: 19억원",
            "안 산 게 아니라 못 산 시간이 이렇게 큰 차이를 만들었어.\n그런데 진짜 중요한 건 이거야.\n→ 10년에 2.5배. 월급으로는 못 따라갈 속도"), text.split("\n\n"))
        assertTrue(DealTools.historyText("가", "과천시 원문동", 84, listOf(now, p20), casual = true, thisYear = 2026).contains("비교할 과거 거래 기록이 없음"))
        // 맨 밑 한줄평: 오른 정도와 최근 흐름에 따라, seed로 표현이 바뀜
        fun pt(y: Int, avg: Long) = DealTools.HistoryPoint(y, if (y == 0) "지금" else "${y}년 전", "", 3, avg, avg, avg)
        assertEquals("두 배가 되는 데 10년이면 충분했음", DealTools.historyVerdict(now, listOf(p10), seed = 1))
        assertEquals("20년 묵혔더니 4.0배. 이래서 다들 버티는구나", DealTools.historyVerdict(pt(0, 400000), listOf(pt(20, 100000), pt(5, 250000))))
        assertEquals("많이 오르긴 했는데 최근 5년은 숨 고르는 중", DealTools.historyVerdict(pt(0, 300000), listOf(pt(10, 150000), pt(5, 290000))))
        assertEquals("길게 보면 올랐는데 5년 전에 산 사람은 아직 물려 있음", DealTools.historyVerdict(pt(0, 200000), listOf(pt(10, 100000), pt(5, 250000))))
        assertEquals("10년 전보다 오히려 싸짐. 집값도 떨어진다는 증거", DealTools.historyVerdict(pt(0, 90000), listOf(pt(10, 100000))))
        assertEquals(null, DealTools.historyVerdict(now, listOf(p20)))
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
        assertTrue(m[0].diff > m[1].diff) // 오른 금액이 큰 순
        // 글: 한마디 → 단지별 두 줄 → 전체 정리
        val today = LocalDate.of(2026, 11, 19)
        val text = DealTools.presaleText("경기 과천시", 84, DealPeriod.Y1, m, casual = true, seed = 0, today = today).split("\n\n")
        assertEquals(4, text.size)
        assertEquals("경기 과천시 분양, 단지 따라 웃고 울었네", text[0])
        assertEquals("경기 과천푸르지오써밋 84㎡\n4년 만에 분양가 대비 5억 오름", text[1])
        assertEquals("경기 힐스테이트과천 84㎡\n3년 만에 분양가 대비 6,000만원 내림", text[2])
        assertTrue(text[3], text[3].startsWith("비교한 2곳 평균 2.2억 오름\n"))
        // 한마디는 seed에 따라 달라짐
        assertTrue(text[0] != DealTools.presaleText("경기 과천시", 84, DealPeriod.Y1, m, casual = true, seed = 1, today = today).split("\n\n")[0])
        // 단지는 3곳까지만
        val many = (1..5).map { k -> m[0].copy(aptNm = "단지$k") }
        val t5 = DealTools.presaleText("부산", 84, DealPeriod.Y1, many, casual = true, today = today).split("\n\n")
        assertEquals(5, t5.size)
        assertTrue(t5[4], t5[4].startsWith("비교한 5곳 중 5곳이 분양가보다 올랐고, 평균은 5억 오름"))
        assertEquals("8개월 만에", DealTools.since("2026-03-10", today))
        assertEquals("3.3억" to "4,385만원", DealTools.shortWon(32623) to DealTools.shortWon(4385))
        assertTrue(DealTools.presaleText("경기 과천시", 84, DealPeriod.Y1, emptyList(), true).contains("견줄 단지가 없음"))
    }

    @Test fun dineOutPage() {
        val html = """<form id="searchForm"><select name="searchYear"><option value="2015">2015</option><option value="2016" selected="selected">2016</option></select>
          <select id="m" name="searchMonth"><option selected value="10">10</option><option value="11">11</option></select></form>
          <table><thead><tr><th scope="col">지역</th><th>냉면</th><th>비빔밥</th><th>김치찌개백반</th><th>삼겹살</th><th>자장면</th><th>삼계탕</th><th>칼국수</th><th>김밥</th></tr></thead>
          <tbody><tr><th>부산광역시</th><td>7,000</td><td>6,500</td><td>5,900</td><td>12,000</td><td>4,300</td><td>12,000</td><td>5,500</td><td>2,000</td></tr>
          <tr><th>서울특별시</th><td><span>8,154</span></td><td>7,846</td><td>5,769</td><td>14,539</td><td>4,769</td><td>13,654</td><td>6,538</td><td>2,115</td></tr></tbody></table>"""
        assertEquals("2016" to "10", DineOut.selected(html))
        val row = DineOut.parse(html)
        assertEquals(4769, row["자장면"])
        assertEquals(2115, row["김밥"])
        assertEquals(8, row.size)
        assertEquals(null, DineOut.selected("<html></html>"))
        assertTrue(DineOut.parse("<table><tr><td>없음</td></tr></table>").isEmpty())
    }

    // ----- 지역별 최고 실거래 단지 -----
    @Test fun topComplexes() {
        val deals = listOf(t("가", 300000, area = 120.0), t("가", 200000), t("나", 250000), t("다", 180000, area = 59.9), t("라", 900000).copy(canceled = true),
            Deal("jeonse", "마", "마", "원문동", "1", 2015, 84.9, 5, "2026-10-01", deposit = 990000, key = "j"))
        val all = DealTools.topDeals(deals, 0, 10)
        assertEquals(listOf("가" to 300000L, "나" to 250000L, "다" to 180000L), all.map { it.deal.aptNm to it.price }) // 단지마다 최고가 한 건, 해제·전세 제외
        assertEquals(listOf("나", "가"), DealTools.topDeals(deals, 84, 10).map { it.deal.aptNm })
        // 달을 나눠 받을 때: 지금까지 결과와 합쳐도 같은 답
        val merged = DealTools.topDeals(deals.drop(2), 0, 10, DealTools.topDeals(deals.take(2), 0, 10))
        assertEquals(all.map { it.deal.key }, merged.map { it.deal.key })
        assertEquals(2, DealTools.topDeals(deals, 0, 2).size)
        val text = DealTools.topText("경기 과천시", 0, DealPeriod.Y5, all, casual = true, seed = 0).split("\n\n")
        assertEquals("경기 과천시 대장 아파트, 실거래가로 줄 세워봄\n최근 5년간 실거래가 기준", text[0])
        assertEquals("※ 5년 이전에 거래된 더 높은 가격이 있을 수 있음", text.last())
        assertTrue(DealTools.topText("경기", 0, DealPeriod.Y1, all, true).let { it.contains("최근 1년간 실거래가 기준") && it.endsWith("※ 1년 이전에 거래된 더 높은 가격이 있을 수 있음") })
        assertEquals("1. 가 30억 (120㎡)\n2. 나 25억 (84㎡)\n3. 다 18억 (59㎡)", text[1])
        assertTrue(text[2], text[2].startsWith("1위와 3위 차이 12억\n"))
        assertEquals("1. 나 25억\n2. 가 20억", DealTools.topText("서울", 84, DealPeriod.Y5, DealTools.topDeals(deals, 84, 10), true).split("\n\n")[1])
        assertTrue(DealTools.topText("서울", 84, DealPeriod.Y5, emptyList(), true).contains("매매 실거래가 없음"))
    }

    // ----- 받은 실거래 자료를 임시 저장해 다시 쓰기 -----
    @Test fun monthCacheSavesCalls() {
        var calls = 0
        val http = HttpGet { _, _ ->
            calls++
            200 to "<response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header><body><items><item><aptNm>가</aptNm><umdNm>동</umdNm><jibun>1</jibun><dealYear>2026</dealYear><dealMonth>1</dealMonth><dealDay>3</dealDay><dealAmount>50,000</dealAmount><excluUseAr>84.9</excluUseAr><floor>3</floor></item></items><totalCount>1</totalCount></body></response>"
        }
        val dir = java.nio.file.Files.createTempDirectory("molit").toFile()
        var now = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, KST).toInstant().toEpochMilli()
        fun client() = MolitClient({ "" }, { "https://proxy.test" }, "", http, dir) { now }
        val hour = 3600_000L
        // 지난 달: 7일 동안은 다시 받지 않음 (앱을 껐다 켜도 = 새 client)
        assertEquals(1, client().fetchMonth("trade", "11680", "202601").size)
        client().fetchMonth("trade", "11680", "202601"); client().fetchMonth("trade", "11680", "202601", memory = false)
        assertEquals(1, calls)
        now += 6 * 24 * hour; client().fetchMonth("trade", "11680", "202601"); assertEquals(1, calls)
        now += 2 * 24 * hour; client().fetchMonth("trade", "11680", "202601"); assertEquals(2, calls)
        // 이번 달: 6시간
        client().fetchMonth("trade", "11680", "202611"); assertEquals(3, calls)
        now += 5 * hour; client().fetchMonth("trade", "11680", "202611"); assertEquals(3, calls)
        now += 2 * hour; client().fetchMonth("trade", "11680", "202611"); assertEquals(4, calls)
        // 알림 확인(force)은 늘 새로 받음
        client().fetchMonth("trade", "11680", "202611", force = true); assertEquals(5, calls)
        dir.deleteRecursively()
    }
}
