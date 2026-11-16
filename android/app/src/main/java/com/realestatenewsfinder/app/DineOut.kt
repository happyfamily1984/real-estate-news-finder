package com.realestatenewsfinder.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 물가 비교용 외식비 (한국소비자원 참가격, 서울). 휴대폰에서 직접 받아 옴 — 클라우드 수집기에서는 접속이 자주 막혀서.
 * 해마다 같은 달의 값을 받고, 요청한 해의 표가 맞는지(화면에서 고른 연도) 확인한 값만 씀. 받은 값은 저장해 두고 다시 받지 않음.
 */
object DineOut {
    private const val URL_ = "https://www.price.go.kr/tprice/portal/servicepriceinfo/dineoutprice/dineOutPriceList.do"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private fun strip(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    /** 조회 화면에서 지금 고른 연·월 ("2026" to "10"). 못 찾으면 null */
    fun selected(html: String): Pair<String, String>? {
        fun pick(name: String): String? {
            val sel = Regex("<select[^>]*name=[\"']$name[\"'][\\s\\S]*?</select>").find(html)?.value ?: return null
            val opt = Regex("<option[^>]*selected[^>]*>").find(sel)?.value ?: return null
            return Regex("value=[\"']?(\\d+)").find(opt)?.groupValues?.get(1)
        }
        val y = pick("searchYear") ?: return null
        val m = pick("searchMonth") ?: return null
        return y to m
    }

    /** 외식비 표 → 그 지역의 품목별 값(원). 표가 없으면 빈 맵 */
    fun parse(html: String, region: String = "서울"): Map<String, Int> {
        val rows = Regex("<tr[^>]*>([\\s\\S]*?)</tr>").findAll(html).map { tr ->
            Regex("<t[hd][^>]*>([\\s\\S]*?)</t[hd]>").findAll(tr.groupValues[1]).map { strip(it.groupValues[1]) }.toList()
        }.toList()
        val head = rows.firstOrNull { "자장면" in it && it.size >= 3 } ?: return emptyMap()
        val row = rows.firstOrNull { it.size == head.size && it[0].startsWith(region) } ?: return emptyMap()
        return head.indices.drop(1).mapNotNull { i ->
            val v = row[i].replace(Regex("[^0-9]"), "").toIntOrNull()
            if (v != null && v in 500..100000) head[i] to v else null
        }.toMap()
    }

    private fun http(url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
        c.setRequestProperty("Referer", URL_)
        try {
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** 그 해·달의 값. 응답 화면이 정말 그 해를 보여주는지 확인하고, 아니면 null (엉뚱한 해의 값을 쓰지 않으려고) */
    private fun fetchYear(year: Int, month: String): Map<String, Int>? {
        val q = "searchOrExcel=&searchYear=$year&searchMonth=$month&searchArea="
        for (attempt in listOf<() -> String>({ http(URL_, q) }, { http("$URL_?$q") })) {
            val html = runCatching(attempt).getOrNull() ?: continue
            if (selected(html)?.first == year.toString()) parse(html).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "dineout.json")

    /**
     * 필요한 해들의 값: 품목 → (연도 → 원). 저장된 것이 있으면 그걸 쓰고, 없는 해만 받음. 실패하면 받은 데까지만.
     * 네트워크를 쓰므로 백그라운드에서 호출
     */
    fun load(ctx: Context, thisYear: Int, years: List<Int>): Map<String, Map<Int, Int>> {
        val saved = runCatching { JSONObject(file(ctx).readText()) }.getOrNull() ?: JSONObject()
        val byYear = saved.optJSONObject("years") ?: JSONObject().also { saved.put("years", it) }
        var month = saved.optString("month")
        var changed = false
        // 올해 값: 기본 화면이 가장 최근 달을 보여줌 (한 달에 한 번만 다시 받음)
        val stamp = "$thisYear-${java.time.LocalDate.now(KST).monthValue}"
        if (saved.optString("checked") != stamp) {
            runCatching { http(URL_) }.getOrNull()?.let { html ->
                val sel = selected(html)
                val row = parse(html)
                if (sel != null && row.isNotEmpty()) {
                    if (sel.second != month) { byYear.keys().asSequence().toList().forEach { byYear.remove(it) } } // 기준 달이 바뀌면 지난 해 값도 그 달로 다시
                    month = sel.second
                    byYear.put(sel.first, JSONObject(row as Map<*, *>))
                    saved.put("month", month).put("checked", stamp)
                    changed = true
                }
            }
        }
        if (month.isNotBlank()) for (y in years.distinct()) {
            if (y < 2009 || byYear.has(y.toString())) continue
            fetchYear(y, month)?.let { byYear.put(y.toString(), JSONObject(it as Map<*, *>)); changed = true }
        }
        if (changed) runCatching { file(ctx).writeText(saved.toString()) }
        val out = HashMap<String, MutableMap<Int, Int>>()
        for (y in byYear.keys()) {
            val row = byYear.getJSONObject(y)
            for (name in row.keys()) out.getOrPut(name) { HashMap() }[y.toInt()] = row.getInt(name)
        }
        return out
    }
}
