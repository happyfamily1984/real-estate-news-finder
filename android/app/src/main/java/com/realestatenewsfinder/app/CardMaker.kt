package com.realestatenewsfinder.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 스레드용 카드 이미지 (1080×1350, 4:5). 기사 화면을 캡처하지 않고 직접 그림:
 *  1장: 표지 (주제 · 큰 제목 · 매체명 · 날짜)
 *  2장~: 핵심 정리 ("항목: 내용" 줄. 항목은 강조색)
 */
object CardMaker {
    const val W = 1080
    const val H = 1350
    private const val PAD = 84f
    private const val MAX_POINTS_PER_CARD = 5

    private const val NAVY = 0xFF0F1B3D.toInt()
    private const val BLUE = 0xFF2563EB.toInt()
    private const val SKY = 0xFF8FB6FF.toInt()
    private const val INK = 0xFF111827.toInt()
    private const val GRAY = 0xFF6B7280.toInt()
    private const val PAPER = 0xFFF7F9FC.toInt()

    data class Meta(val tag: String, val sourceName: String, val time: Long, val handle: String = "")

    private fun paint(size: Float, color: Int, bold: Boolean = true) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private fun layout(text: CharSequence, p: TextPaint, width: Int, spacing: Float = 1.22f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, spacing).setIncludePad(false).build()

    /** 높이(maxH)와 줄 수(maxLines) 안에 들어갈 때까지 글자 크기를 줄임 */
    private fun fit(text: CharSequence, p: TextPaint, width: Int, maxH: Float, maxLines: Int, minSize: Float): StaticLayout {
        var l = layout(text, p, width)
        while ((l.height > maxH || l.lineCount > maxLines) && p.textSize > minSize) {
            p.textSize -= 4f
            l = layout(text, p, width)
        }
        return l
    }

    private fun Canvas.draw(l: StaticLayout, x: Float, y: Float) { save(); translate(x, y); l.draw(this); restore() }

    private fun date(t: Long) = SimpleDateFormat("yyyy.M.d", Locale.KOREA).format(Date(t))

    /** "항목: 내용" 형식이면 항목만 강조색으로 */
    private fun pointText(line: String): CharSequence {
        val i = line.indexOf(':').takeIf { it in 1..10 } ?: line.indexOf('：').takeIf { it in 1..10 } ?: return line
        return SpannableString(line).apply { setSpan(ForegroundColorSpan(BLUE), 0, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun footer(c: Canvas, meta: Meta, color: Int, y: Float) {
        val p = paint(34f, color, bold = false)
        c.drawText("${meta.sourceName} · ${date(meta.time)}", PAD, y, p)
        if (meta.handle.isNotBlank()) {
            val hp = paint(34f, color)
            c.drawText(meta.handle, W - PAD - hp.measureText(meta.handle), y, hp)
        }
    }

    fun cover(headline: String, meta: Meta): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), NAVY, 0xFF1E3A8A.toInt(), Shader.TileMode.CLAMP)
        })
        // 장식: 오른쪽 위 큰 원
        c.drawCircle(W - 60f, 90f, 330f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF })
        c.drawCircle(W - 40f, 60f, 190f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF })

        // 주제 알약
        val tp = paint(40f, 0xFFFFFFFF.toInt())
        val tw = tp.measureText(meta.tag)
        val pill = RectF(PAD, 150f, PAD + tw + 64f, 150f + 78f)
        c.drawRoundRect(pill, 39f, 39f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE })
        c.drawText(meta.tag, pill.left + 32f, pill.top + 53f, tp)

        // 큰 제목 (세로 가운데쯤)
        val hp = paint(96f, 0xFFFFFFFF.toInt())
        val l = fit(headline, hp, (W - PAD * 2).toInt(), 700f, 6, 56f)
        val top = 300f + (760f - l.height) / 2f
        c.drawRect(PAD, top - 44f, PAD + 120f, top - 34f, Paint().apply { color = SKY })
        c.draw(l, PAD, top)

        footer(c, meta, 0xCCFFFFFF.toInt(), H - 100f)
        return bmp
    }

    fun points(headline: String, points: List<String>, meta: Meta, page: Int, pages: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(PAPER)
        c.drawRect(0f, 0f, W.toFloat(), 18f, Paint().apply { color = BLUE })

        c.drawText(if (pages > 1) "핵심만 정리 $page/$pages" else "핵심만 정리", PAD, 150f, paint(38f, BLUE))
        val hl = fit(headline, paint(60f, INK), (W - PAD * 2).toInt(), 230f, 3, 44f)
        c.draw(hl, PAD, 190f)
        var y = 190f + hl.height + 50f
        c.drawRect(PAD, y, W - PAD, y + 3f, Paint().apply { color = 0xFFDDE3EE.toInt() })
        y += 56f

        // 항목들: 남은 높이에 다 들어가도록 글자 크기를 함께 줄임
        val textW = (W - PAD * 2 - 96f).toInt()
        val avail = H - 170f - y
        var size = 54f
        var layouts: List<StaticLayout>
        var gap: Float
        while (true) {
            layouts = points.map { layout(pointText(it), paint(size, INK), textW, 1.28f) }
            gap = size * 0.85f
            val total = layouts.sumOf { it.height } + gap * (layouts.size - 1)
            if (total <= avail || size <= 34f) break
            size -= 3f
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }
        val num = paint(size * 0.62f, 0xFFFFFFFF.toInt())
        layouts.forEachIndexed { i, l ->
            val r = size * 0.56f
            val cy = y + size * 0.62f
            c.drawCircle(PAD + r, cy, r, dot)
            val n = "${i + 1 + (page - 1) * MAX_POINTS_PER_CARD}"
            c.drawText(n, PAD + r - num.measureText(n) / 2f, cy + num.textSize * 0.36f, num)
            c.draw(l, PAD + 96f, y)
            y += l.height + gap
        }

        footer(c, meta, GRAY, H - 100f)
        return bmp
    }

    /**
     * 뉴스 기사용 첫 이미지: 페이지 캡처에서 제목 ~ 날짜 ~ 요약(부제) 부분만 잘라, 위에 매체명을 붙임.
     * 첫 사진이 나오기 전까지만 자르고, 사진이 멀리 있으면 가로의 1.35배에서 가장 가까운 여백에서 자름.
     * 제목 위치를 못 찾았거나 잘라낸 부분이 너무 짧으면 null (→ 직접 그린 표지 카드 사용)
     */
    fun articleHeader(src: Bitmap, title: android.graphics.Rect?, images: List<android.graphics.Rect>, meta: Meta): Bitmap? {
        if (title == null || title.height() <= 0 || title.top >= src.height) return null
        val w = src.width
        val row = IntArray(w)
        fun blank(y: Int): Boolean { src.getPixels(row, 0, w, 0, y, w, 1); return ThreadsKit.isBlankRow(row) }
        val top = (title.top - w * 3 / 100).coerceAtLeast(0)
        val limit = minOf(src.height, top + w * 135 / 100)
        val firstImage = images.filter { it.top >= title.bottom - 4 && it.height() > w / 5 }.minOfOrNull { it.top }
        var bottom = limit
        if (firstImage != null && firstImage <= limit) bottom = firstImage
        else while (bottom > title.bottom + w / 5 && !blank(bottom - 1)) bottom-- // 글 중간이면 위쪽 가장 가까운 여백까지
        while (bottom > title.bottom && blank(bottom - 1)) bottom-- // 아래 빈 공간 제거
        bottom = minOf(src.height, bottom + w * 4 / 100)
        if (bottom - top < w / 4) return null

        val scale = W.toFloat() / w
        val bodyH = ((bottom - top) * scale).toInt()
        val headH = 150
        val out = Bitmap.createBitmap(W, headH + bodyH + 40, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFFFFFFF.toInt())
        c.drawRect(0f, 0f, W.toFloat(), 18f, Paint().apply { color = BLUE })
        val side = (title.left * scale).coerceIn(36f, PAD) // 기사 글과 왼쪽 줄 맞춤
        c.drawText(meta.sourceName, side, 104f, paint(46f, BLUE))
        val dp = paint(32f, GRAY, bold = false)
        val right = if (meta.handle.isNotBlank()) meta.handle else meta.tag
        c.drawText(right, W - side - dp.measureText(right), 100f, dp)
        c.drawRect(side, headH - 4f, W - side, headH - 1f, Paint().apply { color = 0xFFDDE3EE.toInt() })
        c.drawBitmap(src, android.graphics.Rect(0, top, w, bottom), android.graphics.Rect(0, headH, W, headH + bodyH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** 핵심 정리 카드들만 (첫 이미지를 기사 캡처로 쓸 때) */
    fun pointCards(title: String, points: List<String>, meta: Meta): List<Bitmap> {
        val chunks = points.filter { it.isNotBlank() }.chunked(MAX_POINTS_PER_CARD)
        return chunks.mapIndexed { i, ps -> points(title, ps, meta, i + 1, chunks.size) }
    }

    /** 표지 + 핵심 정리 카드들 */
    fun render(title: String, summary: Summary?, meta: Meta): List<Bitmap> {
        val headline = summary?.headline?.takeIf { it.isNotBlank() } ?: title
        val out = mutableListOf(cover(headline, meta))
        val chunks = summary?.points.orEmpty().filter { it.isNotBlank() }.chunked(MAX_POINTS_PER_CARD)
        chunks.forEachIndexed { i, ps -> out += points(title, ps, meta, i + 1, chunks.size) }
        return out
    }
}
