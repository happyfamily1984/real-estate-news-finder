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
        assertEquals(listOf("시행일: 7월 1일", "대상: 수도권 주택담보대출", "가산금리: 1.5%p", "예외: 6월 30일까지 계약분"), s.points)
    }

    @Test
    fun fallsBackWhenCardSectionMissing() {
        val s = Summarizer.parse("첫 줄 요약\n\n둘째 줄\n\n셋째 줄", "원래 제목")!!
        assertEquals("첫 줄 요약", s.headline)
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
    fun promptForbidsInventedNumbersAndAdvice() {
        val p = Summarizer.system("news", listOf("금리·대출"), casual = false)
        assertTrue(p.contains("본문에 없는 숫자는 절대 만들지 않는다"))
        assertTrue(p.contains("투자 조언"))
        assertTrue(p.contains("금리나 한도가 얼마나 바뀌는지"))
        assertTrue(Summarizer.system("community", emptyList(), casual = true).contains("개인의 경험·의견"))
    }

    @Test
    fun basicSummaryWithoutAi() {
        val s = Summarizer.basic(Post("a", "mk", "제목이에요", "u", summary = "국토부는 15일 공급 대책을 발표했다. 수도권에 3만 가구를 공급한다."))
        assertEquals("제목이에요", s.headline)
        assertEquals(2, s.points.size)
    }
}
