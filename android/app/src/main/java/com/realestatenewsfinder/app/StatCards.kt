package com.realestatenewsfinder.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max

/**
 * 직접 만든 이미지에 넣는 워터마크 (남색 알약: 집 아이콘 | 문구 / 계정 이름).
 * 기사·커뮤니티 캡처처럼 남의 화면을 담은 이미지에는 넣지 않음.
 */
object Watermark {
    private const val NAVY = 0xFF0B2A6F.toInt()
    private const val YELLOW = 0xFFFFD84D.toInt()

    data class Mark(val tagline: String, val handle: String) {
        val isEmpty get() = tagline.isBlank() && handle.isBlank()
    }

    fun of(ctx: Context) = Prefs(ctx).let { Mark(it.cardTagline, it.cardHandle) }

    private fun paint(size: Float, color: Int, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    /** rect 안에 알약을 그림. 문구가 둘 다 비어 있으면 빈 알약도 그리지 않음 (force면 배경은 그림) */
    fun draw(c: Canvas, mark: Mark, rect: RectF, force: Boolean = false) {
        if (mark.isEmpty && !force) return
        val r = rect.height() / 2
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY })
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9CC2FF.toInt(); style = Paint.Style.STROKE; strokeWidth = rect.height() * 0.022f })
        if (mark.isEmpty) return
        // 집 아이콘
        val ih = rect.height() * 0.50f
        val cx = rect.left + rect.height() * 0.62f
        val cy = rect.centerY()
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val roof = Path().apply { moveTo(cx - ih * 0.62f, cy - ih * 0.02f); lineTo(cx, cy - ih * 0.56f); lineTo(cx + ih * 0.62f, cy - ih * 0.02f); close() }
        c.drawPath(roof, white)
        c.drawRect(cx - ih * 0.42f, cy - ih * 0.08f, cx + ih * 0.42f, cy + ih * 0.5f, white)
        c.drawRect(cx - ih * 0.12f, cy + ih * 0.16f, cx + ih * 0.12f, cy + ih * 0.5f, Paint().apply { color = NAVY })
        val divX = rect.left + rect.height() * 1.12f
        c.drawRect(divX, rect.top + rect.height() * 0.22f, divX + 2f, rect.bottom - rect.height() * 0.22f, Paint().apply { color = 0x99FFFFFF.toInt() })
        // 글자: 두 줄(문구 / 계정) 또는 한 줄
        val tx = divX + rect.height() * 0.2f
        val maxW = rect.right - tx - rect.height() * 0.35f
        fun fit(text: String, p: Paint): Paint { while (p.measureText(text) > maxW && p.textSize > 12f) p.textSize -= 1f; return p }
        if (mark.tagline.isNotBlank() && mark.handle.isNotBlank()) {
            c.drawText(mark.tagline, tx, cy - rect.height() * 0.07f, fit(mark.tagline, paint(rect.height() * 0.22f, 0xFFFFFFFF.toInt())))
            c.drawText(mark.handle, tx, cy + rect.height() * 0.30f, fit(mark.handle, paint(rect.height() * 0.32f, YELLOW)))
        } else {
            val one = mark.handle.ifBlank { mark.tagline }
            val p = fit(one, paint(rect.height() * 0.32f, if (mark.handle.isNotBlank()) YELLOW else 0xFFFFFFFF.toInt()))
            c.drawText(one, tx, cy + p.textSize * 0.36f, p)
        }
    }

    /** 카드 오른쪽 아래에 알약 하나 (뉴스 카드 등). width 기준으로 크기를 잡음 */
    fun corner(c: Canvas, mark: Mark, canvasW: Int, canvasH: Int, margin: Float = 56f) {
        if (mark.isEmpty) return
        val h = canvasW * 0.088f
        val w = canvasW * (if (mark.tagline.isNotBlank()) 0.40f else 0.30f)
        draw(c, mark, RectF(canvasW - margin - w, canvasH - margin - h, canvasW - margin, canvasH - margin))
    }
}

/**
 * 통계용 카드 이미지 (1080×1620, 2:3): ① 지역별 순위 막대 ② 전국·서울·수도권 추이 선 그래프.
 * 구성: 남색 머리(아파트 그림, 흰·노랑 제목) → 큰 숫자 세 칸 → 그래프 판 → 설명·자료 → 바닥 스카이라인과 워터마크.
 * 오른 값은 빨강, 내린 값은 파랑 (국내 시세 표기 관례)
 */
object StatCards {
    const val W = 1080
    const val H = 1620
    private const val F = W / 1024f // 디자인 시안(1024×1536) 좌표 → 카드 좌표
    private const val PAD = 36f * F
    private const val UP = 0xFFE5262B.toInt()
    private const val DOWN = 0xFF2563EB.toInt()
    private const val NAVY = 0xFF0B2A6F.toInt()
    private const val INK = 0xFF14213D.toInt()
    private const val GRAY = 0xFF5B6B8C.toInt()
    private const val LINE = 0xFFDDE5F2.toInt()
    private const val YELLOW = 0xFFFFD84D.toInt()
    private val TREND_COLORS = intArrayOf(0xFF14213D.toInt(), 0xFFF03A3A.toInt(), 0xFF12A150.toInt())

    private fun text(size: Float, color: Int, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun fit(t: String, p: Paint, maxW: Float, min: Float = 30f): Paint { while (p.measureText(t) > maxW && p.textSize > min) p.textSize -= 2f; return p }

    private fun colorOf(s: StatSeries, v: Double): Int = when {
        s.kind == "change" -> if (v > 0) UP else if (v < 0) DOWN else GRAY
        s.base != null -> if (v >= s.base) UP else DOWN
        else -> DOWN
    }

    /** 제목을 두 줄로: 가운데에 가장 가까운 띄어쓰기에서 나눔. 한 낱말이면 둘째 줄은 "주간 동향" */
    fun titleLines(s: StatSeries): Pair<String, String> {
        val t = s.title.replace(Regex("\\s*\\(.*\\)$"), "") // "PIR (소득 대비 집값)" → "PIR"
        val spaces = t.indices.filter { t[it] == ' ' }
        if (spaces.isEmpty()) return t to (Regex("\\((.*)\\)").find(s.title)?.groupValues?.get(1) ?: "${s.cycle} 동향")
        val cut = spaces.minBy { abs(it - t.length / 2) }
        return t.substring(0, cut) to t.substring(cut + 1)
    }

    private fun asset(ctx: Context, id: Int): Bitmap? = runCatching { BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false }) }.getOrNull()

    /** 배경·머리·큰 숫자·바닥. 반환: 그래프 판을 놓을 영역 */
    private fun frame(ctx: Context, c: Canvas, s: StatSeries, pill: String, mark: Watermark.Mark): RectF {
        // 바탕
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0xFFEAF2FF.toInt(), 0xFFF6F9FF.toInt(), Shader.TileMode.CLAMP) })
        // 머리: 남색 그라데이션 + 아파트 그림
        val headH = 372f * F
        c.drawRect(0f, 0f, W.toFloat(), headH, Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), headH, 0xFF061F5C.toInt(), 0xFF1E6BE0.toInt(), Shader.TileMode.CLAMP) })
        asset(ctx, R.drawable.stat_header)?.let { b -> c.drawBitmap(b, null, RectF(W - b.width * F, 0f, W.toFloat(), b.height * F), Paint(Paint.FILTER_BITMAP_FLAG)) }
        // 머리 아래 물결 (밝은 바탕이 왼쪽에서 살짝 올라옴)
        val wave = Path().apply { moveTo(0f, headH - 70f); quadTo(W * 0.30f, headH + 26f, W * 0.62f, headH - 14f); quadTo(W * 0.85f, headH - 34f, W.toFloat(), headH - 6f); lineTo(W.toFloat(), headH + 2f); lineTo(0f, headH + 2f); close() }
        c.drawPath(wave, fill(0xFFEAF2FF.toInt()))
        // 집 아이콘
        val hx = 112f * F; val hy = 112f * F; val hs = 62f * F
        val white = fill(0xFFFFFFFF.toInt())
        c.drawPath(Path().apply { moveTo(hx - hs, hy); lineTo(hx, hy - hs * 0.78f); lineTo(hx + hs, hy); close() }, white)
        c.drawRect(hx - hs * 0.66f, hy - 4f, hx + hs * 0.66f, hy + hs * 0.62f, white)
        val win = fill(0xFF1E5FD8.toInt()); val ws = hs * 0.16f
        for (dx in listOf(-1, 1)) for (dy in listOf(0, 1)) c.drawRect(hx + dx * ws * 1.15f - ws + (if (dx > 0) 0f else ws * 0.0f), hy + 8f + dy * ws * 2.3f, hx + dx * ws * 1.15f + ws * 0.0f + (if (dx > 0) ws else 0f), hy + 8f + dy * ws * 2.3f + ws * 1.9f, win)
        // 알약 · 제목(흰/노랑) · 기준
        val tx = 214f * F
        val textMax = (690f - 214f) * F
        val pp = text(34f * F, 0xFFFFFFFF.toInt())
        val pw = pp.measureText(pill) + 52f * F
        val pr = RectF(tx, 48f * F, tx + pw, 48f * F + 58f * F)
        c.drawRoundRect(pr, 29f * F, 29f * F, fill(0x553B82F6))
        c.drawRoundRect(pr, 29f * F, 29f * F, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9CC2FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
        c.drawText(pill, pr.left + 26f * F, pr.bottom - 17f * F, pp)
        val (l1, l2) = titleLines(s)
        c.drawText(l1, tx, 182f * F, fit(l1, text(66f * F, 0xFFFFFFFF.toInt()), textMax))
        c.drawText(l2, tx - 30f * F, 270f * F, fit(l2, text(80f * F, YELLOW), textMax + 30f * F))
        c.drawText("${s.source} · ${s.dateLabel} 기준 · ${s.cycle}", tx - 30f * F, 322f * F, fit("${s.source} · ${s.dateLabel} 기준 · ${s.cycle}", text(32f * F, 0xFFFFFFFF.toInt(), bold = false), textMax + 30f * F, 20f))

        // 큰 숫자 세 칸
        val items = listOf("전국", "서울", "수도권").mapNotNull { s.region(it) }.filter { it.latest != null }
        val tilesTop = 384f * F; val tilesH = 146f * F
        if (items.isNotEmpty()) {
            val gap = 16f * F
            val cw = (W - PAD * 2 - gap * (items.size - 1)) / items.size
            items.forEachIndexed { i, r ->
                val x = PAD + i * (cw + gap)
                val rect = RectF(x, tilesTop, x + cw, tilesTop + tilesH)
                c.drawRoundRect(RectF(rect.left, rect.top + 6f, rect.right, rect.bottom + 6f), 26f * F, 26f * F, fill(0x1A1E5FD8))
                c.drawRoundRect(rect, 26f * F, 26f * F, white)
                c.drawCircle(x + 46f * F, tilesTop + 42f * F, 11f * F, fill(NAVY))
                c.drawText(r.name, x + 70f * F, tilesTop + 55f * F, text(36f * F, INK))
                val v = s.fmt(r.latest)
                val vp = fit(v, text(66f * F, colorOf(s, r.latest!!)), cw - 56f * F, 34f)
                c.drawText(v, x + (cw - vp.measureText(v)) / 2, tilesTop + 122f * F, vp)
            }
        }

        // 바닥: 스카이라인 그림 + 워터마크
        asset(ctx, R.drawable.stat_footer)?.let { b -> c.drawBitmap(b, null, RectF(0f, H - b.height * F, W.toFloat(), H.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)) }
        // 시안의 워터마크 자리(그림 위)를 그대로 덮어 그림. 문구가 비어 있어도 자리는 남색으로 덮음
        Watermark.draw(c, mark, RectF(560f * F, H - 170f * F, 1008f * F, H - 26f * F), force = true)

        return RectF(PAD, tilesTop + tilesH + 32f * F, W - PAD, 1128f * F)
    }

    /** 흰 판 + 남색 머리띠(제목). 반환: 판 안쪽 그래프 영역 */
    private fun panel(c: Canvas, area: RectF, title: String, legend: List<Pair<String, Int>> = emptyList()): RectF {
        val r = 26f * F
        c.drawRoundRect(RectF(area.left, area.top + 6f, area.right, area.bottom + 6f), r, r, fill(0x1A1E5FD8))
        c.drawRoundRect(area, r, r, fill(0xFFFFFFFF.toInt()))
        val stripH = 78f * F
        c.save()
        c.clipPath(Path().apply { addRoundRect(area, r, r, Path.Direction.CW) })
        c.drawRect(area.left, area.top, area.right, area.top + stripH, Paint().apply {
            shader = LinearGradient(area.left, 0f, area.right, 0f, intArrayOf(NAVY, 0xFF2A62C9.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()), floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP)
        })
        c.restore()
        // 막대 아이콘 + 제목
        val bx = area.left + 34f * F; val by = area.top + stripH - 20f * F
        listOf(0.4f, 0.65f, 0.9f).forEachIndexed { i, hgt -> c.drawRect(bx + i * 15f * F, by - 36f * F * hgt, bx + i * 15f * F + 10f * F, by, fill(0xFFFFFFFF.toInt())) }
        c.drawText(title, bx + 62f * F, area.top + stripH - 24f * F, text(38f * F, 0xFFFFFFFF.toInt()))
        // 범례 (오른쪽, 흰 바탕 위)
        var lx = area.right - 30f * F
        legend.reversed().forEach { (name, color) ->
            val p = text(28f * F, INK)
            lx -= p.measureText(name)
            c.drawText(name, lx, area.top + stripH - 26f * F, p)
            lx -= 20f * F
            c.drawCircle(lx, area.top + stripH - 36f * F, 11f * F, fill(color))
            lx -= 30f * F
        }
        return RectF(area.left + 28f * F, area.top + stripH + 22f * F, area.right - 28f * F, area.bottom - 24f * F)
    }

    private fun captions(c: Canvas, s: StatSeries, top: Float, withDesc: Boolean = true) {
        val p = text(29f * F, GRAY, bold = false)
        var y = top
        if (withDesc && s.desc.isNotBlank()) { c.drawText(CardMaker.wrapWords(s.desc, W - PAD * 2 - 20f) { p.measureText(it) }.lines().first(), PAD + 18f * F, y, p); y += 44f * F }
        c.drawText("자료 : ${s.note}", PAD + 18f * F, y, p)
    }

    /** 시도별 순위 막대. 시도 자료가 5곳 미만이면 null */
    fun rank(ctx: Context, s: StatSeries, sido: List<String>, mark: Watermark.Mark): Bitmap? {
        val rows = s.regions.filter { it.name in sido && it.latest != null }.sortedByDescending { it.latest!! }
        if (rows.size < 5) return null
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = frame(ctx, c, s, "지역별 한눈에", mark)
        // 17개 시도가 다 들어가도록 판을 아래로 조금 더 키움 (설명 줄은 추이 카드에만)
        val area = RectF(base.left, base.top, base.right, base.bottom + 40f * F)
        val inner = panel(c, area, "지역별 ${s.short}")

        val rowH = inner.height() / rows.size
        val labelW = 92f * F; val valueW = 150f * F
        val chartL = inner.left + labelW; val chartR = inner.right - valueW
        val values = rows.map { it.latest!! }
        // 변동률은 0을 기준으로 좌우, 지수는 0부터 (기준선 표시)
        val lo = if (s.kind == "change") minOf(0.0, values.min()) else 0.0
        val hi = max(if (s.kind == "change") maxOf(0.0, values.max()) else values.max(), s.base ?: 0.0)
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun x(v: Double) = (chartL + (v - lo) / span * (chartR - chartL)).toFloat()
        val zeroX = x(if (s.kind == "change") 0.0 else lo)
        val size = (rowH * 0.74f).coerceIn(20f, 36f)
        var y = inner.top
        rows.forEach { r ->
            val v = r.latest!!
            val cy = y + rowH / 2
            c.drawText(r.name, inner.left, cy + size * 0.36f, text(size, INK))
            val bh = rowH * 0.62f
            val x1 = x(v)
            val left = minOf(zeroX, x1)
            c.drawRoundRect(RectF(left, cy - bh / 2, maxOf(zeroX, x1).coerceAtLeast(left + 4f), cy + bh / 2), 6f, 6f, fill(colorOf(s, v)))
            c.drawText(s.fmt(v), chartR + 16f * F, cy + size * 0.36f, text(size, colorOf(s, v)))
            y += rowH
        }
        val baseX = if (s.kind == "change") zeroX else s.base?.let { x(it) }
        if (baseX != null) c.drawRect(baseX - 1.5f, inner.top, baseX + 1.5f, inner.bottom, fill(0x6614213D))
        captions(c, s, area.bottom + 48f * F, withDesc = false)
        return bmp
    }

    /** 전국·서울·수도권 추이. 값이 3개 미만이면 null */
    fun trend(ctx: Context, s: StatSeries, mark: Watermark.Mark): Bitmap? {
        val lines = listOf("전국", "서울", "수도권").mapNotNull { s.region(it) }.filter { r -> r.values.count { it != null } >= 3 }
        if (lines.isEmpty() || s.dates.size < 3) return null
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val area = frame(ctx, c, s, "최근 ${s.dates.size}회 추이", mark)
        val inner = panel(c, area, "${s.short} 추이", lines.mapIndexed { i, r -> r.name to TREND_COLORS[i] })

        val left = inner.left + 104f * F; val right = inner.right - 14f * F
        val top = inner.top + 10f * F; val bottom = inner.bottom - 58f * F
        val all = lines.flatMap { it.values.filterNotNull() } + listOfNotNull(if (s.kind == "change") 0.0 else null)
        var lo = all.min(); var hi = all.max()
        val padV = ((hi - lo).takeIf { it > 0 } ?: abs(hi).coerceAtLeast(1.0)) * 0.12
        lo -= padV; hi += padV
        fun py(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun px(i: Int) = left + (right - left) * i / (s.dates.size - 1)

        val gp = text(30f * F, INK, bold = false)
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            c.drawRect(left, py(v) - 1f, right, py(v) + 1f, fill(LINE))
            c.drawText(s.fmt(v, false), inner.left, py(v) + 10f * F, gp)
        }
        if (s.kind == "change" && lo < 0 && hi > 0) c.drawRect(left, py(0.0) - 1.5f, right, py(0.0) + 1.5f, fill(0x6614213D))
        c.drawRect(left - 1f, top, left + 1f, bottom, fill(LINE))

        lines.forEachIndexed { i, r ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TREND_COLORS[i]; style = Paint.Style.STROKE; strokeWidth = 6f * F; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
            val path = Path()
            var started = false
            r.values.forEachIndexed { k, v ->
                if (v == null) return@forEachIndexed
                if (!started) { path.moveTo(px(k), py(v)); started = true } else path.lineTo(px(k), py(v))
            }
            c.drawPath(path, paint)
            r.latest?.let { c.drawCircle(px(r.values.size - 1), py(it), 12f * F, fill(TREND_COLORS[i])) }
        }
        val dp = text(30f * F, INK, bold = false)
        fun short(d: String) = d.split('-').let { p -> if (p.size == 3) "${p[1].toInt()}/${p[2].toInt()}" else if (p.size == 2) "${p[0].takeLast(2)}.${p[1]}" else d }
        listOf(0, s.dates.size / 2, s.dates.size - 1).distinct().forEach { k ->
            val label = short(s.dates[k])
            c.drawRect(px(k) - 1f, bottom, px(k) + 1f, bottom + 10f * F, fill(GRAY))
            c.drawText(label, (px(k) - dp.measureText(label) / 2).coerceIn(inner.left, inner.right - dp.measureText(label)), bottom + 48f * F, dp)
        }
        captions(c, s, area.bottom + 52f * F)
        return bmp
    }

    fun render(ctx: Context, s: StatSeries, sido: List<String>, mark: Watermark.Mark = Watermark.of(ctx)): List<Bitmap> =
        listOfNotNull(rank(ctx, s, sido, mark), trend(ctx, s, mark))
}
