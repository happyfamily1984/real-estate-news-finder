package com.realestatenewsfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MyTest {
    private val json = """
    {"version":1,"generatedAt":"2026-11-21T02:30:00.000Z","today":"2026-11-21","court":"수원지방법원 안양지원","notes":["오피스텔 실거래는 국토부 키가 있어야 받을 수 있어요."],
     "complexes":[
      {"id":"acro-tower","name":"평촌아크로타워","kind":"offi","lawdCd":"41173","region":"안양시 동안구","umdNm":"","aptNm":"","buildYear":null,"complexId":"","hasData":false,
       "counts":{"trade12":0,"tradePrev":0,"jeonse12":0,"monthly12":0,"canceled12":0},"areas":[],"recentTrades":[],"recentRents":[],"insights":["오피스텔 실거래 자료가 아직 연결되지 않았어요."],"post":null,"auctionCount":0},
      {"id":"hangaram-silla","name":"한가람신라","kind":"apt","lawdCd":"41173","region":"안양시 동안구","umdNm":"관양동","aptNm":"한가람(신라)","buildYear":1992,"complexId":"0123456789ab","hasData":true,
       "counts":{"trade12":135,"tradePrev":82,"jeonse12":124,"monthly12":60,"canceled12":1},
       "areas":[{"area":46,"exact":[45.8],"trade":{"count12":28,"median12":44100,"min12":34500,"max12":60000,"prevCount":20,"prevMedian":39000,"changePct":13.1,"high":{"price":60000,"date":"2026-10-02","floor":9},"last":{"price":44000,"date":"2026-11-01","floor":3}},
                 "jeonse":{"count12":30,"median12":23000,"last":null},"monthly":{"count12":0,"medianDeposit":0,"medianRent":0},"jeonseRate":52},
                {"area":60,"exact":[59.76],"trade":{"count12":0,"median12":0,"min12":0,"max12":0,"prevCount":0,"prevMedian":0,"changePct":null,"high":null,"last":null},
                 "jeonse":{"count12":0,"median12":0,"last":null},"monthly":{"count12":2,"medianDeposit":5000,"medianRent":130},"jeonseRate":null}],
       "recentTrades":[{"type":"trade","date":"2026-11-01","area":45.8,"floor":3,"price":44000,"deposit":0,"monthlyRent":0,"canceled":false}],
       "recentRents":[{"type":"monthly","date":"2026-10-20","area":59.76,"floor":7,"price":0,"deposit":5000,"monthlyRent":130,"canceled":false}],
       "insights":["최근 1년 매매 135건"],
       "post":{"title":"한가람신라 실거래 정리","summary":"최근 1년 매매 135건","thread":"스레드 글","talk":"오픈톡 글","blog":{"title":"동안구 관양동 한가람신라 실거래가","body":"본문","tags":["한가람신라"]},"cards":[{"title":"면적별","points":["46㎡: 4.41억"]}],"sources":["국토교통부"]},
       "auctionCount":1}],
     "auctions":[
      {"id":"B000254-20250130101547-1","complexId":"hangaram-silla","complexName":"한가람신라","caseNo":"2025타경101547","court":"안양지원","dept":"경매3계","tel":"031-8086-1283","usage":"아파트",
       "address":"경기도 안양시 동안구 관양동 1589-1 한가람아파트 404동 3층305호","unit":"404동 3층305호","area":45.8,"appraisal":383000000,"minPrice":383000000,"minRate":100,"failCount":0,
       "saleDate":"2026-12-01","saleTime":"10:00","place":"제104호입찰법정","decisionDate":"2026-12-08","caseName":"부동산임의경매","receivedDate":"2025-11-03","claim":91220406,
       "rights":"임차권등기 있음","topPriority":"2023.02.23. 근저당권","specRemark":"","remark":"","landShare":"대 33650.4㎡ 중 28.569",
       "schedule":[{"date":"2026-12-01","time":"10:00","kind":"매각기일","place":"","result":"","minPrice":383000000,"soldPrice":0}],"appraisalNotes":["주위환경은 보통임."],
       "firstSeen":"2026-11-21T02:30:00.000Z","gone":false,
       "post":{"title":"[경매] 한가람신라 45.8㎡ · 최저가 3.83억","thread":"경매 글","talk":"","blog":{"title":"경매 제목","body":"경매 본문","tags":["경매"]},"cards":[],"sources":[]}},
      {"id":"B000254-2024-9","complexId":"hangaram-silla","complexName":"한가람신라","caseNo":"2024타경9","court":"안양지원","dept":"경매1계","appraisal":500000000,"minPrice":400000000,"minRate":80,"failCount":1,"saleDate":"2026-11-10","gone":true,"post":null}]}
    """.trimIndent()

    @Test
    fun `내 단지 자료를 읽음 - 단지 분석, 경매, 올릴 글`() {
        val d = My.parse(json)
        assertEquals(2, d.complexes.size)
        val offi = d.complexes[0]
        assertEquals("오피스텔", offi.kindLabel)
        assertNull(offi.target); assertNull(offi.post); assertNull(offi.buildYear)
        val c = d.complexes[1]
        assertEquals(DealTarget("41173", "0123456789ab"), c.target)
        assertEquals(listOf(135, 82, 124, 60), listOf(c.trade12, c.tradePrev, c.jeonse12, c.monthly12))
        assertEquals(MyArea(46, 28, 44100, 34500, 60000, 13.1, 60000, "2026-10-02", 30, 23000, 52, 0, 0, 0), c.areas[0])
        assertNull(c.areas[1].changePct); assertNull(c.areas[1].jeonseRate)
        assertEquals("4억 4,000만", c.recentTrades[0].amount())
        assertEquals("5,000만 / 월 130만", c.recentRents[0].amount())
        assertEquals("my-hangaram-silla-2026-11-21", c.post!!.id)
        assertEquals("오픈톡 글", c.post!!.textFor(Platform.OPENTALK))
        assertEquals(1, d.live.size)
        val a = d.auctions[0]
        assertEquals("3억 8,300만원", My.won(a.minPrice))
        assertEquals("D-10", a.dday(LocalDate.of(2026, 11, 21)))
        assertEquals("오늘", a.dday(LocalDate.of(2026, 12, 1)))
        assertEquals("auction-B000254-20250130101547-1-383000000", a.post!!.id)
        assertEquals("경매 글", a.post!!.textFor(Platform.OPENTALK)) // 오픈톡 글이 비면 스레드 글
        assertEquals("매각기일", a.schedule[0].kind)
        assertTrue(d.auctions[1].gone); assertNull(d.auctions[1].area)
        assertTrue(d.time > 0)
    }

    @Test
    fun `경매 알림 - 처음엔 기준점만, 새 물건과 최저가가 바뀐 물건만 알림`() {
        val d = My.parse(json)
        val a = d.auctions[0]
        assertTrue(My.freshAuctions(d, null).isEmpty())
        assertEquals(listOf(a to true), My.freshAuctions(d, emptySet()))
        assertTrue(My.freshAuctions(d, setOf(a.stamp)).isEmpty())
        // 유찰로 최저가가 내려가기 전 표시만 기억하고 있으면 '바뀜'으로 다시 알림
        assertEquals(listOf(a to false), My.freshAuctions(d, setOf("${a.id}|478750000|2026-10-27")))
        assertEquals("⚖️ 한가람신라 경매 물건이 나왔어요", My.alertTitle(a, true))
        val body = My.alertBody(a)
        assertTrue(body, body.contains("404동 3층305호 · 전용 45.8㎡"))
        assertTrue(body, body.contains("최저가 3억 8,300만원 (감정가 3억 8,300만원의 100%) · 유찰 0회"))
        assertNotNull(a.post)
    }

    @Test
    fun `수집기가 실제로 만든 자료도 읽힘`() {
        val f = java.io.File(System.getenv("MY_JSON") ?: return)
        if (!f.exists()) return
        val d = My.parse(f.readText())
        assertEquals(6, d.complexes.size)
        assertEquals(4, d.complexes.count { it.hasData && it.post != null && it.target != null })
    }
}
