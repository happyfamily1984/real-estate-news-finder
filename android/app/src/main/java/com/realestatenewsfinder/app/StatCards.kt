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

    /** style 0: 남색 알약 + 집 그림, 1: 중개의 신(짙은 남색 + 금색 테두리 + 로고). logo는 style 1에서 왼쪽에 넣을 동그란 로고 */
    data class Mark(val tagline: String, val handle: String, val style: Int = 0, val logo: android.graphics.Bitmap? = null) {
        val isEmpty get() = tagline.isBlank() && handle.isBlank()
    }

    /** 고를 수 있는 워터마크 계정 이름 (설정 화면) */
    val ACCOUNTS = listOf("부알스", "중개의 신")
    private var logo: android.graphics.Bitmap? = null

    /** 설정에서 고른 계정의 워터마크 */
    fun of(ctx: Context): Mark = Prefs(ctx).let { p ->
        if (p.markAccount == 1) {
            if (logo == null) logo = runCatching { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.mark_jungae) }.getOrNull()
            Mark(p.mark2Tagline, p.mark2Handle, 1, logo)
        } else Mark(p.cardTagline, p.cardHandle)
    }

    private const val GOLD = 0xFFF2C24B.toInt()

    /** 중개의 신: 짙은 남색 알약에 금색 테두리, 왼쪽 동그란 로고, 문구(마지막 낱말은 금색) + 계정 이름 */
    private fun drawGold(c: Canvas, mark: Mark, rect: RectF) {
        val r = rect.height() / 2
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = android.graphics.LinearGradient(rect.left, rect.top, rect.left, rect.bottom, 0xFF16234F.toInt(), 0xFF070D24.toInt(), android.graphics.Shader.TileMode.CLAMP) })
        c.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD; style = Paint.Style.STROKE; strokeWidth = rect.height() * 0.035f })
        val d = rect.height() * 0.84f
        val lx = rect.left + rect.height() * 0.08f
        val box = RectF(lx, rect.centerY() - d / 2, lx + d, rect.centerY() + d / 2)
        mark.logo?.let { c.drawBitmap(it, null, box, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        val tx = (if (mark.logo != null) box.right else rect.left + rect.height() * 0.3f) + rect.height() * 0.14f
        val maxW = rect.right - tx - rect.height() * 0.3f
        val cy = rect.centerY()
        val two = mark.tagline.isNotBlank() && mark.handle.isNotBlank()
        val main = mark.tagline.ifBlank { mark.handle }
        val p = paint(rect.height() * (if (two) 0.34f else 0.44f), 0xFFFFFFFF.toInt())
        while (p.measureText(main) > maxW && p.textSize > 12f) p.textSize -= 1f
        val base = if (two) cy - rect.height() * 0.02f else cy + p.textSize * 0.36f
        // 마지막 낱말만 금색 ('중개의' 흰색 + '신' 금색). 계정 이름만 있으면 전체 금색
        val cut = if (mark.tagline.isBlank()) 0 else main.lastIndexOf(' ') + 1
        val head = main.substring(0, cut)
        c.drawText(head, tx, base, p)
        c.drawText(main.substring(cut), tx + p.measureText(head), base, Paint(p).apply { color = GOLD })
        if (two) {
            val hp = paint(rect.height() * 0.24f, GOLD, bold = false)
            while (hp.measureText(mark.handle) > maxW && hp.textSize > 12f) hp.textSize -= 1f
            c.drawText(mark.handle, tx, cy + rect.height() * 0.32f, hp)
        }
    }

    private fun paint(size: Float, color: Int, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    /** rect 안에 알약을 그림. 문구가 둘 다 비어 있으면 빈 알약도 그리지 않음 (force면 배경은 그림) */
    fun draw(c: Canvas, mark: Mark, rect: RectF, force: Boolean = false) {
        if (mark.isEmpty && !force) return
        if (mark.style == 1 && !mark.isEmpty) { drawGold(c, mark, rect); return }
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
    internal const val F = W / 1024f // 디자인 시안(1024×1536) 좌표 → 카드 좌표
    internal const val PAD = 36f * F
    internal const val UP = 0xFFE5262B.toInt()
    internal const val DOWN = 0xFF2563EB.toInt()
    internal const val NAVY = 0xFF0B2A6F.toInt()
    internal const val INK = 0xFF14213D.toInt()
    internal const val GRAY = 0xFF5B6B8C.toInt()
    internal const val LINE = 0xFFDDE5F2.toInt()
    internal const val YELLOW = 0xFFFFD84D.toInt()
    internal val TREND_COLORS = intArrayOf(0xFF14213D.toInt(), 0xFFF03A3A.toInt(), 0xFF12A150.toInt())
    private val DEFAULT_NAMES = listOf("전국", "서울", "수도권")

    internal fun text(size: Float, color: Int, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
    internal fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    internal fun fit(t: String, p: Paint, maxW: Float, min: Float = 30f): Paint { while (p.measureText(t) > maxW && p.textSize > min) p.textSize -= 2f; return p }

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

    internal fun asset(ctx: Context, id: Int): Bitmap? = runCatching { BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false }) }.getOrNull()

    data class Tile(val label: String, val value: String, val color: Int)

    /** 통계 지표용 틀: 제목·기준일·지역 세 곳의 최신 값 */
    private fun frame(ctx: Context, c: Canvas, s: StatSeries, pill: String, mark: Watermark.Mark, names: List<String> = DEFAULT_NAMES, label: (String) -> String = { it }, theme: Theme = Theme.BLUE): RectF {
        val (l1, l2) = titleLines(s)
        val tiles = names.mapNotNull { s.region(it) }.filter { it.latest != null }.take(3).map { Tile(label(it.name), s.fmt(it.latest), colorOf(s, it.latest!!)) }
        return frameCustom(ctx, c, pill, l1, l2, "${s.source} · ${s.dateLabel} 기준 · ${s.cycle}", tiles, mark, theme)
    }

    /**
     * 카드 디자인. 자료 종류마다 색·머리 모양·숫자 칸·바닥을 달리해서 한눈에 구분되게 함.
     *  style 0: 사진 머리(집 아이콘 + 아파트 사진) — 전국 지표, 지역별, 실거래
     *  style 1: 가운데 정렬 머리(사진 없이 큰 제목과 무늬) — 그때와 지금, 최고 실거래 단지
     *  style 2: 왼쪽 제목 + 오른쪽 큰 동그라미 표지 — 살 수 있는 아파트, 분양가 vs 실거래가
     */
    class Theme(
        val headA: Int, val headB: Int,
        /** 바탕(위·아래) */
        val bgA: Int, val bgB: Int,
        /** 둘째 줄 제목(강조) 색 */
        val accent: Int,
        /** 판 머리띠·점·순위 동그라미 색 */
        val dark: Int, val mid: Int,
        val style: Int = 0,
        /** 사진 색조 돌리기(도). null이면 그대로, gray면 흑백 */
        val hue: Float? = null, val gray: Boolean = false,
        /** style 2의 동그라미 안 글자 */
        val badge: String = "",
        /** 숫자 칸을 진한 색으로 채움 */
        val filledTiles: Boolean = false,
    ) {
        companion object {
            /** 전국 지표: 파랑 (처음 디자인) */
            val BLUE = Theme(0xFF061F5C.toInt(), 0xFF1E6BE0.toInt(), 0xFFEAF2FF.toInt(), 0xFFF6F9FF.toInt(), 0xFFFFD84D.toInt(), 0xFF0B2A6F.toInt(), 0xFF2A62C9.toInt())
            /** 지역별 지표: 청록 */
            val TEAL = Theme(0xFF064E4B.toInt(), 0xFF14B8A6.toInt(), 0xFFE6FAF6.toInt(), 0xFFF4FDFB.toInt(), 0xFFFDE68A.toInt(), 0xFF0F5F59.toInt(), 0xFF1BA395.toInt(), hue = -48f)
            /** 실거래 요약: 남보라 */
            val INDIGO = Theme(0xFF1E1B6B.toInt(), 0xFF6D5BF5.toInt(), 0xFFEFEDFF.toInt(), 0xFFF8F7FF.toInt(), 0xFFFFE28A.toInt(), 0xFF2B2380.toInt(), 0xFF5B4BD6.toInt(), hue = 28f)
            /** 새 실거래: 빨강 (속보 느낌) */
            val ALERT = Theme(0xFF7A0C1E.toInt(), 0xFFF0445C.toInt(), 0xFFFFEEF0.toInt(), 0xFFFFF8F9.toInt(), 0xFFFFE08A.toInt(), 0xFF8E1226.toInt(), 0xFFD9364F.toInt(), hue = 125f)
            /** 살 수 있는 아파트: 초록 + '₩' 표지 */
            val GREEN = Theme(0xFF064E2B.toInt(), 0xFF22B866.toInt(), 0xFFE9F9EF.toInt(), 0xFFF6FDF8.toInt(), 0xFFFFF08A.toInt(), 0xFF0B5E34.toInt(), 0xFF1E9E57.toInt(), style = 2, badge = "₩")
            /** 그때와 지금: 짙은 남색 + 금색, 가운데 정렬 */
            val NIGHT = Theme(0xFF0A1024.toInt(), 0xFF26355F.toInt(), 0xFFEEF0F6.toInt(), 0xFFF8F9FC.toInt(), 0xFFF5C451.toInt(), 0xFF141C36.toInt(), 0xFF3A4A7A.toInt(), style = 1, filledTiles = true)
            /** 분양가 vs 실거래가: 주황·분홍 + 'VS' 표지 */
            val SUNSET = Theme(0xFFB5360C.toInt(), 0xFFF2587C.toInt(), 0xFFFFF1EA.toInt(), 0xFFFFF9F6.toInt(), 0xFFFFF3B0.toInt(), 0xFFA5340F.toInt(), 0xFFE5633A.toInt(), style = 2, badge = "VS")
            /** (실험실) 자금 계산: 청회색 + '계산' 표지 */
            val PLAN = Theme(0xFF12324A.toInt(), 0xFF2F7FA8.toInt(), 0xFFEAF4F9.toInt(), 0xFFF6FBFD.toInt(), 0xFFFFE38A.toInt(), 0xFF173B55.toInt(), 0xFF2C6E92.toInt(), style = 2, badge = "계산")
            /** 최고 실거래 단지: 검정 + 금색, 가운데 정렬 */
            val GOLD = Theme(0xFF111111.toInt(), 0xFF3B2F12.toInt(), 0xFFF7F3E8.toInt(), 0xFFFCFAF4.toInt(), 0xFFF2C14E.toInt(), 0xFF1B1B1B.toInt(), 0xFF6B5420.toInt(), style = 1, filledTiles = true)
        }
    }

    /** 지금 그리는 카드의 디자인 (frameCustom이 정하고 panel이 따라 씀) */
    @Volatile private var cur: Theme = Theme.BLUE

    /** 사진 색조를 디자인 색에 맞춤 */
    private fun tint(t: Theme): android.graphics.ColorFilter? {
        if (t.gray) return android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
        val deg = t.hue ?: return null
        val a = Math.toRadians(deg.toDouble()); val cs = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
        val r = 0.213f; val g = 0.715f; val bl = 0.072f
        return android.graphics.ColorMatrixColorFilter(floatArrayOf(
            r + cs * (1 - r) + sn * (-r), g + cs * (-g) + sn * (-g), bl + cs * (-bl) + sn * (1 - bl), 0f, 0f,
            r + cs * (-r) + sn * 0.143f, g + cs * (1 - g) + sn * 0.140f, bl + cs * (-bl) + sn * (-0.283f), 0f, 0f,
            r + cs * (-r) + sn * (-(1 - r)), g + cs * (-g) + sn * g, bl + cs * (1 - bl) + sn * bl, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /** 배경·머리(알약, 흰 제목, 강조 제목, 기준 줄)·큰 숫자 칸·바닥. 반환: 그래프 판을 놓을 영역 */
    internal fun frameCustom(ctx: Context, c: Canvas, pill: String, l1: String, l2: String, sub: String, tiles: List<Tile>, mark: Watermark.Mark, theme: Theme = Theme.BLUE): RectF {
        cur = theme
        val white = fill(0xFFFFFFFF.toInt())
        // 바탕
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), theme.bgA, theme.bgB, Shader.TileMode.CLAMP) })
        // 머리 바탕
        val headH = 372f * F
        c.drawRect(0f, 0f, W.toFloat(), headH, Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), headH, theme.headA, theme.headB, Shader.TileMode.CLAMP) })
        val photo = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = tint(theme) }
        val pp = text(34f * F, 0xFFFFFFFF.toInt())
        val pw = pp.measureText(pill) + 52f * F
        fun pillAt(left: Float) {
            val pr = RectF(left, 48f * F, left + pw, 48f * F + 58f * F)
            c.drawRoundRect(pr, 29f * F, 29f * F, fill(0x33FFFFFF))
            c.drawRoundRect(pr, 29f * F, 29f * F, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
            c.drawText(pill, pr.left + 26f * F, pr.bottom - 17f * F, pp)
        }
        when (theme.style) {
            1 -> {
                // 가운데 정렬: 사진 없이 큰 동그라미 무늬와 가는 줄
                c.drawCircle(W * 0.08f, headH * 0.15f, 190f * F, fill(0x12FFFFFF))
                c.drawCircle(W * 0.95f, headH * 0.80f, 240f * F, fill(0x10FFFFFF))
                c.drawCircle(W * 0.80f, headH * 0.10f, 70f * F, fill(0x14FFFFFF))
                pillAt((W - pw) / 2)
                fun center(t: String, y: Float, p: Paint) { fit(t, p, W - 140f * F, 26f); c.drawText(t, (W - p.measureText(t)) / 2, y, p) }
                center(l1, 190f * F, text(64f * F, 0xFFFFFFFF.toInt()))
                center(l2, 282f * F, text(86f * F, theme.accent))
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent; strokeWidth = 4f * F }
                val sp = fit(sub, text(30f * F, 0xFFFFFFFF.toInt(), bold = false), W - 360f * F, 18f)
                val sw = sp.measureText(sub)
                c.drawText(sub, (W - sw) / 2, 336f * F, sp)
                c.drawLine(60f * F, 326f * F, (W - sw) / 2 - 24f * F, 326f * F, line)
                c.drawLine((W + sw) / 2 + 24f * F, 326f * F, W - 60f * F, 326f * F, line)
            }
            2 -> {
                // 왼쪽 제목 + 오른쪽 큰 동그라미 표지, 비스듬한 줄무늬
                val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x10FFFFFF; strokeWidth = 46f * F }
                for (k in 0..6) c.drawLine(W * 0.38f + k * 120f * F, -20f, W * 0.18f + k * 120f * F, headH + 20f, stripe)
                val cx = W - 190f * F; val cy = 176f * F; val rad = 132f * F
                c.drawCircle(cx, cy, rad + 22f * F, fill(0x22FFFFFF))
                c.drawCircle(cx, cy, rad, white)
                val bp = fit(theme.badge, text(if (theme.badge.length <= 1) 170f * F else 118f * F, theme.headA), rad * 1.6f, 40f)
                c.drawText(theme.badge, cx - bp.measureText(theme.badge) / 2, cy - (bp.descent() + bp.ascent()) / 2, bp)
                val tx = 60f * F; val textMax = W - 60f * F - (rad * 2 + 100f * F)
                pillAt(tx)
                c.drawText(l1, tx, 186f * F, fit(l1, text(64f * F, 0xFFFFFFFF.toInt()), textMax))
                c.drawText(l2, tx, 270f * F, fit(l2, text(76f * F, theme.accent), textMax))
                c.drawText(sub, tx, 322f * F, fit(sub, text(30f * F, 0xFFFFFFFF.toInt(), bold = false), textMax, 18f))
            }
            else -> {
                asset(ctx, R.drawable.stat_header)?.let { b -> c.drawBitmap(b, null, RectF(W - b.width * F, 0f, W.toFloat(), b.height * F), photo) }
                // 집 아이콘
                val hx = 112f * F; val hy = 112f * F; val hs = 62f * F
                c.drawPath(Path().apply { moveTo(hx - hs, hy); lineTo(hx, hy - hs * 0.78f); lineTo(hx + hs, hy); close() }, white)
                c.drawRect(hx - hs * 0.66f, hy - 4f, hx + hs * 0.66f, hy + hs * 0.62f, white)
                val win = fill(theme.mid); val ws = hs * 0.16f
                for (dx in listOf(-1, 1)) for (dy in listOf(0, 1)) c.drawRect(hx + dx * ws * 1.15f - ws, hy + 8f + dy * ws * 2.3f, hx + dx * ws * 1.15f + (if (dx > 0) ws else 0f), hy + 8f + dy * ws * 2.3f + ws * 1.9f, win)
                val tx = 214f * F
                val textMax = (690f - 214f) * F
                pillAt(tx)
                c.drawText(l1, tx, 182f * F, fit(l1, text(66f * F, 0xFFFFFFFF.toInt()), textMax))
                c.drawText(l2, tx - 30f * F, 270f * F, fit(l2, text(80f * F, theme.accent), textMax + 30f * F))
                c.drawText(sub, tx - 30f * F, 322f * F, fit(sub, text(32f * F, 0xFFFFFFFF.toInt(), bold = false), textMax + 30f * F, 20f))
            }
        }
        // 머리 아래 물결 (밝은 바탕이 왼쪽에서 살짝 올라옴). 가운데 정렬 머리는 곧은 금색 줄
        if (theme.style == 1) c.drawRect(0f, headH - 6f * F, W.toFloat(), headH, fill(theme.accent))
        else c.drawPath(Path().apply { moveTo(0f, headH - 70f); quadTo(W * 0.30f, headH + 26f, W * 0.62f, headH - 14f); quadTo(W * 0.85f, headH - 34f, W.toFloat(), headH - 6f); lineTo(W.toFloat(), headH + 2f); lineTo(0f, headH + 2f); close() }, fill(theme.bgA))

        // 큰 숫자 칸 (최대 3칸)
        val tilesTop = 384f * F; val tilesH = 146f * F
        if (tiles.isNotEmpty()) {
            val gap = 16f * F
            val cw = (W - PAD * 2 - gap * (tiles.size - 1)) / tiles.size
            tiles.forEachIndexed { i, t ->
                val x = PAD + i * (cw + gap)
                val rect = RectF(x, tilesTop, x + cw, tilesTop + tilesH)
                c.drawRoundRect(RectF(rect.left, rect.top + 6f, rect.right, rect.bottom + 6f), 26f * F, 26f * F, fill(0x1A000000))
                if (theme.filledTiles) {
                    // 진한 칸: 글자는 흰색, 숫자는 강조색 (첫 칸) 또는 흰색
                    c.drawRoundRect(rect, 26f * F, 26f * F, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, theme.dark, theme.mid, Shader.TileMode.CLAMP) })
                    c.drawText(t.label, x + 34f * F, tilesTop + 55f * F, fit(t.label, text(34f * F, 0xCCFFFFFF.toInt()), cw - 60f * F, 20f))
                    val vp = fit(t.value, text(66f * F, if (i == 0) theme.accent else 0xFFFFFFFF.toInt()), cw - 56f * F, 30f)
                    c.drawText(t.value, x + (cw - vp.measureText(t.value)) / 2, tilesTop + 122f * F, vp)
                } else {
                    c.drawRoundRect(rect, 26f * F, 26f * F, white)
                    c.drawCircle(x + 46f * F, tilesTop + 42f * F, 11f * F, fill(theme.dark))
                    c.drawText(t.label, x + 70f * F, tilesTop + 55f * F, fit(t.label, text(36f * F, INK), cw - 90f * F, 20f))
                    val vp = fit(t.value, text(66f * F, t.color), cw - 56f * F, 30f)
                    c.drawText(t.value, x + (cw - vp.measureText(t.value)) / 2, tilesTop + 122f * F, vp)
                }
            }
        }

        // 바닥: 사진 머리는 스카이라인 사진, 나머지는 색 띠와 건물 실루엣
        val markRect = RectF(560f * F, H - 170f * F, 1008f * F, H - 26f * F)
        if (theme.style == 0) {
            asset(ctx, R.drawable.stat_footer)?.let { b -> c.drawBitmap(b, null, RectF(0f, H - b.height * F, W.toFloat(), H.toFloat()), photo) }
        } else {
            val bandTop = H - 104f * F // 얇은 띠. 워터마크는 띠 위로 걸쳐 놓임
            // 띠 위로 솟은 건물 실루엣
            val heights = floatArrayOf(60f, 110f, 80f, 150f, 95f, 130f, 70f, 120f, 90f, 160f, 75f, 115f, 85f, 140f)
            val bw = W / heights.size.toFloat()
            heights.forEachIndexed { k, hgt -> c.drawRect(k * bw + bw * 0.12f, bandTop - hgt * F * 0.75f, (k + 1) * bw - bw * 0.12f, bandTop, fill((theme.mid and 0x00FFFFFF) or (if (k % 2 == 0) 0x4D000000 else 0x30000000))) }
            c.drawRect(0f, bandTop, W.toFloat(), H.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), 0f, theme.headA, theme.headB, Shader.TileMode.CLAMP) })
            c.drawRect(0f, bandTop, W.toFloat(), bandTop + 5f * F, fill(theme.accent))
        }
        // 워터마크 자리. 문구가 비어 있어도 자리는 남색으로 덮음
        Watermark.draw(c, mark, markRect, force = true)

        return RectF(PAD, tilesTop + tilesH + 32f * F, W - PAD, 1128f * F)
    }

    /** 흰 판 + 디자인 색 머리띠(제목). 반환: 판 안쪽 그래프 영역 */
    internal fun panel(c: Canvas, area: RectF, title: String, legend: List<Pair<String, Int>> = emptyList()): RectF {
        val t = cur
        val r = 26f * F
        c.drawRoundRect(RectF(area.left, area.top + 6f, area.right, area.bottom + 6f), r, r, fill(0x14000000))
        c.drawRoundRect(area, r, r, fill(0xFFFFFFFF.toInt()))
        val stripH = 78f * F
        c.save()
        c.clipPath(Path().apply { addRoundRect(area, r, r, Path.Direction.CW) })
        c.drawRect(area.left, area.top, area.right, area.top + stripH, Paint().apply {
            shader = LinearGradient(area.left, 0f, area.right, 0f, intArrayOf(t.dark, t.mid, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()), floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP)
        })
        c.restore()
        // 막대 아이콘 + 제목
        val bx = area.left + 34f * F; val by = area.top + stripH - 20f * F
        listOf(0.4f, 0.65f, 0.9f).forEachIndexed { i, hgt -> c.drawRect(bx + i * 15f * F, by - 36f * F * hgt, bx + i * 15f * F + 10f * F, by, fill(if (t.style == 1) t.accent else 0xFFFFFFFF.toInt())) }
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

    /** 지금 카드의 진한 색 (순위 동그라미 등, 디자인에 맞춘 색이 필요할 때) */
    internal val themeDark: Int get() = cur.dark

    private fun captions(c: Canvas, s: StatSeries, top: Float, withDesc: Boolean = true) {
        val p = text(29f * F, GRAY, bold = false)
        var y = top
        if (withDesc && s.desc.isNotBlank()) { c.drawText(CardMaker.wrapWords(s.desc, W - PAD * 2 - 20f) { p.measureText(it) }.lines().first(), PAD + 18f * F, y, p); y += 44f * F }
        c.drawText("자료 : ${s.note}", PAD + 18f * F, y, p)
    }

    /** 시도별 순위 막대. 시도 자료가 5곳 미만이면 null */
    fun rank(
        ctx: Context, s: StatSeries, sido: List<String>, mark: Watermark.Mark,
        pill: String = "지역별 한눈에", panelTitle: String = "지역별 ${s.short}", names: List<String> = DEFAULT_NAMES,
        label: (String) -> String = { it }, highlight: String? = null, theme: Theme = Theme.BLUE,
    ): Bitmap? {
        var rows = s.regions.filter { it.name in sido && it.latest != null }.sortedByDescending { it.latest!! }
        if (rows.size < 5) return null
        // 줄이 너무 많으면 위 12곳 + 아래 12곳 (강조 지역은 꼭 넣음)
        if (rows.size > 26) rows = (rows.take(12) + rows.filter { it.name == highlight } + rows.takeLast(12)).distinct().sortedByDescending { it.latest!! }
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = frame(ctx, c, s, pill, mark, names, label, theme)
        // 17개 시도가 다 들어가도록 판을 아래로 조금 더 키움 (설명 줄은 추이 카드에만)
        val area = RectF(base.left, base.top, base.right, base.bottom + 40f * F)
        val inner = panel(c, area, panelTitle)

        val rowH = inner.height() / rows.size
        val valueW = 150f * F
        val size0 = (rowH * 0.74f).coerceIn(20f, 36f)
        val labelW = max(92f * F, rows.maxOf { text(size0, INK).measureText(label(it.name)) } + 14f * F).coerceAtMost(inner.width() * 0.34f)
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
            if (r.name == highlight) c.drawRoundRect(RectF(inner.left - 10f, y + 1f, inner.right + 10f, y + rowH - 1f), 8f, 8f, fill(0x33FFD84D))
            c.drawText(label(r.name), inner.left, cy + size * 0.36f, fit(label(r.name), text(size, INK), labelW - 8f, 14f))
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
    fun trend(ctx: Context, s: StatSeries, mark: Watermark.Mark, names: List<String> = DEFAULT_NAMES, pill: String? = null, label: (String) -> String = { it }, theme: Theme = Theme.BLUE): Bitmap? {
        val lines = names.mapNotNull { s.region(it) }.filter { r -> r.values.count { it != null } >= 3 }.take(3)
        if (lines.isEmpty() || s.dates.size < 3) return null
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val area = frame(ctx, c, s, pill ?: "최근 ${s.dates.size}회 추이", mark, names, label, theme)
        val inner = panel(c, area, "${s.short} 추이", lines.mapIndexed { i, r -> label(r.name) to TREND_COLORS[i] })

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

    /**
     * 한 지역의 카드들: 고른 지표마다 추이(지역·시도·전국) 한 장, 변동률 지표는 같은 시도 시군구 순위 한 장.
     * 그 지역 자료가 없는 지표는 가장 가까운 상위 지역(권역·시도) 값으로 그림
     */
    fun renderRegion(ctx: Context, stats: Stats, region: String, picked: List<StatSeries>, mark: Watermark.Mark = Watermark.of(ctx)): List<Bitmap> {
        val sido = stats.sidoOf(region)
        val out = mutableListOf<Bitmap>()
        val label: (String) -> String = { n -> if (n == "전국" || n == sido) n else stats.shortName(n) }
        for (s in picked) {
            val at = stats.chain(region).firstOrNull { s.region(it)?.latest != null } ?: continue
            val names = listOf(at, sido, "전국").distinct()
            trend(ctx, s, mark, names, "${if (at == sido) sido else "$sido ${stats.shortName(at)}"} · 최근 ${s.dates.size}회", label, Theme.TEAL)?.let { out += it }
            val sib = stats.siblings(region)
            if (s.kind == "change" && sib.count { s.region(it)?.latest != null } >= 5) {
                rank(ctx, s, sib, mark, "$sido 시군구 순위", "$sido ${s.short}", names, label, highlight = at, theme = Theme.TEAL)?.let { out += it }
            }
        }
        return out
    }

    fun render(ctx: Context, s: StatSeries, sido: List<String>, mark: Watermark.Mark = Watermark.of(ctx)): List<Bitmap> =
        listOfNotNull(rank(ctx, s, sido, mark), trend(ctx, s, mark))
}
