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
