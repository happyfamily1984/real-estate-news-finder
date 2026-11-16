package com.realestatenewsfinder.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 실거래 활용 기능의 계산과 글 (화면·안드로이드와 무관 → 단위 테스트 가능).
 *  - 내 돈으로 살 수 있는 아파트: 예산·평형·지역·기간 → 실거래 평균이 예산 이하인 단지
 *  - 과거 가격 비교: 단지·평형 → 지금과 5·10·20년 전 실거래 평균
 * 금액 단위는 만원.
 */
object DealTools {
    /** 고를 수 있는 평형 */
    val BANDS = listOf(59, 84)

    /** 평형 묶음: 59 → 전용 58㎡ 이상 61㎡ 미만, 84 → 83㎡ 이상 86㎡ 미만 */
    fun inBand(area: Double, band: Int): Boolean = area >= band - 1 && area < band + 2

    private fun avg(l: List<Long>): Long = if (l.isEmpty()) 0 else l.average().roundToLong()
    private fun won(v: Long) = DealText.won(v)

    // ---------- 내 돈으로 살 수 있는 아파트 ----------
    data class BudgetItem(val complexId: String, val aptNm: String, val umdNm: String, val buildYear: Int?, val count: Int, val avg: Long, val min: Long, val max: Long, val last: Deal)

    data class BudgetResult(
        /** 예산으로 살 수 있는 단지 (평균가 높은 순 = 예산에 가까운 순) */
        val items: List<BudgetItem>,
        /** 예산을 넘는 단지 (평균가 낮은 순 = 조금만 더 있으면 되는 순) */
        val over: List<BudgetItem>,
        /** 아파트로 보기 어려워 뺀 곳 수 (빌라형 이름, 4층 이하, 거래가 거의 없는 소규모) */
        val excluded: Int = 0,
    ) {
        val total: Int get() = items.size + over.size
    }

    /** 이름만 봐도 아파트 단지가 아닌 곳 (국토부 아파트 자료에 섞여 있는 빌라형·도시형생활주택 등) */
    private val NOT_APT = Regex("빌라|연립|다세대|도시형|주택$|타운하우스|오피스텔|원룸|레지던스|생활주택")

    /** '○○빌'로 끝나는 이름: 소규모 빌라형이 많지만 큰 주상복합(쉐르빌·아크로빌 등)도 있어서 거래가 적을 때만 뺌 */
    private val MAYBE_NOT_APT = Regex("빌$|빌[0-9]{1,2}차?$|빌[가-힣A-Za-z]$")

    /**
     * 아파트 단지로 볼 만한지. 국토부 자료에는 세대수가 없어서 거래 기록으로 추정함:
     *  - 이름이 빌라형이면 제외 ('○○빌'은 거래가 10건 미만일 때만)
     *  - 거래된 층이 모두 4층 이하면 제외 (아파트는 5층 이상)
     *  - 알려진 거래(매매·전월세)가 3건 미만이면 소규모로 보고 제외
     * known: 그 단지의 알려진 모든 거래 (기간 안 매매 + 최근 6개월 매매·전월세)
     */
    fun looksLikeApartment(aptNm: String, known: List<Deal>): Boolean {
        if (NOT_APT.containsMatchIn(aptNm.replace(" ", ""))) return false
        if (known.size < 3) return false
        if (known.size < 10 && MAYBE_NOT_APT.containsMatchIn(aptNm.replace(" ", ""))) return false
        return known.maxOf { it.floor } >= 5
    }

    /**
     * deals: 그 지역의 기간 안 매매 거래. 단지별 평균가가 budget(만원) 이하면 살 수 있는 아파트.
     * aptOnly면 아파트로 보기 어려운 곳을 뺌 (extra: 판단에 보탤 최근 6개월 매매·전월세 거래)
     */
    fun budget(deals: List<Deal>, band: Int, budget: Long, aptOnly: Boolean = false, extra: List<Deal> = emptyList()): BudgetResult {
        val known = (deals + extra).filter { !it.canceled }.distinctBy { it.key }.groupBy { it.complexId }
        var excluded = 0
        val all = deals.filter { it.type == "trade" && !it.canceled && inBand(it.area, band) }.groupBy { it.complexId }.map { (id, ds) ->
            val sorted = ds.sortedByDescending { it.date }
            val prices = ds.map { it.price }
            BudgetItem(id, sorted.first().aptNm, sorted.first().umdNm, ds.firstNotNullOfOrNull { it.buildYear }, ds.size, avg(prices), prices.min(), prices.max(), sorted.first())
        }.filter { i -> (!aptOnly || looksLikeApartment(i.aptNm, known[i.complexId].orEmpty())).also { ok -> if (!ok) excluded++ } }
        return BudgetResult(all.filter { it.avg <= budget }.sortedByDescending { it.avg }, all.filter { it.avg > budget }.sortedBy { it.avg }, excluded)
    }

    private fun itemLine(i: BudgetItem) = "${i.aptNm} (${listOfNotNull(i.umdNm.takeIf { it.isNotBlank() }, i.buildYear?.let { "${it % 100}년".padStart(3, '0') }).joinToString("·")}) ${won(i.avg)} · ${i.count}건"

    fun budgetText(regionName: String, band: Int, budget: Long, period: DealPeriod, r: BudgetResult, casual: Boolean): String {
        fun e(c: String, p: String) = if (casual) c else p
        val place = regionName.substringAfter(' ', regionName)
        val lines = mutableListOf<String>()
        lines += "${place}에서 ${won(budget)}으로 살 수 있는 ${band}㎡ 아파트 (최근 ${period.label} 실거래 평균 기준)"
        if (r.total == 0) {
            lines += "최근 ${period.label} 동안 ${band}㎡ 매매 실거래가 없" + e("음", "어요")
            return lines.joinToString("\n\n")
        }
        if (r.items.isEmpty()) {
            val c = r.over.first()
            lines += "거래된 ${r.total}개 단지 중 이 예산으로 살 수 있는 곳이 없" + e("음", "어요")
            lines += "가장 싼 곳이 ${itemLine(c)}\n${won(c.avg - budget)} 더 필요"
        } else {
            lines += "거래된 ${r.total}개 단지 중 ${r.items.size}곳 가능 (${r.items.size * 100 / r.total}%)"
            val show = r.items.take(6)
            lines += show.mapIndexed { k, i -> "${k + 1}. ${itemLine(i)}" }.joinToString("\n") + (if (r.items.size > show.size) "\n외 ${r.items.size - show.size}곳" else "")
            val tight = r.items.count { it.avg >= budget * 0.9 }
            val cheap = r.items.last()
            val notes = mutableListOf<String>()
            if (r.items.size >= 2) notes += "가장 싼 곳은 ${cheap.aptNm} ${won(cheap.avg)} (${won(budget - cheap.avg)} 남음)"
            if (tight > 0 && tight < r.items.size) notes += "예산의 90% 넘게 써야 하는 곳 ${tight}곳"
            r.over.firstOrNull()?.let { notes += "조금 모자란 곳: ${it.aptNm} ${won(it.avg)} (${won(it.avg - budget)} 부족)" }
            if (notes.isNotEmpty()) lines += notes.joinToString("\n")
        }
        lines += "자료: 국토교통부 실거래가 (단지별 평균. 층·동에 따라 다름" + (if (r.excluded > 0) ", 빌라형·소규모 ${r.excluded}곳 제외" else "") + ")"
        return lines.joinToString("\n\n")
    }

    // ---------- 과거 가격 비교 ----------
    const val FIRST_YM = "200601" // 실거래가 공개 시작

    /** 비교할 시점 (몇 년 전) */
    val HISTORY_YEARS = listOf(5, 10, 20)

    /**
     * 비교할 달들. yearsAgo = 0이면 최근 12개월, 아니면 그 해 같은 달을 가운데 둔 12개월.
     * 실거래가 공개(2006년 1월) 전의 달은 뺌 → 전부 빠지면 빈 목록
     */
    fun historyYms(today: LocalDate, yearsAgo: Int): List<String> {
        val end = if (yearsAgo == 0) today.withDayOfMonth(1) else today.withDayOfMonth(1).minusYears(yearsAgo.toLong()).plusMonths(5)
        return (0 until 12).map { end.minusMonths(it.toLong()) }.map { "%d%02d".format(it.year, it.monthValue) }.filter { it >= FIRST_YM }
    }

    data class HistoryPoint(
        val yearsAgo: Int,
        /** "지금" / "10년 전" */
        val label: String,
        /** "2016년 무렵" */
        val whenLabel: String,
        val count: Int, val avg: Long, val min: Long, val max: Long,
        /** 값이 없는 까닭 (자료 없음·준공 전·거래 없음) */
        val reason: String? = null,
    ) {
        val ok: Boolean get() = count > 0
    }

    /** 같은 단지인지: 단지 번호가 같거나, 동 이름과 단지 이름(띄어쓰기 무시)이 같으면 같은 단지 (오래전 자료는 지번 표기가 다를 수 있음) */
    fun sameComplex(d: Deal, complexId: String, aptNm: String, umdNm: String): Boolean =
        d.complexId == complexId || (d.umdNm == umdNm && d.aptNm.replace(" ", "") == aptNm.replace(" ", ""))

    /** deals: 그 시점 창(historyYms)의 그 지역 매매 거래 */
    fun historyPoint(today: LocalDate, yearsAgo: Int, deals: List<Deal>?, complexId: String, aptNm: String, umdNm: String, areaKey: Int, buildYear: Int?): HistoryPoint {
        val year = today.year - yearsAgo
        val label = if (yearsAgo == 0) "지금" else "${yearsAgo}년 전"
        val whenLabel = if (yearsAgo == 0) "최근 1년" else "${year}년 무렵"
        fun none(reason: String) = HistoryPoint(yearsAgo, label, whenLabel, 0, 0, 0, 0, reason)
        if (yearsAgo > 0 && historyYms(today, yearsAgo).isEmpty()) return none("실거래가 공개(2006년) 전이라 자료 없음")
        if (yearsAgo > 0 && buildYear != null && year < buildYear - 1) return none("${buildYear}년 준공이라 그때는 없던 단지")
        if (deals == null) return none("자료를 받지 못함")
        val mine = deals.filter { it.type == "trade" && !it.canceled && sameComplex(it, complexId, aptNm, umdNm) && inBand(it.area, areaKey) }
        if (mine.isEmpty()) return none("그 무렵 같은 평형 거래 기록 없음")
        val p = mine.map { it.price }
        return HistoryPoint(yearsAgo, label, whenLabel, mine.size, avg(p), p.min(), p.max())
    }

    /** 연평균 상승률(%) */
    fun cagr(now: Long, past: Long, years: Int): Double? = if (past > 0 && now > 0 && years > 0) ((now.toDouble() / past).pow(1.0 / years) - 1) * 100 else null

    fun historyText(aptNm: String, place: String, areaKey: Int, points: List<HistoryPoint>, casual: Boolean): String {
        fun e(c: String, p: String) = if (casual) c else p
        val now = points.firstOrNull { it.yearsAgo == 0 }
        val lines = mutableListOf<String>()
        lines += "$aptNm ${areaKey}㎡, 그때와 지금 실거래가 ($place)"
        if (now == null || !now.ok) {
            lines += "최근 1년 동안 ${areaKey}㎡ 매매 실거래가 없어서 비교할 수 없" + e("음", "어요")
            return lines.joinToString("\n\n")
        }
        lines += "지금: 평균 ${won(now.avg)} (최근 1년 ${now.count}건)"
        val past = points.filter { it.yearsAgo > 0 }.sortedBy { it.yearsAgo }
        for (p in past) {
            if (!p.ok) { lines += "${p.label}: ${p.reason}"; continue }
            val diff = now.avg - p.avg
            val times = now.avg.toDouble() / p.avg
            val sb = StringBuilder("${p.label}(${p.whenLabel}): 평균 ${won(p.avg)} (${p.count}건)")
            sb.append("\n→ 지금까지 ${won(abs(diff))} " + (if (diff >= 0) "올라" else "내려") + " ${String.format("%.1f", times)}배")
            cagr(now.avg, p.avg, p.yearsAgo)?.let { sb.append(String.format(", 연평균 %s%.1f%%", if (it > 0) "+" else "", it)) }
            lines += sb.toString()
        }
        // 정리: 어느 구간에 더 많이 올랐는지
        val ok = past.filter { it.ok }
        if (ok.size >= 2) {
            val near = ok[0]; val far = ok[1]
            val recentRate = cagr(now.avg, near.avg, near.yearsAgo); val earlyRate = cagr(near.avg, far.avg, far.yearsAgo - near.yearsAgo)
            if (recentRate != null && earlyRate != null) {
                lines += "정리: 최근 ${near.yearsAgo}년(연 ${String.format("%+.1f", recentRate)}%)이 그 앞 ${far.yearsAgo - near.yearsAgo}년(연 ${String.format("%+.1f", earlyRate)}%)보다 " + (if (recentRate > earlyRate) "더 가파르게 올랐" else "덜 올랐") + e("음", "어요")
            }
        } else if (ok.size == 1) {
            val p = ok[0]
            lines += "정리: ${p.yearsAgo}년 동안 " + (if (now.avg >= p.avg) "${String.format("%.1f", now.avg.toDouble() / p.avg)}배가 됐" else "오히려 내렸") + e("음", "어요")
        }
        lines += "자료: 국토교통부 실거래가 (각 시점 앞뒤 1년 평균. 물가 반영 안 함)"
        return lines.joinToString("\n\n")
    }

    // ---------- 분양가 vs 실거래 ----------
    /** 청약홈 분양 공고 하나 (수집기 src/presale.js가 만든 presale.json의 항목). prices: 전용면적 정수 → (최저, 최고) 만원 */
    data class PresaleItem(val id: String, val name: String, val date: String, val movein: String, val lawd: List<String>, val addr: String, val prices: Map<Int, Pair<Long, Long>>) {
        /** 그 평형의 분양가: 해당 주택형들의 최저~최고 가운데 값. 없으면 null */
        fun price(band: Int): Long? {
            val p = prices.filterKeys { inBand(it.toDouble(), band) }.values
            if (p.isEmpty()) return null
            return (p.minOf { it.first } + p.maxOf { it.second }) / 2
        }
    }

    fun parsePresale(json: String): List<PresaleItem> {
        val arr = org.json.JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { k ->
            val o = arr.getJSONObject(k)
            val pr = o.optJSONObject("prices")
            val prices = pr?.keys()?.asSequence()?.mapNotNull { key -> key.toIntOrNull()?.let { a -> pr.optJSONArray(key)?.let { v -> a to (v.optLong(0) to v.optLong(1)) } } }?.toMap().orEmpty()
            val lawd = o.optJSONArray("lawd")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            PresaleItem(o.optString("id"), o.optString("name"), o.optString("date"), o.optString("movein"), lawd, o.optString("addr"), prices)
        }
    }

    /** 단지 이름 비교용: 괄호 안·띄어쓰기·'아파트'·단지 구분 기호를 뺌 */
    fun normName(s: String): String = s.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").replace(Regex("[\\s·,.-]"), "").replace("아파트", "")

    /** 분양 이름과 실거래 단지 이름이 같은 단지를 가리키는지 (한쪽이 다른 쪽을 품으면 같은 것으로 봄. 너무 짧은 이름은 정확히 같을 때만) */
    fun sameName(presale: String, apt: String): Boolean {
        val a = normName(presale); val b = normName(apt)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 5 && long.contains(short)
    }

    data class PresaleMatch(val item: PresaleItem, val aptNm: String, val umdNm: String, val buildYear: Int?, val presale: Long, val count: Int, val avg: Long, val last: Deal) {
        val diff: Long get() = avg - presale
        val pct: Double get() = if (presale > 0) diff * 100.0 / presale else 0.0
    }

    /**
     * 그 시군구의 분양 공고(items) 가운데, 최근 10년 안에 지어졌고 기간 안에 실거래가 있는 단지를 찾아 분양가와 비교.
     * deals: 그 시군구의 기간 안 매매 거래. 결과는 상승률 높은 순
     */
    fun presaleCompare(items: List<PresaleItem>, lawdCd: String, deals: List<Deal>, band: Int, thisYear: Int): List<PresaleMatch> {
        val complexes = deals.filter { it.type == "trade" && !it.canceled && inBand(it.area, band) }.groupBy { it.complexId }
            .filter { (_, ds) -> (ds.firstNotNullOfOrNull { it.buildYear } ?: thisYear) >= thisYear - 10 }
        val out = mutableListOf<PresaleMatch>()
        val used = HashSet<String>()
        // 같은 단지의 공고가 여러 번(잔여세대 등)이면 가장 이른 공고를 분양가로
        for (item in items.filter { lawdCd in it.lawd }.sortedBy { it.date }) {
            val price = item.price(band) ?: continue
            val hit = complexes.entries.firstOrNull { (id, ds) -> id !in used && sameName(item.name, ds.first().aptNm) } ?: continue
            used += hit.key
            val ds = hit.value.sortedByDescending { it.date }
            out += PresaleMatch(item, ds.first().aptNm, ds.first().umdNm, ds.firstNotNullOfOrNull { it.buildYear }, price, ds.size, avg(ds.map { it.price }), ds.first())
        }
        return out.sortedByDescending { it.pct }
    }

    fun presaleText(regionName: String, band: Int, period: DealPeriod, matches: List<PresaleMatch>, casual: Boolean): String {
        fun e(c: String, p: String) = if (casual) c else p
        val place = regionName.substringAfter(' ', regionName)
        val lines = mutableListOf<String>()
        lines += "$place 새 아파트, 분양가와 지금 실거래가 (${band}㎡)"
        if (matches.isEmpty()) {
            lines += "분양가 자료가 있고 최근 ${period.label} 안에 ${band}㎡ 실거래가 있는 10년 이내 단지가 없" + e("음", "어요")
            lines += "분양가 자료는 청약홈 최근 5년 공고만 있음"
            return lines.joinToString("\n\n")
        }
        val show = matches.take(5)
        lines += show.mapIndexed { k, m ->
            "${k + 1}. ${m.aptNm} (${m.item.date.take(4)}년 분양)\n분양가 ${won(m.presale)} → 실거래 ${won(m.avg)} (${String.format("%+.0f%%", m.pct)}, ${won(abs(m.diff))} ${if (m.diff >= 0) "올라" else "내려"})"
        }.joinToString("\n") + (if (matches.size > show.size) "\n외 ${matches.size - show.size}곳" else "")
        val up = matches.count { it.diff > 0 }
        val mean = matches.map { it.pct }.average()
        var sum = "정리: ${matches.size}곳 중 ${up}곳이 분양가보다 비싸게 거래, 평균 ${String.format("%+.0f%%", mean)}"
        if (matches.size - up > 0) sum += "\n분양가 아래로 거래된 곳도 ${matches.size - up}곳"
        lines += sum
        lines += "자료: 청약홈 분양가(주택형 중간값), 국토부 실거래가 최근 ${period.label} 평균"
        return lines.joinToString("\n\n")
    }
}
