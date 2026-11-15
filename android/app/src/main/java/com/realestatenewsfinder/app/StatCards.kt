package com.realestatenewsfinder.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max

/**
 * 통계용 카드 이미지 (1080×1350): ① 지역별 순위 막대 ② 전국·서울·수도권 추이 선 그래프.
 * 오른 값은 빨강, 내린 값은 파랑 (국내 시세 표기 관례)
 */
object StatCards {
    private const val W = CardMaker.W
    private const val H = CardMaker.H
    private const val PAD = 72f
    private const val UP = 0xFFE5484D.toInt()
    private const val DOWN = 0xFF2563EB.toInt()
    private const val INK = 0xFF111827.toInt()
    private const val GRAY = 0xFF6B7280.toInt()
    private const val LINE = 0xFFDDE3EE.toInt()
    private const val PAPER = 0xFFF7F9FC.toInt()
    private val TREND_COLORS = intArrayOf(0xFF111827.toInt(), UP, 0xFF12A150.toInt())

    private fun text(size: Float, color: Int, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun colorOf(s: StatSeries, v: Double): Int = when {
        s.kind == "change" -> if (v > 0) UP else if (v < 0) DOWN else GRAY
        s.base != null -> if (v >= s.base) UP else DOWN
        else -> DOWN
    }

    /** 공통 머리: 파란 띠, 제목, 기준일. 반환: 본문 시작 y */
    private fun header(c: Canvas, s: StatSeries, sub: String): Float {
        c.drawColor(PAPER)
        c.drawRect(0f, 0f, W.toFloat(), 18f, fill(DOWN))
        c.drawText(sub, PAD, 118f, text(36f, DOWN))
        val tp = text(62f, INK)
        while (tp.measureText(s.title) > W - PAD * 2 && tp.textSize > 40f) tp.textSize -= 2f
        c.drawText(s.title, PAD, 198f, tp)
        c.drawText("${s.source} · ${s.dateLabel} 기준 · ${s.cycle}", PAD, 252f, text(32f, GRAY, bold = false))
        return 290f
    }

    private fun footer(c: Canvas, s: StatSeries, handle: String) {
        c.drawText("자료: ${s.source} 주택가격동향", PAD, H - 64f, text(30f, GRAY, bold = false))
        if (handle.isNotBlank()) { val hp = text(32f, GRAY); c.drawText(handle, W - PAD - hp.measureText(handle), H - 64f, hp) }
    }

    /** 전국·서울·수도권 큰 숫자 세 칸. 반환: 다음 y */
    private fun bigNumbers(c: Canvas, s: StatSeries, top: Float): Float {
        val items = listOf("전국", "서울", "수도권").mapNotNull { s.region(it) }.filter { it.latest != null }
        if (items.isEmpty()) return top
        val gap = 20f
        val cw = (W - PAD * 2 - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, r ->
            val x = PAD + i * (cw + gap)
            c.drawRoundRect(RectF(x, top, x + cw, top + 170f), 28f, 28f, fill(0xFFFFFFFF.toInt()))
            c.drawText(r.name, x + 28f, top + 56f, text(34f, GRAY))
            val vp = text(64f, colorOf(s, r.latest!!))
            val v = s.fmt(r.latest)
            while (vp.measureText(v) > cw - 48f && vp.textSize > 36f) vp.textSize -= 2f
            c.drawText(v, x + 28f, top + 132f, vp)
        }
        return top + 170f + 36f
    }

    /** 시도별 순위 막대. 시도 자료가 5곳 미만이면 null */
    fun rank(s: StatSeries, sido: List<String>, handle: String = ""): Bitmap? {
        val rows = s.regions.filter { it.name in sido && it.latest != null }.sortedByDescending { it.latest!! }
        if (rows.size < 5) return null
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        var y = bigNumbers(c, s, header(c, s, "지역별 한눈에"))

        val bottom = H - 120f
        val rowH = (bottom - y) / rows.size
        val labelW = 110f
        val valueW = 170f
        val chartL = PAD + labelW
        val chartR = W - PAD - valueW
        val values = rows.map { it.latest!! }
        // 변동률은 0을 기준으로 좌우, 지수는 0부터 (기준선 표시)
        val lo = if (s.kind == "change") minOf(0.0, values.min()) else 0.0
        val hi = max(if (s.kind == "change") maxOf(0.0, values.max()) else values.max(), (s.base ?: 0.0) * 1.05)
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(v: Double) = (chartL + (v - lo) / span * (chartR - chartL)).toFloat()
        val zeroX = x(if (s.kind == "change") 0.0 else lo)
        val lp = text((rowH * 0.48f).coerceIn(24f, 36f), INK)
        val vp = text((rowH * 0.48f).coerceIn(24f, 36f), INK)
        rows.forEach { r ->
            val v = r.latest!!
            val cy = y + rowH / 2
            c.drawText(r.name, PAD, cy + lp.textSize * 0.36f, lp)
            val bh = rowH * 0.56f
            val x1 = x(v)
            val rect = RectF(minOf(zeroX, x1), cy - bh / 2, maxOf(zeroX, x1).coerceAtLeast(minOf(zeroX, x1) + 4f), cy + bh / 2)
            c.drawRoundRect(rect, 8f, 8f, fill(colorOf(s, v)))
            vp.color = colorOf(s, v)
            c.drawText(s.fmt(v), chartR + 18f, cy + vp.textSize * 0.36f, vp)
            y += rowH
        }
        // 기준선 (0 또는 100)
        val baseX = if (s.kind == "change") zeroX else s.base?.let { x(it) }
        if (baseX != null) {
            c.drawRect(baseX - 1.5f, y - rowH * rows.size, baseX + 1.5f, y, fill(0x66111827))
            if (s.base != null) c.drawText("기준 ${s.fmt(s.base, false)}", baseX + 8f, y - rowH * rows.size - 6f, text(24f, GRAY, bold = false))
        }
        footer(c, s, handle)
        return bmp
    }

    /** 전국·서울·수도권 추이. 값이 3개 미만이면 null */
    fun trend(s: StatSeries, handle: String = ""): Bitmap? {
        val lines = listOf("전국", "서울", "수도권").mapNotNull { s.region(it) }.filter { r -> r.values.count { it != null } >= 3 }
        if (lines.isEmpty() || s.dates.size < 3) return null
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        var y = bigNumbers(c, s, header(c, s, "최근 ${s.dates.size}회 추이"))

        // 범례
        var lx = PAD
        lines.forEachIndexed { i, r ->
            c.drawCircle(lx + 12f, y + 16f, 12f, fill(TREND_COLORS[i]))
            val p = text(32f, INK); c.drawText(r.name, lx + 34f, y + 28f, p)
            lx += 34f + p.measureText(r.name) + 40f
        }
        y += 70f

        val left = PAD + 96f; val right = W - PAD - 20f
        val top = y + 10f; val bottom = H - 210f
        val all = lines.flatMap { it.values.filterNotNull() } + listOfNotNull(if (s.kind == "change") 0.0 else null)
        var lo = all.min(); var hi = all.max()
        val padV = ((hi - lo).takeIf { it > 0 } ?: abs(hi).coerceAtLeast(1.0)) * 0.12
        lo -= padV; hi += padV
        fun py(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun px(i: Int) = left + (right - left) * i / (s.dates.size - 1)

        // 눈금 4줄
        val gp = text(26f, GRAY, bold = false)
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            c.drawRect(left, py(v) - 1f, right, py(v) + 1f, fill(LINE))
            c.drawText(s.fmt(v, false), PAD, py(v) + 9f, gp)
        }
        if (s.kind == "change" && lo < 0 && hi > 0) c.drawRect(left, py(0.0) - 1.5f, right, py(0.0) + 1.5f, fill(0x66111827))

        lines.forEachIndexed { i, r ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TREND_COLORS[i]; style = Paint.Style.STROKE; strokeWidth = 7f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
            val path = Path()
            var started = false
            r.values.forEachIndexed { k, v ->
                if (v == null) return@forEachIndexed
                if (!started) { path.moveTo(px(k), py(v)); started = true } else path.lineTo(px(k), py(v))
            }
            c.drawPath(path, paint)
            r.latest?.let { c.drawCircle(px(r.values.size - 1), py(it), 11f, fill(TREND_COLORS[i])) }
        }
        // 가로축: 처음·가운데·끝 날짜
        val dp = text(28f, GRAY, bold = false)
        fun short(d: String) = d.split('-').let { p -> if (p.size == 3) "${p[1].toInt()}/${p[2].toInt()}" else if (p.size == 2) "${p[0].takeLast(2)}.${p[1]}" else d }
        listOf(0, s.dates.size / 2, s.dates.size - 1).distinct().forEach { k ->
            val label = short(s.dates[k])
            c.drawText(label, (px(k) - dp.measureText(label) / 2).coerceIn(PAD, W - PAD - dp.measureText(label)), bottom + 50f, dp)
        }
        if (s.desc.isNotBlank()) {
            val np = text(28f, GRAY, bold = false)
            c.drawText(CardMaker.wrapWords(s.desc, W - PAD * 2) { np.measureText(it) }.lines().first(), PAD, bottom + 104f, np)
        }
        footer(c, s, handle)
        return bmp
    }

    fun render(s: StatSeries, sido: List<String>, handle: String = ""): List<Bitmap> = listOfNotNull(rank(s, sido, handle), trend(s, handle))
}
