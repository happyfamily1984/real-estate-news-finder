package com.realestatenewsfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    private val sido = listOf("서울", "부산", "대구", "인천", "세종", "제주")
    private val json = """{"version":1,"updatedAt":1794700000000,"checkedAt":1794700000000,"sido":["서울","부산","대구","인천","세종","제주"],
      "series":[{"id":"kb-sale-wk","title":"아파트 매매가격 주간 변동률","short":"매매가","unit":"%","digits":2,"cycle":"주간","kind":"change","desc":"설명","source":"KB부동산",
        "date":"2026-11-09","dates":["2026-11-02","2026-11-09"],
        "regions":[{"name":"전국","values":[0.09,0.07]},{"name":"서울","values":[0.12,0.1]},{"name":"수도권","values":[0.1,0.08]},
                   {"name":"부산","values":[0.01,-0.02]},{"name":"대구","values":[-0.03,-0.05]},{"name":"인천","values":[0.02,0.03]},{"name":"세종","values":[0.2,0.25]},{"name":"제주","values":[0.0,0.0]}]},
       {"id":"kb-buyer-wk","title":"매수우위지수","short":"매수우위","unit":"","digits":1,"cycle":"주간","kind":"level","base":100,"desc":"100보다 크면 사려는 사람이 많음","source":"KB부동산",
        "date":"2026-11-09","dates":["2026-11-02","2026-11-09"],"regions":[{"name":"전국","values":[41.1,39.5]},{"name":"서울","values":[60.2,54.9]}]},
       {"id":"kb-pir","title":"PIR (소득 대비 집값)","short":"PIR","unit":"배","digits":1,"cycle":"분기","kind":"level","desc":"설명","source":"KB부동산",
        "date":"2026-06","dates":["2026-05","2026-06"],"regions":[{"name":"서울","values":[10.4,null]}]}]}"""

    @Test
    fun parsesAndFormats() {
        val st = Stats.parse(json)
        assertEquals(3, st.series.size)
        val s = st.series[0]
        assertEquals("11/9", s.dateLabel)
        assertEquals("+0.07%", s.fmt(s.region("전국")!!.latest))
        assertEquals("-0.05%", s.fmt(-0.05))
        assertEquals("0.00%", s.fmt(-0.001))
        assertEquals(0.09, s.region("전국")!!.prev!!, 1e-9)
        assertEquals("2026년 6월", st.series[2].dateLabel)
        assertEquals(null, st.series[2].region("서울")!!.latest)
        assertEquals("https://raw.githubusercontent.com/o/r/data/stats.json", StatsRepository.urlOf("https://raw.githubusercontent.com/o/r/data/posts.json"))
    }

    @Test
    fun changeRateThreadUsesExactNumbers() {
        val s = Stats.parse(json).series[0]
        val t = StatText.thread(s, sido, casual = true)
        assertEquals(
            listOf(
                "아파트 매매가격 주간 변동률, 전국 +0.07% (KB부동산 11/9 기준)",
                "서울 +0.10%, 수도권 +0.08%임",
                "제일 많이 오른 곳은 세종(+0.25%)",
                "제일 많이 내린 곳은 대구(-0.05%)",
                "17개 시도 중 오른 곳 3곳, 내린 곳 2곳",
                "전국 상승폭은 지난주(+0.09%)보다 줄었음",
            ),
            t.split("\n\n"),
        )
        assertTrue(StatText.thread(s, sido, casual = false).contains("줄었어요"))
    }

    @Test
    fun levelThreadExplainsIndex() {
        val s = Stats.parse(json).series[1]
        val t = StatText.thread(s, sido, casual = true).split("\n\n")
        assertEquals("매수우위지수, 전국 39.5 (KB부동산 11/9 기준)", t[0])
        assertEquals("서울 54.9임", t[1])
        assertEquals("전국은 지난주(41.1)보다 1.6 내렸음", t[2])
        assertEquals("100보다 크면 사려는 사람이 많음", t[3])
    }

    @Test
    fun regionsWithParents() {
        val j = """{"version":2,"updatedAt":1,"sido":["서울","경기"],
          "parents":{"서울 강남구":["서울 동남권","서울 강남","서울"],"서울 서초구":["서울 동남권","서울 강남","서울"],"서울 동남권":["서울 강남","서울"],"서울 강남":["서울"],"경기 성남시":["경기 경부1권","경기"],"경기 성남시 분당구":["경기 성남시","경기 경부1권","경기"]},
          "series":[{"id":"reb-sale-wk","title":"아파트 매매가격 주간 변동률","short":"매매가","unit":"%","digits":2,"cycle":"주간","kind":"change","desc":"","source":"한국부동산원",
            "date":"2026-11-09","dates":["2026-10-19","2026-10-26","2026-11-02","2026-11-09"],
            "regions":[{"name":"전국","values":[0.05,0.06,0.07,0.07]},{"name":"서울","values":[0.1,0.1,0.1,0.1]},{"name":"서울 강남구","values":[0.2,0.2,0.3,0.25]},{"name":"서울 서초구","values":[0.1,0.1,0.1,0.15]},{"name":"서울 동남권","values":[0.1,0.1,0.2,0.2]}]},
           {"id":"reb-buy-supply-wk","title":"매매수급지수","short":"매매수급","unit":"","digits":1,"cycle":"주간","kind":"level","base":100,"desc":"","source":"한국부동산원",
            "date":"2026-11-09","dates":["2026-11-02","2026-11-09"],"regions":[{"name":"전국","values":[99.6,99.5]},{"name":"서울","values":[109.4,109.2]},{"name":"서울 동남권","values":[111.0,112.5]}]}]}"""
        val st = Stats.parse(j)
        assertEquals(listOf("서울 강남구", "서울 서초구", "서울 강남", "서울 동남권"), st.childrenOf("서울"))
        assertEquals("서울", st.sidoOf("서울 강남구"))
        assertEquals("강남구", st.shortName("서울 강남구"))
        assertEquals(listOf("서울 강남구", "서울 서초구"), st.siblings("서울 강남구"))
        assertEquals(listOf("경기 성남시"), st.siblings("경기 성남시 분당구")) // 구가 딸린 시의 구는 순위에서 뺌
        // 강남구 단위 자료가 없는 지표는 가장 가까운 상위 지역(동남권) 값
        assertEquals(listOf("reb-sale-wk" to "서울 강남구", "reb-buy-supply-wk" to "서울 동남권"), st.seriesFor("서울 강남구").map { it.first.id to it.second })
        assertEquals(0.9527, RegionText.cumulative(listOf(0.2, 0.2, 0.3, 0.25), 4)!!, 1e-3)
        val t = RegionText.thread(st, "서울 강남구", st.series, casual = true)
        assertTrue(t, t.startsWith("서울 강남구 부동산 지표 정리 (11/9 기준)"))
        assertTrue(t, t.contains("한국부동산원 매매가 +0.25% (서울 +0.10%, 전국 +0.07%)"))
        assertTrue(t, t.contains("누적 최근 4주 +0.95%"))
        assertTrue(t, t.contains("한국부동산원 매매수급 (서울 동남권 기준) 112.5 (서울 109.2, 전국 99.5)"))
        assertTrue(t, t.contains("직전보다 1.5 올랐음"))
        // 예전 형식(parents 없음)도 그대로 읽힘
        assertTrue(Stats.parse(json).parents.isEmpty())
    }
}
