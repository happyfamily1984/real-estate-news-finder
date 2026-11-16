package com.realestatenewsfinder.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 실거래용 카드 이미지 (통계 카드와 같은 틀, 1080×1620).
 *  - 기간 요약: ① 실거래가 추이(거래마다 점, 월평균 선) ② 최근 실거래 내역 표
 *  - 새 실거래: ① 새 거래와 이전 평균 비교 ② 추이(새 거래는 빨간 N)
 */
object DealCards {
    private const val W = StatCards.W
    private const val F = StatCards.F
    private const val RED = 0xFFE5262B.toInt()
    private const val BLUE = 0xFF2563EB.toInt()
    private val AREA_COLORS = intArrayOf(0xFF1E5FD8.toInt(), 0xFF12A150.toInt(), 0xFFF08A24.toInt())
    private const val OTHER = 0xFF9AA7BD.toInt()

    data class Input(
        val aptNm: String, val place: String, val type: String, val period: DealPeriod,
        /** 기간 안의 그 유형 거래 */
        val deals: List<Deal>,
        /** 새로 신고된 거래의 키 (없으면 기간 요약) */
        val newKeys: Set<String> = emptySet(),
        val today: LocalDate = LocalDate.now(KST),
    ) {
        val valid: List<Deal> get() = deals.filter { !it.canceled }.sortedWith(compareByDescending<Deal> { it.date }.thenByDescending { it.floor })
        val label: String get() = Molit.TYPES[type] ?: type
    }

    /** 18.5억 / 9,500만 */
    fun short(v: Long): String = when {
        v >= 10000 -> String.format("%.1f", v / 10000.0).removeSuffix(".0") + "억"
        else -> "%,d만".format(v)
    }

    /** 그래프·표에 쓰는 값: 월세는 월세액, 나머지는 거래가·보증금 */
    private fun y(d: Deal) = if (d.type == "monthly") d.monthlyRent else DealText.value(d)
    private fun price(d: Deal) = if (d.type == "monthly") "${short(d.deposit)} / ${"%,d".format(d.monthlyRent)}만" else short(DealText.value(d))
    private fun avg(l: List<Long>) = if (l.isEmpty()) 0L else l.average().roundToLong()

    private fun head(ctx: Context, c: Canvas, i: Input, title2: String, tiles: List<StatCards.Tile>, mark: Watermark.Mark): RectF =
        StatCards.frameCustom(
            ctx, c, "${i.label} 실거래 · 최근 ${i.period.label}", i.aptNm, title2,
            "${i.place} · ${i.today.monthValue}/${i.today.dayOfMonth} 기준", tiles, mark,
        )

    private fun caption(c: Canvas, top: Float, extra: String = "") {
        val p = StatCards.text(29f * F, StatCards.GRAY, bold = false)
        var yy = top
        if (extra.isNotBlank()) { c.drawText(extra, StatCards.PAD + 18f * F, yy, p); yy += 44f * F }
        c.drawText("자료 : 국토교통부 실거래가 (계약일 기준, 해제 거래 제외)", StatCards.PAD + 18f * F, yy, p)
    }

    private fun summaryTiles(i: Input): List<StatCards.Tile> {
        val v = i.valid
        if (v.isEmpty()) return listOf(StatCards.Tile("거래", "0건", StatCards.INK))
        return if (i.type == "monthly") listOf(
            StatCards.Tile("거래", "${v.size}건", StatCards.INK),
            StatCards.Tile("평균 보증금", short(avg(v.map { it.deposit })), BLUE),
            StatCards.Tile("평균 월세", "%,d만".format(avg(v.map { it.monthlyRent })), RED),
        ) else listOf(
            StatCards.Tile("거래", "${v.size}건", StatCards.INK),
            StatCards.Tile("평균", short(avg(v.map(DealText::value))), BLUE),
            StatCards.Tile("최고", short(v.maxOf(DealText::value)), RED),
        )
    }

    private fun nBadge(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.drawCircle(cx, cy, r, StatCards.fill(RED))
        val p = StatCards.text(r * 1.25f, 0xFFFFFFFF.toInt())
        c.drawText("N", cx - p.measureText("N") / 2, cy + r * 0.45f, p)
    }

    /** 추이: 거래마다 점(면적별 색), 월평균 선. 거래가 2건 미만이면 null */
    fun trend(ctx: Context, i: Input, mark: Watermark.Mark, tiles: List<StatCards.Tile> = summaryTiles(i), title2: String = "실거래가 추이"): Bitmap? {
        val v = i.valid
        if (v.size < 2) return null
        val bmp = Bitmap.createBitmap(W, StatCards.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val area = head(ctx, c, i, title2, tiles, mark)
        val groups = v.groupBy(DealText::areaKey).entries.sortedByDescending { it.value.size }.take(3).map { it.key }
        fun color(d: Deal) = groups.indexOf(DealText.areaKey(d)).let { if (it >= 0) AREA_COLORS[it] else OTHER }
        val inner = StatCards.panel(c, area, if (i.type == "monthly") "월세 추이" else "${i.label}가 추이", groups.sorted().map { g -> "${g}㎡" to AREA_COLORS[groups.indexOf(g)] })

        val left = inner.left + 118f * F; val right = inner.right - 18f * F
        val top = inner.top + 16f * F; val bottom = inner.bottom - 58f * F
        val d0 = LocalDate.parse(i.period.since(i.today)).toEpochDay(); val d1 = i.today.toEpochDay()
        val ys = v.map(::y)
        var lo = ys.min().toDouble(); var hi = ys.max().toDouble()
        val padV = ((hi - lo).takeIf { it > 0 } ?: (hi * 0.1).coerceAtLeast(1.0)) * 0.14
        lo -= padV; hi += padV
        fun px(date: String) = left + (right - left) * ((LocalDate.parse(date).toEpochDay() - d0).toFloat() / (d1 - d0).coerceAtLeast(1)).coerceIn(0f, 1f)
        fun py(value: Double) = (bottom - (value - lo) / (hi - lo) * (bottom - top)).toFloat()

        val gp = StatCards.text(28f * F, StatCards.INK, bold = false)
        for (k in 0..3) {
            val value = lo + (hi - lo) * k / 3
            c.drawRect(left, py(value) - 1f, right, py(value) + 1f, StatCards.fill(StatCards.LINE))
            c.drawText(if (i.type == "monthly") "%,d만".format(value.roundToLong()) else short(value.roundToLong()), inner.left, py(value) + 10f * F, gp)
        }
        c.drawRect(left - 1f, top, left + 1f, bottom, StatCards.fill(StatCards.LINE))

        // 월평균 선 (가장 거래가 많은 면적 기준 → 평형이 섞여 생기는 들쭉날쭉을 줄임)
        val main = v.filter { DealText.areaKey(it) == groups.first() }
        val monthly = main.groupBy { it.date.substring(0, 7) }.toSortedMap().map { (ym, ds) -> "$ym-15" to ds.map(::y).average() }
        if (monthly.size >= 2) {
            val path = Path()
            monthly.forEachIndexed { k, (date, value) -> if (k == 0) path.moveTo(px(date), py(value)) else path.lineTo(px(date), py(value)) }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AREA_COLORS[0]; alpha = 150; style = Paint.Style.STROKE; strokeWidth = 5f * F; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND })
        }
        val r = (if (v.size > 150) 5f else if (v.size > 60) 7f else 10f) * F
        for (d in v.reversed()) if (d.key !in i.newKeys) c.drawCircle(px(d.date), py(y(d).toDouble()), r, StatCards.fill(color(d)).apply { alpha = 215 })
        for (d in v) if (d.key in i.newKeys) nBadge(c, px(d.date), py(y(d).toDouble()), 20f * F)

        val dp = StatCards.text(28f * F, StatCards.INK, bold = false)
        val start = LocalDate.ofEpochDay(d0); val mid = LocalDate.ofEpochDay((d0 + d1) / 2)
        listOf(start, mid, i.today).forEach { date ->
            val label = if (i.period.months <= 3) "${date.monthValue}/${date.dayOfMonth}" else "${date.year % 100}.${"%02d".format(date.monthValue)}"
            val x = px(date.toString())
            c.drawRect(x - 1f, bottom, x + 1f, bottom + 10f * F, StatCards.fill(StatCards.GRAY))
            c.drawText(label, (x - dp.measureText(label) / 2).coerceIn(inner.left, inner.right - dp.measureText(label)), bottom + 46f * F, dp)
        }
        caption(c, area.bottom + 52f * F, if (monthly.size >= 2) "선: ${groups.first()}㎡ 월평균 · 점: 거래 한 건" + (if (i.newKeys.isNotEmpty()) " · N: 새로 신고된 거래" else "") else "")
        return bmp
    }

    /** 최근 실거래 내역 표 (최대 12줄) */
    fun list(ctx: Context, i: Input, mark: Watermark.Mark): Bitmap? {
        val v = i.valid
        if (v.isEmpty()) return null
        val bmp = Bitmap.createBitmap(W, StatCards.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = head(ctx, c, i, "최근 실거래 내역", summaryTiles(i), mark)
        val area = RectF(base.left, base.top, base.right, base.bottom + 40f * F)
        val inner = StatCards.panel(c, area, "최근 ${i.label} 실거래")
        val rows = v.take(12)
        val rowH = inner.height() / (rows.size.coerceAtLeast(8) + 1)
        val size = (rowH * 0.46f).coerceIn(24f, 38f)
        val cols = floatArrayOf(inner.left + 44f * F, inner.left + inner.width() * 0.30f, inner.left + inner.width() * 0.50f)
        val hp = StatCards.text(size * 0.86f, StatCards.GRAY, bold = false)
        var yy = inner.top + rowH * 0.62f
        listOf("계약일", "전용면적", "층").forEachIndexed { k, t -> c.drawText(t, cols[k], yy, hp) }
        val ht = if (i.type == "monthly") "보증금 / 월세" else if (i.type == "trade") "거래가" else "보증금"
        c.drawText(ht, inner.right - hp.measureText(ht), yy, hp)
        c.drawRect(inner.left, inner.top + rowH - 2f, inner.right, inner.top + rowH, StatCards.fill(StatCards.LINE))
        rows.forEachIndexed { k, d ->
            yy = inner.top + rowH * (k + 1) + rowH * 0.64f
            val fresh = d.key in i.newKeys
            if (fresh) nBadge(c, inner.left + 16f * F, yy - size * 0.34f, 15f * F)
            val p = StatCards.text(size, StatCards.INK, bold = fresh)
            c.drawText(d.date.substring(2).replace('-', '.'), cols[0], yy, p)
            c.drawText("${DealFmt.area(d.area)}㎡", cols[1], yy, p)
            c.drawText("${d.floor}층", cols[2], yy, p)
            val pv = price(d)
            val pp = StatCards.text(size * 1.08f, if (fresh) RED else StatCards.NAVY)
            c.drawText(pv, inner.right - pp.measureText(pv), yy, pp)
            if (k < rows.size - 1) c.drawRect(inner.left, inner.top + rowH * (k + 2) - 1f, inner.right, inner.top + rowH * (k + 2), StatCards.fill(0xFFEEF2F9.toInt()))
        }
        caption(c, area.bottom + 48f * F)
        return bmp
    }

    /** 새 실거래 카드: 새 거래(최대 3건)마다 가격과 이전 평균 대비 */
    fun fresh(ctx: Context, i: Input, compares: List<DealText.Compare>, mark: Watermark.Mark): Bitmap? {
        val news = compares.filter { !it.deal.canceled && it.deal.type == i.type }.take(2) // 한 장에 두 건까지 (나머지는 글과 내역 표에)
        if (news.isEmpty()) return null
        val top = news.first()
        val tiles = mutableListOf(StatCards.Tile("새 거래", "${compares.count { !it.deal.canceled && it.deal.type == i.type }}건", StatCards.INK), StatCards.Tile("거래가", price(top.deal), RED))
        top.diffPct?.takeIf { i.type != "monthly" }?.let { p -> tiles += StatCards.Tile("평균 대비", String.format("%s%.1f%%", if (p > 0) "+" else "", p), if (p > 0) RED else if (p < 0) BLUE else StatCards.INK) }
        val bmp = Bitmap.createBitmap(W, StatCards.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = head(ctx, c, i, "새 실거래 발생", tiles, mark)
        val area = RectF(base.left, base.top, base.right, base.bottom + 40f * F)
        val inner = StatCards.panel(c, area, "새로 신고된 ${i.label} 실거래")
        val blockH = inner.height() / 2
        news.forEachIndexed { k, cmp ->
            val d = cmp.deal
            val y0 = inner.top + blockH * k
            if (k > 0) c.drawRect(inner.left, y0 - 1f, inner.right, y0 + 1f, StatCards.fill(StatCards.LINE))
            nBadge(c, inner.left + 26f * F, y0 + blockH * 0.22f, 22f * F)
            val l1 = "${DealFmt.area(d.area)}㎡ · ${d.floor}층"
            c.drawText(l1, inner.left + 64f * F, y0 + blockH * 0.22f + 16f * F, StatCards.text(44f * F, StatCards.INK))
            val pv = price(d)
            val pp = StatCards.fit(pv, StatCards.text(60f * F, RED), inner.width() * 0.5f, 30f)
            c.drawText(pv, inner.right - pp.measureText(pv), y0 + blockH * 0.22f + 20f * F, pp)
            val gray = StatCards.text(32f * F, StatCards.GRAY, bold = false)
            val extra = listOf("계약 ${DealText.md(d.date)}", d.dealingGbn, d.contractType).filter { it.isNotBlank() }.joinToString(" · ")
            c.drawText(extra, inner.left + 64f * F, y0 + blockH * 0.45f, gray)
            val line3: String; val color3: Int
            if (cmp.count == 0) { line3 = "최근 ${i.period.label} 같은 면적 거래 없음"; color3 = StatCards.GRAY }
            else if (i.type == "monthly") { line3 = "같은 면적 평균 보증금 ${short(cmp.prevAvg)} (${cmp.count}건)"; color3 = StatCards.INK }
            else {
                val p = cmp.diffPct ?: 0.0
                line3 = "이전 평균 ${short(cmp.prevAvg)}(${cmp.count}건) 대비 ${if (cmp.diff > 0) "+" else if (cmp.diff < 0) "-" else ""}${short(abs(cmp.diff))} (${String.format("%s%.1f%%", if (p > 0) "+" else "", p)})"
                color3 = if (cmp.diff > 0) RED else if (cmp.diff < 0) BLUE else StatCards.INK
            }
            c.drawText(line3, inner.left + 64f * F, y0 + blockH * 0.66f, StatCards.fit(line3, StatCards.text(34f * F, color3), inner.width() - 70f * F, 22f))
            if (cmp.count > 0 && i.type != "monthly") {
                val line4 = when {
                    cmp.isRecordHigh -> "기간 내 최고가(${short(cmp.prevMax)}) 넘은 신고가"
                    cmp.isRecordLow -> "기간 내 최저가(${short(cmp.prevMin)})보다 낮음"
                    else -> "기간 내 최고 ${short(cmp.prevMax)} · 최저 ${short(cmp.prevMin)}"
                }
                c.drawText(line4, inner.left + 64f * F, y0 + blockH * 0.85f, StatCards.text(32f * F, if (cmp.isRecordHigh) RED else StatCards.GRAY, bold = cmp.isRecordHigh))
            }
        }
        caption(c, area.bottom + 48f * F)
        return bmp
    }

    /** history: 비교에 쓸 기간 안 거래 (i.deals와 같아도 됨) */
    fun render(ctx: Context, i: Input, mark: Watermark.Mark = Watermark.of(ctx)): List<Bitmap> {
        if (i.newKeys.isEmpty()) return listOfNotNull(trend(ctx, i, mark), list(ctx, i, mark))
        val compares = i.valid.filter { it.key in i.newKeys }.map { DealText.compare(it, i.deals, i.newKeys) }
        return listOfNotNull(fresh(ctx, i, compares, mark), trend(ctx, i, mark, title2 = "새 실거래와 추이"), list(ctx, i, mark))
    }
}
