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

data class Stats(val updatedAt: Long, val sido: List<String>, val series: List<StatSeries>) {
    companion object {
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
                    desc = s.optString("desc"), source = s.optString("source"), date = s.optString("date"), dates = strings(s.optJSONArray("dates")),
                    regions = (0 until rs.length()).map { j ->
                        val r = rs.getJSONObject(j)
                        val v = r.getJSONArray("values")
                        StatRegion(r.getString("name"), (0 until v.length()).map { k -> if (v.isNull(k)) null else v.getDouble(k) })
                    },
                )
            }
            return Stats(o.optLong("checkedAt", o.optLong("updatedAt", 0)), strings(o.optJSONArray("sido")), series)
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

/**
 * 통계를 스레드 글로. AI를 쓰지 않고 숫자를 그대로 문장에 끼워 넣어서 숫자가 틀릴 일이 없음.
 */
object StatText {
    private fun ranked(s: StatSeries, sido: List<String>) =
        s.regions.filter { it.name in sido && it.latest != null }.sortedByDescending { it.latest!! }

    fun thread(s: StatSeries, sido: List<String>, casual: Boolean): String {
        fun e(casualEnd: String, politeEnd: String) = if (casual) casualEnd else politeEnd
        val lines = mutableListOf<String>()
        val nation = s.region("전국")
        val seoul = s.region("서울")
        val metro = s.region("수도권")
        val head = nation ?: seoul ?: s.regions.first()
        val basis = "(${s.source} ${s.dateLabel} 기준)"
        lines += "${s.title}, ${head.name} ${s.fmt(head.latest)} $basis"

        val others = listOfNotNull(seoul, metro).filter { it !== head && it.latest != null }
        if (others.isNotEmpty()) lines += others.joinToString(", ") { "${it.name} ${s.fmt(it.latest)}" } + e("임", "예요")

        val rank = ranked(s, sido)
        if (rank.size >= 5) {
            val top = rank.first(); val bottom = rank.last()
            if (s.kind == "change") {
                lines += if (top.latest!! > 0) "제일 많이 오른 곳은 ${top.name}(${s.fmt(top.latest)})" + e("", "예요") else "오른 곳 없이 ${top.name}(${s.fmt(top.latest)})이 가장 덜 내렸" + e("음", "어요")
                lines += if (bottom.latest!! < 0) "제일 많이 내린 곳은 ${bottom.name}(${s.fmt(bottom.latest)})" + e("", "예요") else "가장 덜 오른 곳은 ${bottom.name}(${s.fmt(bottom.latest)})" + e("", "예요")
                val up = rank.count { it.latest!! > 0 }; val down = rank.count { it.latest!! < 0 }
                lines += "17개 시도 중 오른 곳 ${up}곳, 내린 곳 ${down}곳" + e("", "이에요")
            } else {
                lines += "가장 높은 곳은 ${top.name}(${s.fmt(top.latest)}), 가장 낮은 곳은 ${bottom.name}(${s.fmt(bottom.latest)})" + e("", "예요")
            }
        }

        val now = head.latest; val before = head.prev
        if (now != null && before != null) {
            val unitWord = when (s.cycle) { "주간" -> "지난주"; "월간" -> "지난달"; else -> "직전 조사" }
            val diff = now - before
            val same = abs(diff) < 0.5 / Math.pow(10.0, s.digits.toDouble())
            lines += if (s.kind == "change") {
                when {
                    same -> "${head.name} 변동률은 $unitWord(${s.fmt(before)})와 같" + e("음", "아요")
                    now > 0 && before > 0 -> "${head.name} 상승폭은 $unitWord(${s.fmt(before)})보다 " + (if (diff > 0) "커졌" else "줄었") + e("음", "어요")
                    now < 0 && before < 0 -> "${head.name} 하락폭은 $unitWord(${s.fmt(before)})보다 " + (if (diff < 0) "커졌" else "줄었") + e("음", "어요")
                    else -> "$unitWord(${s.fmt(before)})에서 " + (if (now > 0) "상승" else if (now < 0) "하락" else "보합") + "으로 바뀌었" + e("음", "어요")
                }
            } else {
                if (same) "${head.name}은 $unitWord(${s.fmt(before, false)})와 같" + e("음", "아요")
                else "${head.name}은 $unitWord(${s.fmt(before, false)})보다 ${s.fmt(abs(diff), false)} " + (if (diff > 0) "올랐" else "내렸") + e("음", "어요")
            }
        }
        if (s.kind != "change" && s.desc.isNotBlank()) lines += s.desc
        return lines.joinToString("\n\n")
    }
}
