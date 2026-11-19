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

    /**
     * 예산별 단지 목록형 글:
     *   [금액]만 모아보자. / [지역]에서 [금액]으로 살 수 있는 [평형] 아파트 TOP N / 1~5. 단지 (평형, 가격) / 저장해두고 임장 가봐. 너는 몇 번이 제일 끌려?
     * label: "인천 미추홀구" 또는 "서울". 가격은 단지별 실거래 평균. seed에 따라 첫 줄 표현이 바뀜
     */
    fun budgetText(label: String, band: Int, budget: Long, r: BudgetResult, casual: Boolean, seed: Int = 0): String {
        fun e(c: String, p: String) = if (casual) c else p
        val money = shortWon(budget)
        if (r.total == 0) return "$label ${band}㎡, 최근 거래된 아파트가 없" + e("음", "어요")
        if (r.items.isEmpty()) {
            val c = r.over.first()
            return "$label ${band}㎡ 아파트, ${money}으로는 아직 없" + e("음", "어요") + "\n\n가장 싼 곳이 ${c.aptNm} (${band}㎡, ${shortWon(c.avg)})"
        }
        val show = r.items.take(5)
        val hooks = if (casual) listOf("${money}만 모아보자.", "${money}으로 어디까지 갈 수 있을까.", "예산 $money, 선택지는 이만큼 있어.")
        else listOf("${money}만 모아봐요.", "${money}으로 어디까지 갈 수 있을까요.", "예산 $money, 선택지는 이만큼 있어요.")
        val who = if (band <= 60) "신혼부부나 1~2인 가구라면" else "아이 있는 3~4인 가족이라면"
        val lines = mutableListOf<String>()
        lines += hooks[Math.floorMod(seed, hooks.size)] + "\n${label}에서 ${money}으로 살 수 있는 ${band}㎡ 아파트 TOP ${show.size}"
        lines += show.mapIndexed { k, i -> "${k + 1}. ${i.aptNm} (${band}㎡, ${shortWon(i.avg)})" }.joinToString("\n")
        lines += if (casual) "$who 저장해두고 임장 가봐.\n너는 몇 번이 제일 끌려?" else "$who 저장해두고 임장 가보세요.\n몇 번이 제일 끌리세요?"
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

    /**
     * 맨 밑 한줄평: 가장 오래된 시점 대비 몇 배가 됐는지, 최근에 속도가 꺾였는지에 따라 (여러 표현 중 seed로 고름)
     * past: 값이 있는 과거 시점들
     */
    fun historyVerdict(now: HistoryPoint, past: List<HistoryPoint>, seed: Int = 0): String? {
        val old = past.filter { it.ok }.maxByOrNull { it.yearsAgo } ?: return null
        val near = past.filter { it.ok }.minByOrNull { it.yearsAgo }!!
        val n = old.yearsAgo
        val t = now.avg.toDouble() / old.avg
        val times = String.format("%.1f", t)
        // 오래전부터는 많이 올랐는데 최근 구간은 거의 안 오른 경우
        val recent = cagr(now.avg, near.avg, near.yearsAgo)
        val stalled = near !== old && t >= 1.5 && recent != null && recent < 2.0
        val list = when {
            now.avg < near.avg && near !== old && t >= 1.3 -> listOf("길게 보면 올랐는데 ${near.yearsAgo}년 전에 산 사람은 아직 물려 있음", "${n}년 전 가격은 까마득한데 ${near.yearsAgo}년 전 고점은 아직 못 넘음", "오래 든 사람만 웃고 있는 단지")
            stalled -> listOf("많이 오르긴 했는데 최근 ${near.yearsAgo}년은 숨 고르는 중", "오를 만큼 오르고 지금은 쉬어가는 구간", "예전에 다 오르고 요즘은 제자리걸음")
            t >= 3 -> listOf("${n}년 묵혔더니 ${times}배. 이래서 다들 버티는구나", "${n}년 전에 샀다면 지금은 다른 세상에 살고 있을 단지", "월급 모아서는 평생 못 따라갈 속도")
            t >= 2 -> listOf("${n}년에 ${times}배. 월급으로는 못 따라갈 속도", "두 배가 되는 데 ${n}년이면 충분했음", "오를 곳은 오른다는 말의 표본")
            t >= 1.3 -> listOf("크게 튀진 않아도 꾸준히 올라온 단지", "화려하진 않은데 손해 본 사람은 없는 흐름", "${n}년 들고 있으면 배신하지 않은 집")
            t >= 1.0 -> listOf("${n}년 지났는데 거의 제자리. 물가 생각하면 사실상 내린 셈", "오르긴 했는데 은행 이자만도 못한 수준", "버틴 시간에 비해 얻은 게 적은 단지")
            else -> listOf("${n}년 전보다 오히려 싸짐. 집값도 떨어진다는 증거", "산 사람보다 기다린 사람이 이긴 단지", "${n}년 전 가격을 아직 못 되찾음")
        }
        return list[Math.floorMod(seed, list.size)]
    }

    /**
     * 과거 대비 현재 비교형 글:
     *   [지역] 아파트 [과거 연도] vs [현재 연도] 가격 비교 / [단지] [평형] / 연도: 가격 … / 안 산 게 아니라 못 산 시간이… / 그런데 진짜 중요한 건 이거야. → [한줄 해석]
     * thisYear: 올해. compare(물가 비교)는 더 쓰지 않음(인위적이라 뺌) — 인자는 호환을 위해 남김
     */
    @Suppress("UNUSED_PARAMETER")
    fun historyText(aptNm: String, place: String, areaKey: Int, points: List<HistoryPoint>, casual: Boolean, compare: PriceCompare? = null, seed: Int = 0, thisYear: Int = LocalDate.now(KST).year): String {
        fun e(c: String, p: String) = if (casual) c else p
        val now = points.firstOrNull { it.yearsAgo == 0 }
        if (now == null || !now.ok) return "$aptNm ${areaKey}㎡ ($place)\n\n최근 1년 동안 매매 실거래가 없어서 비교할 수 없" + e("음", "어요")
        val past = points.filter { it.yearsAgo > 0 && it.ok }.sortedByDescending { it.yearsAgo }
        if (past.isEmpty()) return "$aptNm ${areaKey}㎡ ($place)\n\n${thisYear}년: ${won(now.avg)}\n비교할 과거 거래 기록이 없" + e("음", "어요")
        val old = past.first()
        val lines = mutableListOf<String>()
        lines += "$place 아파트 ${thisYear - old.yearsAgo}년 vs ${thisYear}년 가격 비교"
        lines += "$aptNm ${areaKey}㎡\n" + (past.map { "${thisYear - it.yearsAgo}년: ${won(it.avg)}" } + "${thisYear}년: ${won(now.avg)}").joinToString("\n")
        val rose = now.avg > old.avg
        val gapLine = if (rose) {
            if (casual) listOf("안 산 게 아니라 못 산 시간이 이렇게 큰 차이를 만들었어.", "그때 망설인 ${old.yearsAgo}년이 이만큼의 차이가 됐어.", "${old.yearsAgo}년 사이에 ${shortWon(now.avg - old.avg)}이 벌어졌어.")
            else listOf("안 산 게 아니라 못 산 시간이 이렇게 큰 차이를 만들었어요.", "그때 망설인 ${old.yearsAgo}년이 이만큼의 차이가 됐어요.", "${old.yearsAgo}년 사이에 ${shortWon(now.avg - old.avg)}이 벌어졌어요.")
        } else {
            if (casual) listOf("${old.yearsAgo}년을 들고 있었는데 오히려 내렸어.", "이번엔 기다린 사람이 이긴 경우야.")
            else listOf("${old.yearsAgo}년을 들고 있었는데 오히려 내렸어요.", "이번엔 기다린 사람이 이긴 경우예요.")
        }
        val verdict = historyVerdict(now, past, seed)
        lines += gapLine[Math.floorMod(seed, gapLine.size)] + (verdict?.let { "\n" + e("그런데 진짜 중요한 건 이거야.", "그런데 진짜 중요한 건 이거예요.") + "\n→ $it" } ?: "")
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

    /** 글에 쓰는 짧은 금액: 3.3억 / 4,385만원 */
    fun shortWon(v: Long): String = if (v >= 10000) String.format("%.1f", v / 10000.0).removeSuffix(".0") + "억" else "%,d만원".format(v)

    /** 분양 공고일부터 지금까지: "3년 만에" / "8개월 만에" */
    fun since(date: String, today: LocalDate): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return "분양 뒤"
        val months = ((today.year - d.year) * 12 + (today.monthValue - d.monthValue)).coerceAtLeast(1)
        return if (months >= 12) "${months / 12}년 만에" else "${months}개월 만에"
    }

    /**
     * 짧은 글: 맨 위 한마디(전체 흐름) → 단지 3곳(이름·평형 / N년 만에 분양가 대비 얼마) → 마지막 줄 전체 정리.
     * 단지는 오른 금액이 큰 순으로 3곳만. label: "부산" 또는 "인천 미추홀구". seed에 따라 한마디·정리 표현이 바뀜
     */
    fun presaleText(label: String, band: Int, period: DealPeriod, matches: List<PresaleMatch>, casual: Boolean, seed: Int = 0, today: LocalDate = LocalDate.now(KST)): String {
        fun e(c: String, p: String) = if (casual) c else p
        if (matches.isEmpty()) {
            return "$label ${band}㎡, 분양가와 견줄 단지가 없" + e("음", "어요") + "\n\n분양가 자료(청약홈 최근 5년 공고)가 있고 최근 ${period.label} 안에 실거래가 있는 10년 이내 단지만 비교함"
        }
        fun pick(list: List<String>, salt: Int = 0) = list[Math.floorMod(seed + salt, list.size)]
        val up = matches.count { it.diff > 0 }
        val down = matches.size - up
        val mean = matches.map { it.diff }.average().roundToLong()
        val meanPct = matches.map { it.pct }.average()
        val city = label.substringBefore(' ')
        // 맨 위 한마디: 전체 흐름에 따라
        val head = when {
            down == 0 && meanPct >= 50 -> pick(listOf("$label 새 아파트, 청약 당첨이 곧 로또였네", "$label 분양받은 사람은 전부 웃고 있음", "$label 분양가, 지금 보면 다 헐값이었네"))
            down == 0 -> pick(listOf("$label 새 아파트, 분양가 밑으로 간 곳이 없네", "$label 분양, 넣기만 했으면 남는 장사였음", "$label 부동산도 생각보다 탄탄하네"))
            up >= down * 2 -> pick(listOf("$label 분양, 대체로 남는 장사였네", "$label 새 아파트는 대부분 분양가 위에서 거래 중", "$label 청약, 열에 일곱은 웃었음"))
            up >= down -> pick(listOf("$label 분양, 단지 따라 웃고 울었네", "같은 ${label}인데 분양 성적표는 극과 극", "$label 새 아파트, 오른 곳만 오름"))
            up > 0 -> pick(listOf("$label 분양, 오른 단지가 오히려 귀함", "$label 새 아파트, 분양가 지키기도 벅참", "$label 분양가가 천장이었던 단지가 더 많음"))
            else -> pick(listOf("$label 새 아파트, 분양가를 지킨 곳이 없네", "$label 분양받은 사람은 지금 속 쓰릴 듯", "$label, 분양가가 곧 최고가였음"))
        }
        val lines = mutableListOf(head)
        lines += matches.take(3).joinToString("\n\n") { m ->
            val move = if (m.diff >= 0) "${shortWon(m.diff)} 오름" else "${shortWon(abs(m.diff))} 내림"
            "$city ${m.aptNm} ${band}㎡\n${since(m.item.date, today)} 분양가 대비 $move"
        }
        // 마지막 줄: 전체 정리 (숫자 사실 + 짧은 평가)
        val fact = if (matches.size <= 3) "비교한 ${matches.size}곳 평균 " + (if (mean >= 0) "${shortWon(mean)} 오름" else "${shortWon(abs(mean))} 내림")
        else "비교한 ${matches.size}곳 중 ${up}곳이 분양가보다 올랐고, 평균은 " + (if (mean >= 0) "${shortWon(mean)} 오름" else "${shortWon(abs(mean))} 내림")
        val verdict = when {
            down == 0 -> pick(listOf("새 아파트 선호가 가격으로 확인된 셈", "분양가가 싸 보일 만큼 시세가 올라온 지역", "청약 경쟁이 치열했던 이유가 숫자로 보임"), 1)
            up >= down && mean < 0 -> pick(listOf("오른 단지보다 내린 단지의 낙폭이 더 컸음", "잘된 곳만 보고 전체를 판단하면 안 되는 시장", "평균으로 보면 분양가를 못 지킨 셈"), 1)
            up >= down -> pick(listOf("입지 좋은 단지 위주로 프리미엄이 붙는 흐름", "오르긴 했는데 단지별 차이가 큼", "분양가보다 어디에 짓느냐가 갈랐음"), 1)
            else -> pick(listOf("분양가가 시세보다 앞서갔던 지역", "새 아파트라고 다 오르는 건 아니라는 증거", "분양가 부담이 시세에 그대로 남아 있음"), 1)
        }
        lines += "$fact\n$verdict"
        return lines.joinToString("\n\n")
    }

    // ---------- 지역별 최고 실거래 단지 ----------
    /** 고를 수 있는 평형: 0 = 전체 */
    val TOP_BANDS = listOf(0, 59, 84)

    /** 단지 하나의 기간 내 최고가 거래 */
    data class TopItem(val deal: Deal, val gu: String = "") {
        val price: Long get() = deal.price
    }

    /**
     * 거래들에서 단지별 최고가 거래를 뽑아 비싼 순으로 n곳. band가 0이면 평형 구분 없이, 59·84면 그 평형만.
     * 해제된 거래는 뺌. 많은 달을 나눠 받을 때는 지금까지의 결과(prev)와 합쳐 가며 호출 → 거래 전체를 들고 있지 않아도 됨
     */
    fun topDeals(deals: List<Deal>, band: Int, n: Int, prev: List<TopItem> = emptyList(), gu: String = ""): List<TopItem> {
        val best = HashMap<String, TopItem>()
        for (t in prev) best[t.deal.complexId] = t
        for (d in deals) {
            if (d.type != "trade" || d.canceled || d.price <= 0) continue
            if (band != 0 && !inBand(d.area, band)) continue
            val cur = best[d.complexId]
            if (cur == null || d.price > cur.price) best[d.complexId] = TopItem(d, gu)
        }
        return best.values.sortedByDescending { it.price }.take(n)
    }

    /**
     * 짧은 글: 맨 위 한마디와 기준 기간 → 1~10위 단지와 최고가 → 한 줄 → 기간 밖 거래 안내. label: "경기" 또는 "서울 강남구"
     */
    fun topText(label: String, band: Int, period: DealPeriod, items: List<TopItem>, casual: Boolean, seed: Int = 0): String {
        fun e(c: String, p: String) = if (casual) c else p
        val what = if (band == 0) "" else " ${band}㎡"
        if (items.isEmpty()) return "$label$what, 최근 ${period.label} 동안 매매 실거래가 없" + e("음", "어요")
        fun pick(list: List<String>, salt: Int = 0) = list[Math.floorMod(seed + salt, list.size)]
        val lines = mutableListOf<String>()
        lines += pick(listOf("$label$what 대장 아파트, 실거래가로 줄 세워봄", "$label$what 에서 제일 비싸게 팔린 아파트 순위", "$label$what 집값 꼭대기에는 누가 있을까")).replace("$what 에서", "${what}에서") +
            "\n최근 ${period.label}간 실거래가 기준"
        val show = items.take(10)
        lines += show.mapIndexed { k, t -> "${k + 1}. ${t.deal.aptNm} ${shortWon(t.price)}" + (if (band == 0) " (${t.deal.area.toInt()}㎡)" else "") }.joinToString("\n")
        if (show.size >= 3) {
            val gap = show.first().price - show.last().price
            val tail = if (casual) listOf("같은 지역 맞나 싶은 격차", "순위표만 봐도 어디가 대장인지 보임", "이 중에 살아보고 싶은 곳 있어?", "1위는 당분간 안 바뀔 듯")
            else listOf("같은 지역이 맞나 싶은 격차예요", "순위표만 봐도 어디가 대장인지 보여요", "이 중에 살아보고 싶은 곳 있으세요?", "1위는 당분간 안 바뀔 것 같아요")
            lines += "1위와 ${show.size}위 차이 ${shortWon(gap)}\n" + pick(tail, 1)
        }
        // 기간 밖의 거래는 순위에 없음을 밝힘
        lines += "※ ${period.label} 이전에 거래된 더 높은 가격이 있을 수 있" + e("음", "어요")
        return lines.joinToString("\n\n")
    }
}
