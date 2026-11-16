package com.realestatenewsfinder.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/** 실거래 조회 기간 (1개월 → 5년 순서) */
enum class DealPeriod(val months: Int, val label: String) {
    M1(1, "1개월"), M3(3, "3개월"), M6(6, "6개월"), Y1(12, "1년"), Y3(36, "3년"), Y5(60, "5년");

    /** 오늘 기준 시작일 */
    fun since(today: LocalDate): String = today.minusMonths(months.toLong()).toString()

    /** 받아야 하는 달 수 (시작일이 걸친 달까지) */
    val fetchMonths: Int get() = months + 1
}

/**
 * 실거래 자료를 스레드 글로. AI를 쓰지 않고 숫자를 그대로 문장에 끼워 넣어서 숫자가 틀릴 일이 없음.
 * 금액 단위는 국토부 자료와 같은 만원.
 */
object DealText {
    /** 거래의 대표 금액: 매매는 거래가, 전·월세는 보증금 */
    fun value(d: Deal): Long = if (d.type == "trade") d.price else d.deposit

    /** 같은 평형으로 묶는 기준: 전용면적 정수 부분 (84.93·84.99 → 84) */
    fun areaKey(d: Deal): Int = d.area.toInt()

    fun md(date: String): String = date.split('-').let { if (it.size == 3) "${it[1].toInt()}/${it[2].toInt()}" else date }

    /** 16억 5,000만원 */
    fun won(v: Long): String = DealFmt.manwon(v) + "원"

    /** 금액 차이: "5,000만원 높음" / 반말·존댓말 */
    private fun pct(now: Long, base: Long): String {
        if (base <= 0) return ""
        val p = (now - base) * 100.0 / base
        return String.format("%s%.1f%%", if (p > 0) "+" else "", p)
    }

    private fun avg(list: List<Long>): Long = if (list.isEmpty()) 0 else list.average().roundToLong()

    private fun place(regionName: String, umdNm: String) = listOf(regionName.substringAfterLast(' ', regionName), umdNm).filter { it.isNotBlank() }.joinToString(" ")

    private fun one(d: Deal): String = "${areaKey(d)}㎡ ${d.floor}층 ${DealFmt.price(d)}" + (if (d.type == "monthly") "원" else "원")

    /**
     * 기간 요약 글. deals: 그 단지·유형의 기간 안 거래 (해제 포함 가능, 여기서 뺌)
     */
    fun summary(aptNm: String, regionName: String, umdNm: String, type: String, period: DealPeriod, deals: List<Deal>, casual: Boolean): String {
        fun e(c: String, p: String) = if (casual) c else p
        val label = Molit.TYPES[type] ?: type
        val valid = deals.filter { !it.canceled }.sortedWith(compareByDescending<Deal> { it.date }.thenByDescending { it.floor })
        val lines = mutableListOf<String>()
        lines += "$aptNm 최근 ${period.label} $label 실거래 정리 (${place(regionName, umdNm)})"
        if (valid.isEmpty()) {
            lines += "최근 ${period.label} 동안 신고된 $label 실거래가 없" + e("음", "어요")
            lines += "자료: 국토교통부 실거래가 공개시스템"
            return lines.joinToString("\n\n")
        }
        val vals = valid.map(::value)
        val hi = valid.maxBy(::value); val lo = valid.minBy(::value)
        if (type == "monthly") {
            lines += "거래 ${valid.size}건, 평균 보증금 ${won(avg(vals))} / 월세 ${"%,d".format(avg(valid.map { it.monthlyRent }))}만원"
        } else {
            lines += "거래 ${valid.size}건, 평균 ${won(avg(vals))}"
            if (valid.size >= 2) lines += "최고 ${won(value(hi))} (${areaKey(hi)}㎡ ${hi.floor}층, ${md(hi.date)})\n최저 ${won(value(lo))} (${areaKey(lo)}㎡ ${lo.floor}층, ${md(lo.date)})"
        }
        // 면적별 평균 (거래 많은 순 4개)
        val byArea = valid.groupBy(::areaKey).entries.sortedByDescending { it.value.size }.take(4).sortedBy { it.key }
        if (byArea.size >= 2 || (byArea.size == 1 && valid.size >= 2)) {
            lines += "면적별 평균\n" + byArea.joinToString("\n") { (a, ds) ->
                if (type == "monthly") "${a}㎡ 보증금 ${won(avg(ds.map(::value)))} / 월 ${"%,d".format(avg(ds.map { it.monthlyRent }))}만원 (${ds.size}건)"
                else "${a}㎡ ${won(avg(ds.map(::value)))} (${ds.size}건)"
            }
        }
        // 기간 앞 절반과 뒤 절반 비교 (가장 거래가 많은 면적 기준 → 평형이 섞여 생기는 착시를 줄임)
        if (type != "monthly") {
            val main = valid.groupBy(::areaKey).maxBy { it.value.size }
            val sorted = main.value.sortedBy { it.date }
            if (sorted.size >= 4) {
                val half = sorted.size / 2
                val before = avg(sorted.take(half).map(::value)); val after = avg(sorted.drop(half).map(::value))
                if (before > 0 && after != before) {
                    lines += "${main.key}㎡ 기준, 기간 앞쪽 평균 ${won(before)} → 뒤쪽 평균 ${won(after)} (${pct(after, before)})"
                }
            }
        }
        val last = valid.first()
        lines += "가장 최근 거래는 ${md(last.date)} ${one(last)}"
        lines += "자료: 국토교통부 실거래가 (계약일 기준, 해제 거래 제외)"
        return lines.joinToString("\n\n")
    }

    /** 새 거래 하나를 같은 면적의 이전 거래와 비교한 결과 */
    data class Compare(
        val deal: Deal,
        /** 같은 면적의 이전 거래 수 */
        val count: Int,
        val prevAvg: Long, val prevMax: Long, val prevMin: Long,
        /** 직전 거래 (계약일이 가장 가까운 이전 거래) */
        val last: Deal?,
    ) {
        val diff: Long get() = DealText.value(deal) - prevAvg
        val diffPct: Double? get() = if (prevAvg > 0) diff * 100.0 / prevAvg else null
        val isRecordHigh: Boolean get() = count > 0 && DealText.value(deal) > prevMax
        val isRecordLow: Boolean get() = count > 0 && DealText.value(deal) < prevMin
    }

    /** 새 거래를 기간 안의 같은 유형·같은 면적 거래(새 거래와 해제 거래 제외)와 비교 */
    fun compare(fresh: Deal, history: List<Deal>, freshKeys: Set<String>): Compare {
        val prev = history.filter { !it.canceled && it.type == fresh.type && it.key !in freshKeys && it.key != fresh.key && areaKey(it) == areaKey(fresh) }
        val vals = prev.map(::value)
        return Compare(
            fresh, prev.size, avg(vals), vals.maxOrNull() ?: 0, vals.minOrNull() ?: 0,
            prev.filter { it.date <= fresh.date }.maxWithOrNull(compareBy<Deal> { it.date }.thenBy { it.floor }) ?: prev.maxByOrNull { it.date },
        )
    }

    /**
     * 새 실거래 분석 글: "최근 OO단지 실거래 발생" + 이전 평균·최고가와 비교.
     * fresh: 새로 신고된 거래, history: 비교에 쓸 기간 안 전체 거래 (모든 유형 섞여 있어도 됨)
     */
    fun fresh(aptNm: String, regionName: String, umdNm: String, fresh: List<Deal>, history: List<Deal>, period: DealPeriod, casual: Boolean): String {
        fun e(c: String, p: String) = if (casual) c else p
        val keys = fresh.map { it.key }.toSet()
        val news = fresh.filter { !it.canceled }.sortedWith(compareByDescending<Deal> { it.date }.thenByDescending { value(it) })
        val lines = mutableListOf<String>()
        lines += "최근 $aptNm 실거래 발생 (${place(regionName, umdNm)})"
        if (news.isEmpty()) {
            val c = fresh.filter { it.canceled }
            lines += if (c.isNotEmpty()) "거래 해제 ${c.size}건: " + c.take(3).joinToString(", ") { "${md(it.date)} ${one(it)}" } else "새로 신고된 거래가 없" + e("음", "어요")
            return lines.joinToString("\n\n")
        }
        for (d in news.take(2)) { // 스레드 500자 안에 들어가게 두 건까지 자세히
            val c = compare(d, history, keys)
            val label = Molit.TYPES[d.type] ?: d.type
            val sb = StringBuilder("[$label] ${areaKey(d)}㎡ ${d.floor}층 ${DealFmt.price(d)}원 (계약 ${md(d.date)})")
            if (d.type == "trade" && d.dealingGbn == "직거래") sb.append(" · 직거래")
            if (d.type != "trade" && d.contractType.isNotBlank()) sb.append(" · ${d.contractType}")
            if (c.count == 0) {
                sb.append("\n최근 ${period.label} 동안 같은 면적 $label 거래가 없어서 비교할 값이 없" + e("음", "어요"))
            } else if (d.type == "monthly") {
                val same = history.filter { !it.canceled && it.type == "monthly" && it.key !in keys && areaKey(it) == areaKey(d) }
                sb.append("\n같은 면적 최근 ${period.label} 평균(${c.count}건)은 보증금 ${won(c.prevAvg)} / 월 ${"%,d".format(avg(same.map { it.monthlyRent }))}만원")
            } else {
                val v = value(d)
                sb.append("\n같은 면적 최근 ${period.label} 평균 ${won(c.prevAvg)}(${c.count}건)")
                sb.append(
                    when {
                        c.diff == 0L -> "과 같" + e("음", "아요")
                        c.diff > 0 -> "보다 ${won(c.diff)} 높" + e("음", "아요") + " (${pct(v, c.prevAvg)})"
                        else -> "보다 ${won(abs(c.diff))} 낮" + e("음", "아요") + " (${pct(v, c.prevAvg)})"
                    },
                )
                sb.append(
                    when {
                        c.isRecordHigh -> "\n기간 내 최고가 ${won(c.prevMax)}을 넘은 신고가" + e("", "예요")
                        c.isRecordLow -> "\n기간 내 최저가 ${won(c.prevMin)}보다 낮은 거래" + e("", "예요")
                        else -> "\n기간 내 최고 ${won(c.prevMax)}, 최저 ${won(c.prevMin)} 사이"
                    },
                )
                c.last?.let { p ->
                    val dv = v - value(p)
                    sb.append("\n직전 거래(${md(p.date)} ${p.floor}층 ${won(value(p))})보다 ")
                    sb.append(if (dv == 0L) "같은 가격" else "${won(abs(dv))} " + (if (dv > 0) "올랐" else "내렸") + e("음", "어요"))
                }
            }
            lines += sb.toString()
        }
        if (news.size > 2) lines += "외 ${news.size - 2}건: " + news.drop(2).take(3).joinToString(", ") { "${areaKey(it)}㎡ ${DealFmt.price(it)}원" }
        val canceled = fresh.count { it.canceled }
        if (canceled > 0) lines += "거래 해제 ${canceled}건도 있" + e("음", "어요")
        lines += "자료: 국토교통부 실거래가 (층·동·향에 따라 가격 차이가 있을 수 있음)"
        return lines.joinToString("\n\n")
    }
}
