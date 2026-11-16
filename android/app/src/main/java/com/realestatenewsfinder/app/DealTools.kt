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
    fun budget(deals: List<Deal>, band: Int, budget: Long, aptOnly: Boolean = false, extra: List<Deal> = emptyList(), guOf: Map<String, String> = emptyMap()): BudgetResult {
        val known = (deals + extra).filter { !it.canceled }.distinctBy { it.key }.groupBy { it.complexId }
        var excluded = 0
        val all = deals.filter { it.type == "trade" && !it.canceled && inBand(it.area, band) }.groupBy { it.complexId }.map { (id, ds) ->
            val sorted = ds.sortedByDescending { it.date }
            val prices = ds.map { it.price }
            BudgetItem(id, sorted.first().aptNm, listOfNotNull(guOf[id], sorted.first().umdNm.takeIf { it.isNotBlank() }).joinToString(" "), ds.firstNotNullOfOrNull { it.buildYear }, ds.size, avg(prices), prices.min(), prices.max(), sorted.first())
        }.filter { i -> (!aptOnly || looksLikeApartment(i.aptNm, known[i.complexId].orEmpty())).also { ok -> if (!ok) excluded++ } }
        return BudgetResult(all.filter { it.avg <= budget }.sortedByDescending { it.avg }, all.filter { it.avg > budget }.sortedBy { it.avg }, excluded)
    }

    private val BUDGET_ASK = listOf("이 중에 어디가 제일 좋아 보여?", "마음에 드는 단지 있어?", "여기서 하나만 고른다면 어디?", "나라면 몇 번 고를지 댓글로 알려줘", "살아본 곳 있으면 후기 좀 알려줘", "예산 같으면 어디로 갈 것 같아?")
    private val BUDGET_ASK_POLITE = listOf("이 중에 어디가 제일 좋아 보이나요?", "마음에 드는 단지 있으세요?", "여기서 하나만 고른다면 어디인가요?", "몇 번을 고르실지 댓글로 알려주세요", "살아보신 곳 있으면 후기 부탁드려요", "같은 예산이면 어디로 가시겠어요?")

    /**
     * 짧은 글: 지역·예산 한 줄, 단지 이름만 번호 붙여 나열, 마지막에 한마디(seed에 따라 바뀜).
     * label: "인천 미추홀구" 또는 "서울"
     */
    fun budgetText(label: String, band: Int, budget: Long, r: BudgetResult, casual: Boolean, seed: Int = 0): String {
        fun e(c: String, p: String) = if (casual) c else p
        val lines = mutableListOf<String>()
        lines += "$label ${won(budget)}으로 살 수 있는 ${band}㎡ 아파트"
        if (r.total == 0) { lines += "최근 거래된 ${band}㎡ 아파트가 없" + e("음", "어요"); return lines.joinToString("\n\n") }
        if (r.items.isEmpty()) {
            val c = r.over.first()
            lines += "이 예산으로는 살 수 있는 곳이 없" + e("음", "어요") + "\n가장 싼 곳이 ${c.aptNm} ${won(c.avg)}"
            return lines.joinToString("\n\n")
        }
        val show = r.items.take(10)
        lines += show.mapIndexed { k, i -> "${k + 1}. ${i.aptNm}" }.joinToString("\n") + (if (r.items.size > show.size) "\n외 ${r.items.size - show.size}곳" else "")
        val asks = if (casual) BUDGET_ASK else BUDGET_ASK_POLITE
        lines += asks[Math.floorMod(seed, asks.size)]
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

    /** 참가격 외식비 품목 이름 → 글에 쓰는 표현 */
    private val DINE_LABEL = mapOf(
        "자장면" to "짜장면 한 그릇", "냉면" to "냉면 한 그릇", "비빔밥" to "비빔밥 한 그릇", "김치찌개백반" to "김치찌개 백반",
        "삼겹살" to "삼겹살 1인분", "삼계탕" to "삼계탕 한 그릇", "칼국수" to "칼국수 한 그릇", "김밥" to "김밥 한 줄",
    )

    /** 물가 비교 한 가지: 그때와 지금의 외식비 */
    data class PriceCompare(val point: HistoryPoint, val item: String, val then: Int, val now: Int) {
        val pct: Double get() = (now - then) * 100.0 / then
        /** "10년 사이 짜장면 한 그릇 4,800원 → 7,654원 (+59%)" */
        val line: String get() = "${point.yearsAgo}년 사이 $item ${"%,d".format(then)}원 → ${"%,d".format(now)}원 (${String.format("%+.0f%%", pct)})"
    }

    /**
     * 집값과 견줄 물가 하나를 고름 (seed에 따라 품목이 바뀜).
     * dineout: 품목 → (연도 → 원). 가장 오래된 시점부터, 그 해 자료가 있는 품목 가운데서 고름 (자료는 2009년부터)
     */
    fun priceCompare(points: List<HistoryPoint>, dineout: Map<String, Map<Int, Int>>, thisYear: Int, seed: Int = 0): PriceCompare? {
        for (p in points.filter { it.ok && it.yearsAgo > 0 }.sortedByDescending { it.yearsAgo }) {
            val year = thisYear - p.yearsAgo
            val usable = dineout.entries.mapNotNull { (name, byYear) ->
                val then = byYear[year] ?: return@mapNotNull null
                val now = byYear[thisYear] ?: byYear[thisYear - 1] ?: return@mapNotNull null
                PriceCompare(p, DINE_LABEL[name] ?: name, then, now)
            }.sortedBy { it.item }
            if (usable.isNotEmpty()) return usable[Math.floorMod(seed, usable.size)]
        }
        return null
    }

    /** 짧은 글: 시점별 금액만 나열하고, 물가 한 가지와 견줘 한 줄 */
    fun historyText(aptNm: String, place: String, areaKey: Int, points: List<HistoryPoint>, casual: Boolean, compare: PriceCompare? = null): String {
        fun e(c: String, p: String) = if (casual) c else p
        val now = points.firstOrNull { it.yearsAgo == 0 }
        val lines = mutableListOf<String>()
        lines += "$aptNm ${areaKey}㎡ 실거래가 ($place)"
        if (now == null || !now.ok) {
            lines += "최근 1년 동안 ${areaKey}㎡ 매매 실거래가 없어서 비교할 수 없" + e("음", "어요")
            return lines.joinToString("\n\n")
        }
        val past = points.filter { it.yearsAgo > 0 && it.ok }.sortedByDescending { it.yearsAgo }
        if (past.isEmpty()) {
            lines += "지금 ${won(now.avg)}\n비교할 과거 거래 기록이 없" + e("음", "어요")
            return lines.joinToString("\n\n")
        }
        lines += (past.map { "${it.label} ${won(it.avg)}" } + "지금 ${won(now.avg)}").joinToString("\n")
        fun housePct(p: HistoryPoint) = (now.avg - p.avg) * 100.0 / p.avg
        fun times(p: HistoryPoint) = String.format("%.1f", now.avg.toDouble() / p.avg)
        if (compare != null) {
            val p = compare.point
            val h = housePct(p)
            lines += compare.line + "\n같은 기간 이 집은 ${String.format("%+.0f%%", h)}" + (if (h > 0) " (${times(p)}배)" else "")
        } else {
            val p = past.first()
            lines += "${p.yearsAgo}년 사이 ${String.format("%+.0f%%", housePct(p))}" + (if (now.avg > p.avg) " (${times(p)}배)" else "")
        }
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
     * deals: 그 시군구의 기간 안 매매 거래. 결과는 오른 금액이 큰 순
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
        return out.sortedByDescending { it.diff }
    }

    /** 분양가 대비 얼마나 올랐는지에 따른 한마디 (여러 개 중 seed로 고름 → 매번 같은 말이 되지 않게) */
    private fun presaleComment(m: PresaleMatch, pick: Int): String {
        val gain = won(abs(m.diff))
        val list = when {
            m.pct >= 100 -> listOf("분양가의 두 배를 넘김. 이건 진짜 미친 상승", "분양 때 비싸다고 망설였다면 땅을 칠 숫자", "청약 당첨이 곧 로또였던 단지", "분양가만큼이 그대로 한 번 더 붙음", "그때 넣을까 말까 고민한 사람은 속 쓰릴 결과")
            m.pct >= 50 -> listOf("분양가 비싸다는 말이 쏙 들어갈 상승폭", "당첨자는 앉아서 $gain 번 셈", "고분양가 걱정이 무색해진 결과", "웬만한 직장인 평생 저축이 몇 년 만에 붙음", "청약 경쟁률이 괜히 높았던 게 아님")
            m.pct >= 20 -> listOf("분양가 대비 꾸준히 오른 편", "당첨됐다면 꽤 쏠쏠한 수익", "입주 프리미엄이 확실히 붙은 단지", "$gain 차이면 청약 넣을 이유는 충분했음")
            m.pct >= 0 -> listOf("분양가에서 크게 벗어나지 않은 수준", "프리미엄은 아직 얇은 편", "오르긴 했지만 금융비용 생각하면 애매한 폭", "분양가가 곧 시세였던 셈")
            else -> listOf("분양가보다 싸게 거래되는 중. 이른바 마피 구간", "분양가를 못 지키고 있는 단지", "고분양가 논란이 현실이 된 사례", "분양받은 사람은 $gain 손해를 보고 있는 셈")
        }
        return list[Math.floorMod(pick, list.size)]
    }

    /**
     * 금액 중심의 짧은 글: "2024년 5월 서초구에 분양됐던 OO 59㎡, 최근 실거래 39억 … 찍힘 / 분양가 17억 … / 한마디".
     * 오른 금액이 큰 순으로 3곳까지. seed에 따라 한마디가 바뀜
     */
    fun presaleText(place: String, band: Int, period: DealPeriod, matches: List<PresaleMatch>, casual: Boolean, seed: Int = 0): String {
        fun e(c: String, p: String) = if (casual) c else p
        if (matches.isEmpty()) {
            return "$place ${band}㎡, 분양가와 견줄 단지가 없" + e("음", "어요") + "\n\n분양가 자료(청약홈 최근 5년 공고)가 있고 최근 ${period.label} 안에 실거래가 있는 10년 이내 단지만 비교함"
        }
        val lines = mutableListOf<String>()
        matches.take(3).forEachIndexed { k, m ->
            val ym = m.item.date.split('-').let { d -> if (d.size >= 2) "${d[0]}년 ${d[1].toInt()}월" else m.item.date.take(4) + "년" }
            val head = "$ym ${place}에 분양됐던 ${m.aptNm} ${band}㎡"
            val move = if (m.diff >= 0) "${won(m.diff)} 오름" else "${won(abs(m.diff))} 내림"
            lines += "$head\n최근 실거래 ${won(m.avg)} " + e("찍힘", "찍혔어요") + "\n분양가는 ${won(m.presale)} → $move\n${presaleComment(m, seed + k * 7 + m.aptNm.length)}"
        }
        if (matches.size > 3) lines += "외 ${matches.size - 3}곳"
        return lines.joinToString("\n\n")
    }
}
