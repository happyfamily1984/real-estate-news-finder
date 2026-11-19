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
            if (i.newKeys.isNotEmpty()) StatCards.Theme.ALERT else StatCards.Theme.INDIGO,
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

    // ---------- 내 돈으로 살 수 있는 아파트 ----------
    fun budget(ctx: Context, place: String, band: Int, budget: Long, period: DealPeriod, r: DealTools.BudgetResult, today: LocalDate, mark: Watermark.Mark = Watermark.of(ctx)): List<Bitmap> {
        if (r.total == 0) return emptyList()
        val bmp = Bitmap.createBitmap(W, StatCards.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tiles = if (r.items.isEmpty()) listOf(StatCards.Tile("가능한 단지", "0곳", StatCards.INK), StatCards.Tile("가장 싼 곳", short(r.over.first().avg), RED))
        else listOf(StatCards.Tile("가능한 단지", "${r.items.size}곳", StatCards.INK), StatCards.Tile("가장 비싼 곳", short(r.items.first().avg), RED), StatCards.Tile("가장 싼 곳", short(r.items.last().avg), BLUE))
        val base = StatCards.frameCustom(ctx, c, "${band}㎡ · 최근 ${period.label} 실거래", place, "${short(budget)}으로 살 수 있는 아파트", "거래된 ${r.total}개 단지 중 ${r.items.size}곳 · ${today.monthValue}/${today.dayOfMonth} 기준", tiles, mark, StatCards.Theme.GREEN)
        val area = RectF(base.left, base.top, base.right, base.bottom + 40f * F)
        val rows = r.items.ifEmpty { r.over }.take(11)
        val inner = StatCards.panel(c, area, if (r.items.isEmpty()) "예산을 넘는 가장 싼 단지" else "살 수 있는 아파트")
        val rowH = inner.height() / rows.size.coerceAtLeast(8)
        val size = (rowH * 0.44f).coerceIn(24f, 36f)
        rows.forEachIndexed { k, it ->
            val cy = inner.top + rowH * k + rowH / 2
            val num = "${k + 1}"
            c.drawCircle(inner.left + 22f * F, cy, 20f * F, StatCards.fill(if (k < 3) StatCards.themeDark else 0xFFB5C9BC.toInt()))
            val np = StatCards.text(24f * F, 0xFFFFFFFF.toInt())
            c.drawText(num, inner.left + 22f * F - np.measureText(num) / 2, cy + 9f * F, np)
            val pv = short(it.avg)
            val pp = StatCards.text(size * 1.1f, if (r.items.isEmpty()) RED else StatCards.themeDark)
            val priceW = pp.measureText(pv)
            c.drawText(pv, inner.right - priceW, cy + size * 0.1f, pp)
            val nameMax = inner.width() - 70f * F - priceW - 24f * F
            c.drawText(it.aptNm, inner.left + 58f * F, cy - size * 0.08f, StatCards.fit(it.aptNm, StatCards.text(size, StatCards.INK), nameMax, 18f))
            val sub = listOfNotNull(it.umdNm.takeIf { u -> u.isNotBlank() }, it.buildYear?.let { y -> "${y}년" }, "${it.count}건", "최근 ${DealText.md(it.last.date)} ${short(it.last.price)}").joinToString(" · ")
            c.drawText(sub, inner.left + 58f * F, cy + size * 0.82f, StatCards.fit(sub, StatCards.text(size * 0.66f, StatCards.GRAY, bold = false), inner.width() - 70f * F, 14f))
            if (k < rows.size - 1) c.drawRect(inner.left, inner.top + rowH * (k + 1) - 1f, inner.right, inner.top + rowH * (k + 1), StatCards.fill(0xFFEEF2F9.toInt()))
        }
        val p = StatCards.text(29f * F, StatCards.GRAY, bold = false)
        c.drawText("자료 : 국토교통부 실거래가 (단지별 평균" + (if (r.excluded > 0) ", 빌라형·소규모 제외" else "") + ")" + (if (r.items.size > rows.size) " · 외 ${r.items.size - rows.size}곳" else ""), StatCards.PAD + 18f * F, area.bottom + 48f * F, p)
        return listOf(bmp)
    }

    // ---------- 과거 가격 비교 ----------
    fun history(ctx: Context, aptNm: String, place: String, areaKey: Int, points: List<DealTools.HistoryPoint>, today: LocalDate, mark: Watermark.Mark = Watermark.of(ctx), note: String? = null): List<Bitmap> {
        val now = points.firstOrNull { it.yearsAgo == 0 && it.ok } ?: return emptyList()
        val order = points.sortedByDescending { it.yearsAgo } // 오래된 것부터
        val bmp = Bitmap.createBitmap(W, StatCards.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val oldest = order.firstOrNull { it.ok && it.yearsAgo > 0 }
        val tiles = mutableListOf(StatCards.Tile("지금", short(now.avg), RED))
        order.filter { it.ok && it.yearsAgo > 0 }.sortedBy { it.yearsAgo }.take(2).forEach { tiles += StatCards.Tile(it.label, short(it.avg), BLUE) }
        val area = StatCards.frameCustom(ctx, c, "매매 실거래 · ${areaKey}㎡", aptNm, "그때와 지금", "$place · ${today.monthValue}/${today.dayOfMonth} 기준", tiles, mark, StatCards.Theme.NIGHT)
        val inner = StatCards.panel(c, area, "실거래 평균가 변화")
        val rowH = inner.height() / order.size
        val maxV = order.maxOf { it.avg }.coerceAtLeast(1)
        val labelW = 150f * F
        val barL = inner.left + labelW; val barMax = inner.width() - labelW - 20f * F
        order.forEachIndexed { k, p ->
            val y0 = inner.top + rowH * k
            val cy = y0 + rowH * 0.42f
            c.drawText(p.label, inner.left, cy + 6f * F, StatCards.text(38f * F, StatCards.INK))
            c.drawText(p.whenLabel, inner.left, cy + 46f * F, StatCards.text(24f * F, StatCards.GRAY, bold = false))
            if (!p.ok) {
                c.drawText(p.reason ?: "자료 없음", barL, cy + 10f * F, StatCards.fit(p.reason ?: "", StatCards.text(30f * F, StatCards.GRAY, bold = false), barMax, 18f))
            } else {
                val w = (barMax * 0.62f * p.avg / maxV).coerceAtLeast(8f)
                val bh = (rowH * 0.34f).coerceAtMost(54f * F)
                c.drawRoundRect(RectF(barL, cy - bh / 2, barL + w, cy + bh / 2), 8f, 8f, StatCards.fill(if (p.yearsAgo == 0) RED else BLUE))
                c.drawText(short(p.avg), barL + w + 14f * F, cy + 14f * F, StatCards.text(42f * F, if (p.yearsAgo == 0) RED else StatCards.NAVY))
                val sub = if (p.yearsAgo == 0) "${p.count}건 평균" else {
                    val times = String.format("%.1f", now.avg.toDouble() / p.avg)
                    val rate = DealTools.cagr(now.avg, p.avg, p.yearsAgo)?.let { String.format(" · 연평균 %+.1f%%", it) } ?: ""
                    "${p.count}건 평균 → 지금까지 ${times}배$rate"
                }
                c.drawText(sub, barL, cy + bh / 2 + 36f * F, StatCards.fit(sub, StatCards.text(27f * F, StatCards.GRAY, bold = false), barMax, 16f))
            }
            if (k < order.size - 1) c.drawRect(inner.left, y0 + rowH - 1f, inner.right, y0 + rowH, StatCards.fill(StatCards.LINE))
        }
        val p = StatCards.text(29f * F, StatCards.GRAY, bold = false)
        // 물가와 견준 한 줄이 있으면 그걸, 없으면 오른 금액
        val first = note ?: oldest?.let { "${it.label}보다 ${short(kotlin.math.abs(now.avg - it.avg))} ${if (now.avg >= it.avg) "오름" else "내림"}" }
        first?.let { c.drawText(it, StatCards.PAD + 18f * F, area.bottom + 52f * F, StatCards.fit(it, p, W - StatCards.PAD * 2 - 36f * F, 18f)) }
        c.drawText("자료 : 국토교통부 실거래가 (각 시점 앞뒤 1년 평균)", StatCards.PAD + 18f * F, area.bottom + 96f * F, p)
        return listOf(bmp)
    }

    // ---------- 분양가 vs 실거래 ----------
    /** 금액이 주인공: 줄마다 "분양가 17.4억 → 실거래 39.9억"을 크게, 오른 금액을 오른쪽에 */
    fun presale(ctx: Context, place: String, band: Int, period: DealPeriod, matches: List<DealTools.PresaleMatch>, today: LocalDate, mark: Watermark.Mark = Watermark.of(ctx)): List<Bitmap> {
        if (matches.isEmpty()) return emptyList()
        val bmp = Bitmap.createBitmap(W, StatCards.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun signed(v: Long) = (if (v >= 0) "+" else "-") + short(abs(v))
        fun color(v: Long) = if (v > 0) RED else if (v < 0) BLUE else StatCards.INK
        val top = matches.first()
        val meanDiff = matches.map { it.diff }.average().roundToLong()
        val tiles = if (matches.size == 1) listOf(StatCards.Tile("분양가", short(top.presale), BLUE), StatCards.Tile("최근 실거래", short(top.avg), RED), StatCards.Tile("차이", signed(top.diff), color(top.diff)))
        else listOf(StatCards.Tile("비교 단지", "${matches.size}곳", StatCards.INK), StatCards.Tile("평균 차이", signed(meanDiff), color(meanDiff)), StatCards.Tile("가장 큰 차이", signed(top.diff), color(top.diff)))
        val base = StatCards.frameCustom(ctx, c, "${band}㎡ · 10년 이내 단지", place, "분양가 vs 실거래가", "실거래 최근 ${period.label} 평균 · ${today.monthValue}/${today.dayOfMonth} 기준", tiles, mark, StatCards.Theme.SUNSET)
        val area = RectF(base.left, base.top, base.right, base.bottom + 40f * F)
        val inner = StatCards.panel(c, area, "분양가 → 최근 실거래가")
        val rows = matches.take(6)
        val rowH = inner.height() / rows.size.coerceAtLeast(3)
        rows.forEachIndexed { k, m ->
            val y0 = inner.top + rowH * k
            val name = "${k + 1}. ${m.aptNm}"
            val sub = "${m.item.date.take(4)}년 분양 · 실거래 ${m.count}건"
            val subP = StatCards.text(26f * F, StatCards.GRAY, bold = false)
            val subW = subP.measureText(sub)
            c.drawText(name, inner.left, y0 + rowH * 0.34f, StatCards.fit(name, StatCards.text(36f * F, StatCards.INK), inner.width() - subW - 20f * F, 20f))
            c.drawText(sub, inner.right - subW, y0 + rowH * 0.34f, subP)
            // 금액 줄: 분양가 → 실거래가 (크게), 오른 금액
            val by = y0 + rowH * 0.78f
            val big = (rowH * 0.36f).coerceIn(40f, 70f * F)
            var x = inner.left
            val lab = StatCards.text(big * 0.46f, StatCards.GRAY, bold = false)
            fun put(t: String, p: Paint) { c.drawText(t, x, by, p); x += p.measureText(t) }
            put("분양가 ", lab); put(short(m.presale), StatCards.text(big, StatCards.NAVY)); put("  →  ", StatCards.text(big * 0.7f, StatCards.GRAY, bold = false)); put("실거래 ", lab); put(short(m.avg), StatCards.text(big, RED))
            val dv = signed(m.diff)
            val dp = StatCards.fit(dv, StatCards.text(big * 0.82f, color(m.diff)), (inner.right - x - 16f * F).coerceAtLeast(60f), 20f)
            c.drawText(dv, inner.right - dp.measureText(dv), by, dp)
            if (k < rows.size - 1) c.drawRect(inner.left, y0 + rowH - 1f, inner.right, y0 + rowH, StatCards.fill(StatCards.LINE))
        }
        c.drawText("자료 : 청약홈 분양가(최근 5년 공고), 국토교통부 실거래가" + (if (matches.size > rows.size) " · 외 ${matches.size - rows.size}곳" else ""), StatCards.PAD + 18f * F, area.bottom + 48f * F, StatCards.text(29f * F, StatCards.GRAY, bold = false))
        return listOf(bmp)
    }

    // ---------- 지역별 최고 실거래 단지 ----------
    fun top(ctx: Context, place: String, band: Int, period: DealPeriod, items: List<DealTools.TopItem>, today: LocalDate, mark: Watermark.Mark = Watermark.of(ctx)): List<Bitmap> {
        if (items.isEmpty()) return emptyList()
        val rows = items.take(10)
        val bmp = Bitmap.createBitmap(W, StatCards.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tiles = listOfNotNull(
            StatCards.Tile("1위", short(rows.first().price), RED),
            rows.getOrNull(4)?.let { StatCards.Tile("5위", short(it.price), StatCards.NAVY) },
            rows.getOrNull(9)?.let { StatCards.Tile("10위", short(it.price), BLUE) },
        )
        val base = StatCards.frameCustom(ctx, c, "매매 실거래 · 최근 ${period.label}" + (if (band == 0) "" else " · ${band}㎡"), place, "최고 실거래 단지", "최근 ${period.label}간 실거래가 기준 · ${today.monthValue}/${today.dayOfMonth}", tiles, mark, StatCards.Theme.GOLD)
        val area = RectF(base.left, base.top, base.right, base.bottom + 40f * F)
        val inner = StatCards.panel(c, area, "최고가 순위 1~${rows.size}위")
        val rowH = inner.height() / rows.size.coerceAtLeast(8)
        val size = (rowH * 0.44f).coerceIn(24f, 36f)
        rows.forEachIndexed { k, t ->
            val d = t.deal
            val cy = inner.top + rowH * k + rowH / 2
            val num = "${k + 1}"
            val medal = intArrayOf(0xFFE2A400.toInt(), 0xFF9AA5B1.toInt(), 0xFFB8733A.toInt())
            c.drawCircle(inner.left + 22f * F, cy, (if (k < 3) 23f else 19f) * F, StatCards.fill(if (k < 3) medal[k] else 0xFF2B2B2B.toInt()))
            val np = StatCards.text(24f * F, 0xFFFFFFFF.toInt())
            c.drawText(num, inner.left + 22f * F - np.measureText(num) / 2, cy + 9f * F, np)
            val pv = short(t.price)
            val pp = StatCards.text(size * 1.15f, if (k == 0) 0xFFB07D00.toInt() else 0xFF1B1B1B.toInt())
            val pw = pp.measureText(pv)
            c.drawText(pv, inner.right - pw, cy + size * 0.12f, pp)
            val maxW = inner.width() - 70f * F - pw - 24f * F
            c.drawText(d.aptNm, inner.left + 58f * F, cy - size * 0.08f, StatCards.fit(d.aptNm, StatCards.text(size, StatCards.INK), maxW, 18f))
            val sub = listOfNotNull(t.gu.takeIf { it.isNotBlank() }, d.umdNm.takeIf { it.isNotBlank() }, "${DealFmt.area(d.area)}㎡ ${d.floor}층", d.date.substring(2, 7).replace('-', '.')).joinToString(" · ")
            c.drawText(sub, inner.left + 58f * F, cy + size * 0.82f, StatCards.fit(sub, StatCards.text(size * 0.66f, StatCards.GRAY, bold = false), inner.width() - 70f * F, 14f))
            if (k < rows.size - 1) c.drawRect(inner.left, inner.top + rowH * (k + 1) - 1f, inner.right, inner.top + rowH * (k + 1), StatCards.fill(0xFFEEF2F9.toInt()))
        }
        val cap = StatCards.text(29f * F, StatCards.GRAY, bold = false)
        c.drawText("최근 ${period.label}간 실거래가 기준 (${period.label} 이전에 더 높은 거래가 있을 수 있음)", StatCards.PAD + 18f * F, area.bottom + 48f * F, cap)
        c.drawText("자료 : 국토교통부 실거래가 (단지별 최고가 한 건. 해제 거래 제외)", StatCards.PAD + 18f * F, area.bottom + 92f * F, cap)
        return listOf(bmp)
    }

    // ---------- (실험실) 자금 계산 ----------
    fun funding(ctx: Context, aptNm: String, place: String, areaKey: Int, f: DealTools.Funding, loanRatio: Int, today: LocalDate, mark: Watermark.Mark = Watermark.of(ctx)): List<Bitmap> {
        val bmp = Bitmap.createBitmap(W, StatCards.H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val lack = f.shortfall > 0
        val tiles = listOf(StatCards.Tile("매매가", short(f.price), StatCards.INK), StatCards.Tile("대출", short(f.loan), BLUE), StatCards.Tile(if (lack) "부족한 돈" else "남는 돈", short(abs(f.shortfall)), if (lack) RED else 0xFF12A150.toInt()))
        val base = StatCards.frameCustom(ctx, c, "${areaKey}㎡ · 최근 1년 실거래 평균", aptNm, "${short(f.savings)}으로 사는 법", "$place · ${today.monthValue}/${today.dayOfMonth} 기준", tiles, mark, StatCards.Theme.PLAN)
        val area = RectF(base.left, base.top, base.right, base.bottom + 40f * F)
        val inner = StatCards.panel(c, area, "얼마가 필요하고 어떻게 채우나")
        val rows = listOf(
            Triple("매매가", short(f.price), StatCards.INK), Triple("취득세 (1주택 기준 대략)", "+" + short(f.tax), StatCards.GRAY), Triple("중개보수 (상한)", "+" + short(f.fee), StatCards.GRAY),
            Triple("필요한 돈 합계", short(f.total), StatCards.themeDark), Triple("대출 (집값의 $loanRatio%)", "-" + short(f.loan), BLUE), Triple("모은 돈", "-" + short(f.savings), BLUE),
            Triple(if (lack) "부족한 돈" else "남는 돈", short(abs(f.shortfall)), if (lack) RED else 0xFF12A150.toInt()),
        )
        val rowH = inner.height() / rows.size
        rows.forEachIndexed { k, (label, value, color) ->
            val cy = inner.top + rowH * k + rowH / 2
            val strong = k == 3 || k == rows.size - 1
            if (strong) c.drawRoundRect(RectF(inner.left - 8f, cy - rowH * 0.42f, inner.right + 8f, cy + rowH * 0.42f), 12f, 12f, StatCards.fill(if (k == 3) 0x142C6E92 else if (lack) 0x14E5262B else 0x1412A150))
            c.drawText(label, inner.left + 10f * F, cy + 12f * F, StatCards.text((if (strong) 38f else 34f) * F, StatCards.INK, bold = strong))
            val vp = StatCards.text((if (strong) 50f else 40f) * F, color)
            c.drawText(value, inner.right - 10f * F - vp.measureText(value), cy + 14f * F, vp)
            if (!strong && k < rows.size - 1) c.drawRect(inner.left, inner.top + rowH * (k + 1) - 1f, inner.right, inner.top + rowH * (k + 1), StatCards.fill(0xFFEEF2F9.toInt()))
        }
        val cap = StatCards.text(29f * F, StatCards.GRAY, bold = false)
        c.drawText("취득세·중개보수는 대략 계산. 대출 한도는 소득·규제에 따라 사람마다 다름", StatCards.PAD + 18f * F, area.bottom + 48f * F, cap)
        c.drawText("자료 : 국토교통부 실거래가 (최근 1년 평균)", StatCards.PAD + 18f * F, area.bottom + 92f * F, cap)
        return listOf(bmp)
    }
}
