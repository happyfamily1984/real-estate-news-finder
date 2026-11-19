package com.realestatenewsfinder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

data class StatRegion(val name: String, val values: List<Double?>) {
    val latest: Double? get() = values.lastOrNull()
    val prev: Double? get() = values.getOrNull(values.size - 2)
}

/**
 * 통계 지표 하나 (클라우드 수집기 src/stats.js가 만든 stats.json의 series 항목).
 * kind: change = 변동률(%), level = 지수·배수.  base: 기준선(예: 100)
 */
data class StatSeries(
    val id: String, val title: String, val short: String, val unit: String, val digits: Int, val cycle: String,
    val kind: String, val base: Double?, val desc: String, val source: String,
    /** 카드 아래 자료 표기 (예: 한국부동산원 R-ONE 주간아파트가격동향) */
    val note: String,
    /** 기준일 "2026-11-09" 또는 "2026-06" */
    val date: String, val dates: List<String>, val regions: List<StatRegion>,
) {
    fun region(name: String): StatRegion? = regions.firstOrNull { it.name == name }

    /** 숫자 표기: 변동률은 부호를 붙임 (+0.07%) */
    fun fmt(v: Double?, sign: Boolean = kind == "change"): String {
        if (v == null) return "-"
        val x = if (abs(v) < 0.5 / Math.pow(10.0, digits.toDouble())) 0.0 else v // -0.00 방지
        val s = String.format("%.${digits}f", x)
        return (if (sign && x > 0) "+" else "") + s + unit
    }

    /** "11/9" 또는 "2026년 6월" */
    val dateLabel: String get() = date.split('-').let { p ->
        if (p.size == 3) "${p[1].toInt()}/${p[2].toInt()}" else if (p.size == 2) "${p[0]}년 ${p[1].toInt()}월" else date
    }
}

data class Stats(
    val updatedAt: Long, val sido: List<String>, val series: List<StatSeries>,
    /** 하위 지역 → 상위 지역들 (가까운 순). 예: "서울 강남구" → [서울 동남권, 서울 강남, 서울] */
    val parents: Map<String, List<String>> = emptyMap(),
    /** 물가 비교용 외식비 (참가격, 서울): 품목 → (연도 → 원) */
    val dineout: Map<String, Map<Int, Int>> = emptyMap(),
) {
    /** 시도 아래의 지역들: 시군구 먼저(가나다순), 권역은 뒤에 */
    fun childrenOf(sido: String): List<String> =
        parents.filter { it.value.lastOrNull() == sido }.keys.sortedWith(compareBy<String> { isZone(it) }.thenBy { it })

    /** 지역 자신 → 상위 지역 순서 */
    fun chain(region: String): List<String> = listOf(region) + parents[region].orEmpty()

    /** 이 지역의 시도 (맨 위 단계 지역이면 자신) */
    fun sidoOf(region: String): String = parents[region]?.lastOrNull() ?: region

    /** 이 지역(없으면 가장 가까운 상위 지역)의 값이 있는 지표: (지표, 실제로 값이 있는 지역 이름) */
    fun seriesFor(region: String): List<Pair<StatSeries, String>> =
        series.mapNotNull { s -> chain(region).firstOrNull { s.region(it)?.latest != null }?.let { s to it } }

    /** 순위 비교 대상: 같은 시도의 시군구(구가 딸린 시의 하위 구와 권역은 제외). 시도를 고르면 그 아래 시군구 */
    fun siblings(region: String): List<String> {
        val sd = sidoOf(region)
        return childrenOf(sd).filter { !isZone(it) && it.removePrefix("$sd ").count { c -> c == ' ' } == 0 }
    }

    /** 화면·이미지에 쓰는 짧은 이름: "서울 강남구" → "강남구" (시도 이름과 같으면 그대로) */
    fun shortName(region: String): String = region.removePrefix(sidoOf(region) + " ").ifBlank { region }

    companion object {
        /** 권역(동남권·경부1권, 서울 강북·강남)인지 */
        fun isZone(region: String) = region.endsWith("권") || region == "서울 강북" || region == "서울 강남"

        private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getString(i) } }.orEmpty()

        fun parse(json: String): Stats {
            val o = JSONObject(json)
            val arr = o.getJSONArray("series")
            val series = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val rs = s.getJSONArray("regions")
                StatSeries(
                    id = s.getString("id"), title = s.getString("title"), short = s.optString("short", s.getString("title")),
                    unit = s.optString("unit"), digits = s.optInt("digits", 1), cycle = s.optString("cycle"),
                    kind = s.optString("kind", "level"), base = if (s.has("base")) s.optDouble("base") else null,
                    desc = s.optString("desc"), source = s.optString("source"), note = s.optString("note").ifBlank { s.optString("source") }, date = s.optString("date"), dates = strings(s.optJSONArray("dates")),
                    regions = (0 until rs.length()).map { j ->
                        val r = rs.getJSONObject(j)
                        val v = r.getJSONArray("values")
                        StatRegion(r.getString("name"), (0 until v.length()).map { k -> if (v.isNull(k)) null else v.getDouble(k) })
                    },
                )
            }
            val parents = o.optJSONObject("parents")?.let { p -> p.keys().asSequence().associateWith { k -> strings(p.optJSONArray(k)) } }.orEmpty()
            val dine = o.optJSONObject("dineout")?.optJSONObject("items")?.let { items ->
                items.keys().asSequence().associateWith { name ->
                    val by = items.getJSONObject(name)
                    by.keys().asSequence().mapNotNull { y -> y.toIntOrNull()?.let { it to by.optInt(y) } }.filter { it.second > 0 }.toMap()
                }
            }.orEmpty()
            return Stats(o.optLong("checkedAt", o.optLong("updatedAt", 0)), strings(o.optJSONArray("sido")), series, parents, dine)
        }
    }
}

object StatsRepository {
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "stats.json")

    /** 피드 주소의 posts.json 자리에 stats.json */
    fun urlOf(feedUrl: String) = feedUrl.substringBeforeLast('/') + "/stats.json"

    suspend fun fetch(ctx: Context): Stats = withContext(Dispatchers.IO) {
        val conn = URL("${urlOf(Prefs(ctx).feedUrl)}?t=${System.currentTimeMillis() / 600000}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        try {
            if (conn.responseCode != 200) throw Exception(if (conn.responseCode == 404) "아직 통계 자료가 없어요 (수집기가 아직 통계를 만들지 않음)" else "서버 응답 ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val stats = Stats.parse(text)
            cacheFile(ctx).writeText(text)
            stats
        } finally {
            conn.disconnect()
        }
    }

    fun cached(ctx: Context): Stats? = runCatching { Stats.parse(cacheFile(ctx).readText()) }.getOrNull()
}

/** 숫자 흐름을 읽는 도구 (연속 상승·하락, 기간 내 위치, 평균과 비교) */
object Insight {
    /** 끝에서부터 같은 부호(+/-)가 몇 번 이어졌는지. 부호 포함 (+3 = 3회 연속 플러스, -2 = 2회 연속 마이너스, 0 = 최근 값이 0이거나 없음) */
    fun signStreak(values: List<Double?>, eps: Double = 1e-9): Int {
        val last = values.lastOrNull() ?: return 0
        val sign = if (last > eps) 1 else if (last < -eps) -1 else return 0
        var n = 0
        for (v in values.asReversed()) { if (v != null && v * sign > eps) n++ else break }
        return n * sign
    }

    /** 끝에서부터 직전 값보다 계속 오른(+) / 내린(-) 횟수 */
    fun moveStreak(values: List<Double?>, eps: Double = 1e-9): Int {
        val v = values.filterNotNull()
        if (v.size < 2) return 0
        val sign = if (v[v.size - 1] - v[v.size - 2] > eps) 1 else if (v[v.size - 1] - v[v.size - 2] < -eps) -1 else return 0
        var n = 0
        for (k in v.size - 1 downTo 1) { if ((v[k] - v[k - 1]) * sign > eps) n++ else break }
        return n * sign
    }

    /** 최신 값이 기간 안에서 가장 크면 1, 가장 작으면 -1, 아니면 0 (값이 4개 이상이고 단독일 때만) */
    fun extreme(values: List<Double?>): Int {
        val v = values.filterNotNull()
        if (v.size < 4) return 0
        val last = v.last(); val rest = v.dropLast(1)
        return if (rest.all { it < last }) 1 else if (rest.all { it > last }) -1 else 0
    }

    /** 최신 값을 뺀 직전 n개 평균 */
    fun prevAvg(values: List<Double?>, n: Int): Double? {
        val v = values.filterNotNull().dropLast(1).takeLast(n)
        return if (v.size == n) v.average() else null
    }
}

/**
 * 통계를 스레드 글로. AI를 쓰지 않고 숫자를 그대로 문장에 끼워 넣어서 숫자가 틀릴 일이 없음.
 * 숫자만 나열하지 않고 흐름을 읽어 줌: 연속 상승·하락, 최근 평균 대비 속도, 기간 내 최고·최저, 지역 간 격차, 누적 변동, 한 줄 정리.
 */
object StatText {
    private fun ranked(s: StatSeries, sido: List<String>) =
        s.regions.filter { it.name in sido && it.latest != null }.sortedByDescending { it.latest!! }

    /** 주기 낱말: 날짜가 연-월-일이면 "주", 연-월이면 "개월" */
    private fun unit(s: StatSeries) = if (s.date.count { it == '-' } == 2) "주" else "개월"
    private fun num(s: StatSeries, v: Double, digits: Int = s.digits) = String.format("%.${digits}f", v)
    private fun eps(s: StatSeries) = 0.5 / Math.pow(10.0, s.digits.toDouble())

    fun thread(s: StatSeries, sido: List<String>, casual: Boolean): String {
        val nation = s.region("전국")
        val head = nation ?: s.region("서울") ?: s.regions.first()
        return when {
            s.kind == "change" -> changeThread(s, head, sido, casual)
            s.id.contains("pir") -> pirThread(s, head, casual)
            else -> levelThread(s, head, sido, casual)
        }.joinToString("\n\n")
    }

    // ---------- 변동률 (매매가·전세가 주간 변동률) ----------
    /**
     * 주간 시황 숫자 요약형 글:
     *   📈 [기간] [무엇] 흐름 요약 / 상승률 TOP • 지역: 수치 ×3 / 흐름 포인트 • … / 한 줄 정리 ✔ … / 다음 주에도 정리해서 올릴게.
     */
    private fun changeThread(s: StatSeries, head: StatRegion, sido: List<String>, casual: Boolean): List<String> {
        fun e(c: String, p: String) = if (casual) c else p
        val lines = mutableListOf<String>()
        val u = unit(s)
        val now = head.latest ?: 0.0
        val pt = "%p"
        val what = s.title.replace(Regex("\\s*(주간|월간)?\\s*변동률$"), "") // "아파트 매매가격"
        val week = if (u == "주") "${s.dateLabel} 주간" else s.dateLabel
        lines += "📈 $week $what 흐름 요약 (${s.source})"

        // 상승률 TOP 3 (시도). 오른 곳이 없으면 덜 내린 순
        val rank = ranked(s, sido)
        if (rank.size >= 3) {
            lines += (if (rank.first().latest!! > 0) "상승률 TOP" else "덜 내린 곳 TOP") + "\n" + rank.take(3).joinToString("\n") { "• ${it.name}: ${s.fmt(it.latest)}" }
        }

        // 흐름 포인트: 전국 수치와 연속 여부, 속도 변화, 서울과의 격차, 오른 곳·내린 곳 수 (최대 4개)
        val points = mutableListOf<String>()
        val streak = Insight.signStreak(head.values, eps(s))
        var first = "${head.name} ${s.fmt(head.latest)}"
        if (abs(streak) >= 2) first += ", ${abs(streak)}$u${if (abs(streak) >= head.values.size) " 넘게" else ""} 연속 " + (if (streak > 0) "상승" else "하락")
        points += first
        var accel = 0 // 1 = 강해짐, -1 = 약해짐
        val before = head.prev
        if (before != null) {
            val diff = now - before
            val unitWord = if (u == "주") "지난주" else "지난달"
            if (abs(diff) >= eps(s)) points += when {
                now > 0 && before > 0 -> { accel = if (diff > 0) 1 else -1; "상승폭은 $unitWord(${s.fmt(before)})보다 " + (if (diff > 0) "커짐" else "줄어듦") }
                now < 0 && before < 0 -> { accel = if (diff < 0) 1 else -1; "하락폭은 $unitWord(${s.fmt(before)})보다 " + (if (diff < 0) "커짐" else "줄어듦") }
                else -> "$unitWord(${s.fmt(before)})에서 " + (if (now > 0) "상승" else if (now < 0) "하락" else "보합") + "으로 바뀜"
            }
        }
        val seoul = s.region("서울")?.takeIf { it !== head }?.latest
        if (seoul != null && head.name == "전국" && abs(seoul - now) >= eps(s)) points += "서울 ${s.fmt(seoul)}, 전국보다 ${num(s, abs(seoul - now))}$pt " + (if (seoul > now) "높음" else "낮음")
        var breadth = ""
        if (rank.size >= 5) {
            val up = rank.count { it.latest!! > 0 }; val down = rank.count { it.latest!! < 0 }
            breadth = when {
                up * 3 >= rank.size * 2 -> "상승이 전국으로 퍼진 모습"
                down * 3 >= rank.size * 2 -> "약세가 넓게 퍼진 모습"
                up > 0 && down > 0 -> "오르는 곳과 내리는 곳이 갈리는 모습"
                else -> "대부분 제자리"
            }
            points += "${rank.size}개 시도 중 오른 곳 ${up}곳, 내린 곳 ${down}곳"
        }
        lines += "흐름 포인트\n" + points.take(4).joinToString("\n") { "• $it" }

        // 한 줄 정리
        val dir = if (now > eps(s)) "오름세" else if (now < -eps(s)) "내림세" else "보합"
        val pace = when { dir == "보합" -> ""; accel > 0 -> "가 강해지는 중"; accel < 0 -> "가 약해지는 중"; else -> " 유지" }
        lines += "한 줄 정리\n✔ ${head.name} $dir$pace" + (if (breadth.isNotBlank()) ", 지역별로는 $breadth" else "")
        lines += if (u == "주") e("다음 주에도 정리해서 올릴게. 팔로우해두면 놓치지 않아.", "다음 주에도 정리해서 올릴게요. 팔로우해두면 놓치지 않아요.")
        else e("다음 달에도 정리해서 올릴게. 팔로우해두면 놓치지 않아.", "다음 달에도 정리해서 올릴게요. 팔로우해두면 놓치지 않아요.")
        return lines
    }

    // ---------- 지수 (수급·매수우위·전망: 기준선 100) ----------
    private fun levelThread(s: StatSeries, head: StatRegion, sido: List<String>, casual: Boolean): List<String> {
        fun e(c: String, p: String) = if (casual) c else p
        val lines = mutableListOf<String>()
        val u = unit(s)
        val now = head.latest ?: 0.0
        val move = Insight.moveStreak(head.values, eps(s))
        var first = "${s.title}, ${head.name} ${s.fmt(head.latest)}"
        if (kotlin.math.abs(move) >= 2) first += " · ${kotlin.math.abs(move)}$u 연속 " + (if (move > 0) "상승" else "하락")
        lines += "$first (${s.source} ${s.dateLabel} 기준)"

        val base = s.base
        if (base != null) {
            val gap = now - base
            val how = when { abs(gap) < 5 -> "기준선 ${num(s, base, 0)} 근처"; abs(gap) >= 30 -> "기준선 ${num(s, base, 0)}보다 ${num(s, abs(gap))} " + (if (gap > 0) "높음 → 기준선을 크게 웃도는 상태" else "낮음 → 기준선을 크게 밑도는 상태"); else -> "기준선 ${num(s, base, 0)}보다 ${num(s, abs(gap))} " + (if (gap > 0) "높음" else "낮음") }
            lines += how + (if (s.desc.isNotBlank()) "\n(${s.desc})" else "")
        }

        // 흐름: 직전 대비, 기간 처음 대비, 기간 내 최고·최저
        val flow = mutableListOf<String>()
        val before = head.prev
        if (before != null) {
            val diff = now - before
            val unitWord = if (u == "주") "지난주" else "지난달"
            flow += if (abs(diff) < eps(s)) "${head.name}은 $unitWord(${s.fmt(before, false)})와 같" + e("음", "아요")
            else "${head.name}은 $unitWord(${s.fmt(before, false)})보다 ${s.fmt(abs(diff), false)} " + (if (diff > 0) "올랐" else "내렸") + e("음", "어요")
        }
        val start = head.values.firstOrNull()
        if (start != null && head.values.size >= 6 && abs(now - start) >= eps(s)) {
            flow += "${head.values.size - 1}$u 전(${s.fmt(start, false)})과 비교하면 ${s.fmt(abs(now - start), false)} " + (if (now > start) "높아짐" else "낮아짐")
        }
        when (Insight.extreme(head.values)) {
            1 -> flow += "최근 ${head.values.size}$u 중 가장 높은 값"
            -1 -> flow += "최근 ${head.values.size}$u 중 가장 낮은 값"
        }
        if (flow.isNotEmpty()) lines += flow.joinToString("\n")

        val others = listOfNotNull(s.region("서울"), s.region("수도권")).filter { it !== head && it.latest != null }
        if (others.isNotEmpty()) {
            var line = others.joinToString(", ") { "${it.name} ${s.fmt(it.latest)}" }
            val seoul = others.firstOrNull { it.name == "서울" }?.latest
            if (seoul != null && head.name == "전국") {
                line += "\n서울이 전국보다 ${s.fmt(abs(seoul - now), false)} " + (if (seoul > now) "높" else "낮") + e("음", "아요")
                if (base != null && (seoul - base) * (now - base) < 0) line += "\n서울은 기준선 " + (if (seoul > base) "위" else "아래") + ", 전국은 " + (if (now > base) "위" else "아래") + "로 온도 차이가 뚜렷함"
            }
            lines += line
        }

        val rank = ranked(s, sido)
        if (rank.size >= 5) {
            var line = "가장 높은 곳 ${rank.first().name}(${s.fmt(rank.first().latest)}), 가장 낮은 곳 ${rank.last().name}(${s.fmt(rank.last().latest)})"
            if (base != null) line += "\n${rank.size}개 시도 중 기준선을 넘은 곳 ${rank.count { it.latest!! > base }}곳"
            lines += line
        }
        if (base == null && s.desc.isNotBlank()) lines += s.desc
        return lines
    }

    // ---------- PIR (소득 대비 집값) ----------
    private fun pirThread(s: StatSeries, head: StatRegion, casual: Boolean): List<String> {
        fun e(c: String, p: String) = if (casual) c else p
        val lines = mutableListOf<String>()
        val seoul = s.region("서울").takeIf { it !== head && it?.latest != null }
        val now = head.latest ?: 0.0
        lines += "${s.title}, ${head.name} ${s.fmt(head.latest)}" + (seoul?.let { " · 서울 ${s.fmt(it.latest)}" } ?: "") + " (${s.source} ${s.dateLabel} 기준)"
        if (s.desc.isNotBlank()) lines += s.desc
        // 숫자를 생활 감각으로: 소득의 절반을 저축하면 두 배가 걸림
        val real = mutableListOf<String>()
        fun years(v: Double) = String.format("%.0f", v * 2)
        real += "소득의 절반을 저축한다고 치면 ${head.name} 약 ${years(now)}년" + (seoul?.latest?.let { ", 서울 약 ${years(it)}년" } ?: "")
        seoul?.latest?.let { if (now > 0) real += "서울은 ${head.name}의 ${String.format("%.1f", it / now)}배 수준" }
        lines += real.joinToString("\n")
        // 흐름
        val flow = mutableListOf<String>()
        val u = unit(s)
        for (r in listOfNotNull(head, seoul)) {
            val v = r.latest ?: continue
            val start = r.values.firstOrNull() ?: continue
            if (r.values.size < 4) continue
            val d = v - start
            flow += if (abs(d) < eps(s)) "${r.name}은 ${r.values.size - 1}$u 전(${s.fmt(start)})과 같은 수준"
            else "${r.name}은 ${r.values.size - 1}$u 전(${s.fmt(start)})보다 ${s.fmt(abs(d))} " + (if (d > 0) "올랐" else "내렸") + e("음", "어요")
        }
        if (flow.isNotEmpty()) lines += flow.joinToString("\n")
        val dir = seoul?.let { r -> r.values.firstOrNull()?.let { st -> (r.latest ?: st) - st } } ?: head.values.firstOrNull()?.let { now - it }
        if (dir != null && abs(dir) >= eps(s)) lines += "정리: 소득이 느는 속도보다 집값이 " + (if (dir > 0) "더 빨리 올라 내 집 마련 부담이 커지는 흐름" else "덜 올라 내 집 마련 부담이 줄어드는 흐름")
        return lines
    }
}

/** 한 지역의 지표들을 스레드 글로 (숫자를 그대로 끼워 넣음. AI 사용 안 함) */
object RegionText {
    /** 최근 n회 변동률을 복리로 합친 누적 변동률(%) */
    fun cumulative(values: List<Double?>, n: Int): Double? {
        val v = values.takeLast(n)
        if (v.size < n || v.any { it == null }) return null
        return (v.fold(1.0) { acc, x -> acc * (1 + x!! / 100) } - 1) * 100
    }

    fun thread(stats: Stats, region: String, picked: List<StatSeries>, casual: Boolean): String {
        fun e(casualEnd: String, politeEnd: String) = if (casual) casualEnd else politeEnd
        val lines = mutableListOf<String>()
        val sido = stats.sidoOf(region)
        val date = picked.firstOrNull()?.dateLabel
        lines += "📈 $region 집값 흐름 요약" + (date?.let { " ($it 기준)" } ?: "")
        for (s in picked) {
            val at = stats.chain(region).firstOrNull { s.region(it)?.latest != null } ?: continue
            val r = s.region(at)!!
            val who = if (at == region) "" else "($at 기준) "
            val compare = listOf(sido, "전국").distinct().filter { it != at }.mapNotNull { n -> s.region(n)?.latest?.let { "$n ${s.fmt(it)}" } }
            var line = "${s.source} ${s.short} $who${s.fmt(r.latest)}"
            val u = if (s.date.count { it == '-' } == 2) "주" else "개월"
            val streak = if (s.kind == "change") Insight.signStreak(r.values, 0.5 / Math.pow(10.0, s.digits.toDouble())) else Insight.moveStreak(r.values, 0.5 / Math.pow(10.0, s.digits.toDouble()))
            if (abs(streak) >= 2) line += " · ${abs(streak)}$u${if (s.kind == "change" && abs(streak) >= r.values.size) " 넘게" else ""} 연속 " + (if (streak > 0) "상승" else "하락")
            if (compare.isNotEmpty()) line += " (${compare.joinToString(", ")})"
            // 시도 평균과의 격차
            val sd = s.region(sido)?.latest
            if (at != sido && sd != null && r.latest != null) {
                val gap = r.latest!! - sd
                val gapText = if (s.kind == "change") String.format("%.${s.digits}f%%p", abs(gap)) else s.fmt(abs(gap), false)
                if (abs(gap) >= 0.5 / Math.pow(10.0, s.digits.toDouble())) line += "\n$sido 평균보다 $gapText " + (if (gap > 0) "높" else "낮") + e("음", "아요")
            }
            if (s.kind == "change") {
                val c4 = cumulative(r.values, 4)
                val call = if (r.values.size >= 8) cumulative(r.values, r.values.size) else null
                val extra = listOfNotNull(c4?.let { "최근 4주 ${s.fmt(it)}" }, call?.let { "${r.values.size}주 ${s.fmt(it)}" })
                if (extra.isNotEmpty()) line += "\n누적 " + extra.joinToString(", ")
            } else if (r.prev != null && r.latest != null) {
                val d = r.latest!! - r.prev!!
                if (abs(d) >= 0.5 / Math.pow(10.0, s.digits.toDouble())) line += "\n직전보다 ${s.fmt(abs(d), false)} " + (if (d > 0) "올랐" else "내렸") + e("음", "어요")
            }
            lines += line
        }
        // 같은 시도 안에서의 순위 (매매가 변동률 기준)
        val sale = picked.firstOrNull { it.kind == "change" && it.region(region)?.latest != null }
        if (sale != null) {
            val sib = stats.siblings(region).mapNotNull { n -> sale.region(n)?.latest?.let { n to it } }.sortedByDescending { it.second }
            val rank = sib.indexOfFirst { it.first == region }
            if (sib.size >= 5) {
                lines += "$sido 상승률 TOP\n" + sib.take(3).joinToString("\n") { "• ${stats.shortName(it.first)}: ${sale.fmt(it.second)}" }
                if (rank >= 0) {
                    val pos = when { rank * 3 < sib.size -> "강한 편"; rank * 3 >= sib.size * 2 -> "약한 편"; else -> "중간" }
                    lines += "한 줄 정리\n✔ ${stats.shortName(region)}는 $sido ${sib.size}개 시군구 중 ${sale.short} 상승률 ${rank + 1}위, $sido 안에서 $pos"
                } else if (region == sido) {
                    lines += "한 줄 정리\n✔ $sido 안에서는 ${stats.shortName(sib.first().first)}(${sale.fmt(sib.first().second)})가 가장 높고 ${stats.shortName(sib.last().first)}(${sale.fmt(sib.last().second)})가 가장 낮" + e("음", "아요")
                }
            }
        }
        return lines.joinToString("\n\n")
    }
}
