package com.realestatenewsfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadsKitTest {
    // w=100 → 한 장 최대 200px, 여러 장일 때 목표 160px
    private fun rows(h: Int, blankAt: List<IntRange>) = BooleanArray(h) { y -> blankAt.any { y in it } }

    @Test
    fun shortPostStaysSinglePiece() {
        assertEquals(listOf(0, 170), ThreadsKit.cutPoints(rows(170, listOf(80..90)), 100))
    }

    @Test
    fun cutsAtGapsNotInsideImages() {
        // 높이 400: 여백이 130~140, 200~210(사진 안의 검은 띠), 270~280
        val blank = rows(400, listOf(130..140, 200..210, 270..280))
        val cuts = ThreadsKit.cutPoints(blank, 100, avoid = listOf(150..260))
        assertEquals(listOf(0, 135, 275, 400), cuts)
    }

    @Test
    fun noTinyLastPiece() {
        val cuts = ThreadsKit.cutPoints(rows(300, emptyList()), 100) // 여백이 전혀 없어도 고르게
        val sizes = cuts.zipWithNext { a, b -> b - a }
        assertTrue(sizes.all { it >= 90 })
    }

    @Test
    fun blankRowIgnoresThinSideBorders() {
        val white = 0xFFFFFFFF.toInt(); val gray = 0xFF888888.toInt(); val black = 0xFF000000.toInt()
        val bordered = IntArray(1080) { white }.also { it[0] = gray; it[1] = gray; it[1078] = gray; it[1079] = gray }
        assertTrue(ThreadsKit.isBlankRow(bordered))
        // 회색 바탕 + 가운데 흰 카드 (네이트판 같은 레이아웃)
        val card = IntArray(1080) { if (it in 40..1040) white else 0xFFF2F2F2.toInt() }.also { it[40] = gray; it[1040] = gray }
        assertTrue(ThreadsKit.isBlankRow(card))
        val text = IntArray(1080) { if (it in 100..400 && it % 3 == 0) black else white }
        assertTrue(!ThreadsKit.isBlankRow(text))
    }

    @Test
    fun fallsBackToFarGapInsteadOfCuttingText() {
        // 목표 지점(200) 근처엔 여백이 없고 멀리(110~120)에만 있음 → 그 여백에서 자름
        val cuts = ThreadsKit.cutPoints(rows(400, listOf(110..120)), 100)
        assertEquals(115, cuts[1])
    }

    @Test
    fun tallImageIsCutAtItsOwnGaps() {
        // 높이 600짜리 긴 사진 하나(글 캡처)가 본문 전체: 사진 안의 줄 사이 여백(195~205, 395~405)에서 자름
        val cuts = ThreadsKit.cutPoints(rows(600, listOf(195..205, 395..405)), 100, avoid = listOf(0..600))
        assertEquals(listOf(0, 200, 400, 600), cuts)
    }
}

class LongPieceTest {
    private fun rows(h: Int, blankAt: List<IntRange>) = BooleanArray(h) { y -> blankAt.any { y in it } }

    // 커뮤니티 글: 가로의 3배까지는 한 장, 넘으면 2.4배 안팎에서 여백을 찾아 자름
    @org.junit.Test
    fun communityCaptureUsesFewerLongerPieces() {
        org.junit.Assert.assertEquals(listOf(0, 290), ThreadsKit.cutPoints(rows(290, listOf(140..150)), 100, maxRatio = 3f, idealRatio = 2.4f))
        val cuts = ThreadsKit.cutPoints(rows(700, listOf(228..236, 470..480)), 100, maxRatio = 3f, idealRatio = 2.4f)
        org.junit.Assert.assertEquals(listOf(0, 232, 475, 700), cuts)
        // 기본값(뉴스 캡처 등)은 예전처럼 2배·1.6배
        org.junit.Assert.assertEquals(2, ThreadsKit.cutPoints(rows(290, listOf(140..150)), 100).size - 1)
    }
}

class CardWrapTest {
    // 글자 하나의 폭을 10으로 가정
    private fun wrap(text: String, width: Float) = CardMaker.wrapWords(text, width) { it.length * 10f }

    @org.junit.Test
    fun breaksOnlyAtSpaces() {
        org.junit.Assert.assertEquals("7월부터 수도권 주담대\n한도 최대 5000만원\n축소", wrap("7월부터 수도권 주담대 한도 최대 5000만원 축소", 130f))
        org.junit.Assert.assertEquals("가산금리: 1.5%p\n(지방은 0.75%p\n유지)", wrap("가산금리: 1.5%p (지방은 0.75%p 유지)", 130f))
    }

    @org.junit.Test
    fun splitsOnlyVeryLongWords() {
        org.junit.Assert.assertEquals("가나다라마\n바사 아", wrap("가나다라마바사 아", 50f))
        org.junit.Assert.assertEquals("첫 줄\n둘째", wrap("첫 줄\n둘째", 200f))
    }
}
