package com.realestatenewsfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerTest {
    private val raw = """
        [스레드]
        7월부터 수도권 주담대 한도가 줄어요

        스트레스 DSR 3단계가 7월 1일 시행돼요.

        - 수도권 주담대에 가산금리 1.5%p가 적용돼요.

        **6월 30일까지** 계약한 건은 기존 규정이 적용돼요.

        [카드]
        제목: 7월부터 주담대 한도 축소
        - 시행일: 7월 1일
        - 대상: 수도권 주택담보대출
        * 가산금리: 1.5%p
        1. 예외: 6월 30일까지 계약분
    """.trimIndent()

    @Test
    fun parsesThreadAndCard() {
        val s = Summarizer.parse(raw, "원래 제목")!!
        assertTrue(s.thread.startsWith("7월부터 수도권 주담대 한도가 줄어요\n\n스트레스 DSR"))
        assertTrue(s.thread.contains("수도권 주담대에 가산금리 1.5%p가 적용돼요."))
        assertTrue(!s.thread.contains("[") && !s.thread.contains("*") && !s.thread.contains("- "))
        assertEquals("7월부터 주담대 한도 축소", s.headline)
        // 카드 구역에 '제목:'이 없으면 기사 제목을 그대로 씀
        assertEquals("원래 제목", Summarizer.parse(raw.replace("제목: 7월부터 주담대 한도 축소\n", ""), "원래 제목")!!.headline)
        assertEquals(listOf("시행일: 7월 1일", "대상: 수도권 주택담보대출", "가산금리: 1.5%p", "예외: 6월 30일까지 계약분"), s.points)
    }

    @Test
    fun fallsBackWhenCardSectionMissing() {
        val s = Summarizer.parse("첫 줄 요약\n\n둘째 줄\n\n셋째 줄", "원래 제목")!!
        assertEquals("원래 제목", s.headline)
        assertEquals(listOf("둘째 줄", "셋째 줄"), s.points)
        assertNull(Summarizer.parse("  ", "원래 제목"))
    }

    @Test
    fun longThreadIsCutAtLineEnd() {
        val long = (1..40).joinToString("\n\n") { "이것은 ${it}번째 문장이에요. 길이를 채우는 내용." }
        val s = Summarizer.parse(long, "t")!!
        assertTrue(s.thread.length <= Summarizer.MAX_THREAD_CHARS)
        assertTrue(s.thread.endsWith("내용."))
    }

    @Test
    fun promptKeepsFactsAndThreadsTone() {
        val p = Summarizer.system("news", listOf("금리·대출"), casual = true, today = "2026년 11월 15일")
        assertTrue(p.contains("본문에 있는 것만"))
        assertTrue(p.contains("주제를 바꾸지 않는다"))
        assertTrue(p.contains("본문에 없는 연도·날짜를 쓰지 않는다"))
        assertTrue(p.contains("권유"))
        assertTrue(p.contains("오늘은 2026년 11월 15일"))
        assertTrue(p.contains("금리나 한도가 얼마나 바뀌는지"))
        assertTrue(p.contains("~했대"))
        // 화살표는 '쓰지 않는 것' 안내에만 나옴 (형식으로 오해하지 않게)
        assertEquals(1, Regex("→").findAll(p).count())
        assertTrue(Summarizer.system("community", emptyList(), casual = false).contains("개인의 경험·의견"))
    }

    @Test
    fun findsAndDropsInventedNumbers() {
        val source = "제목: 서울 1순위 청약자, 4년 만에 최저\n본문: 일반공급은 21% 늘었지만 1순위 청약신청자는 48% 감소했다. 강남3구 일반분양은 73가구로 전년 대비 88% 급감했다. 경쟁률은 870대 1이다."
        val s = Summary(
            "강남3구 일반분양 73가구, 경쟁률 870대1\n\n강남3구 일반분양 73가구 2024년 현재\n\n오티에르 반포 43가구\n\n신청자는 48% 줄었음",
            "강남3구 분양 73가구", listOf("일반분양: 73가구", "기준: 2024년", "경쟁률: 870대 1"),
        )
        val bad = Summarizer.unsupportedNumbers(s.thread + s.points.joinToString("\n"), source)
        assertEquals(listOf("2024", "43"), bad)
        val cleaned = Summarizer.dropLinesWith(s, bad)
        assertEquals("강남3구 일반분양 73가구, 경쟁률 870대1\n\n신청자는 48% 줄었음", cleaned.thread)
        assertEquals(listOf("일반분양: 73가구", "경쟁률: 870대 1"), cleaned.points)
        assertEquals(emptyList<String>(), Summarizer.unsupportedNumbers("일반공급 21% 증가, 1순위는 48% 감소", source))
    }

    @Test
    fun basicSummaryWithoutAi() {
        val s = Summarizer.basic(Post("a", "mk", "제목이에요", "u", summary = "국토부는 15일 공급 대책을 발표했다. 수도권에 3만 가구를 공급한다."))
        assertEquals("제목이에요", s.headline)
        assertEquals(2, s.points.size)
    }

    @Test
    fun noticeUsesCrawledDetails() {
        val p = Post("a", "applyhome", "[청약] 호반써밋 초량 (부산·민영) 11/23~11/25 접수", "u",
            details = listOf("공급위치: 부산광역시 동구 초량3동", "공급규모: 236세대 (일반 99 · 특별 137)", "59㎡ 분양가: 5억4,820만~5억6,320만원", "청약일정: 특별공급 11/23 · 1순위 11/24 · 2순위 11/25"))
        val s = Summarizer.basic(p)
        assertEquals(p.details, s.points)
        assertTrue(s.thread.contains("59㎡ 분양가: 5억4,820만~5억6,320만원"))
        val input = Summarizer.buildInput(p, "청약홈", "본문 글자")
        assertTrue(input.contains("공고 주요 정보:") && input.contains("공급규모: 236세대"))
        // 상세 정보의 숫자는 원문에 있는 숫자로 인정됨
        assertEquals(emptyList<String>(), Summarizer.unsupportedNumbers("59㎡ 분양가가 5억4,820만원부터래", input))
        assertEquals(p, Post.fromJson(p.toJson()))
    }
}
