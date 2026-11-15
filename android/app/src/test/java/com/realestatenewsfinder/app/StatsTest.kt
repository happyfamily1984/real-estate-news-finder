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
}
