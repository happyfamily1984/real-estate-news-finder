package com.realestatenewsfinder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 정부 보도자료를 휴대폰에서 직접 받아옴 (정책브리핑 부처별 RSS).
 * 정부 사이트는 해외 클라우드 접속을 막아서 GitHub Actions에서는 받을 수 없음.
 * 사이트에 부담을 주지 않도록 30분에 한 번만 받고, 그 사이에는 저장해 둔 결과를 사용.
 */
object GovLocal {
    data class Src(val id: String, val name: String, val board: String, val feed: String, val color: Long, val filter: Boolean)

    private fun dept(id: String, name: String, color: Long, filter: Boolean = true) =
        Src(id, name, "보도자료", "https://www.korea.kr/rss/dept_$id.xml", color, filter)

    val SOURCES = listOf(
        dept("molit", "국토교통부", 0xFF1F6F43, filter = false),
        dept("fsc", "금융위원회", 0xFF8A6D1D),
        dept("moef", "기획재정부", 0xFF5A4FCF),
        dept("nts", "국세청", 0xFFB0542C),
        Src("policy", "정책브리핑", "정책뉴스", "https://www.korea.kr/rss/policy.xml", 0xFF2B7A9E, true),
    )

    private const val INTERVAL_MS = 30 * 60_000L
    private const val KEEP_MS = 72 * 3600_000L // 보도자료는 하루에 몇 건뿐이라 3일치 유지
    private const val MAX_PER_SOURCE = 30

    private fun file(ctx: Context) = File(ctx.filesDir, "gov-local.json")

    private fun strip(html: String) = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()

    private fun parseDate(s: String): Long? {
        for (fmt in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss")) {
            runCatching { return SimpleDateFormat(fmt, Locale.US).parse(s.trim())?.time }
        }
        return null
    }

    /** RSS XML → 글 목록 (부동산 관련만, 최신순). 점수는 최신성 + 독자 관심도 */
    fun parse(xml: String, src: Src, now: Long = System.currentTimeMillis()): List<Post> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.trim().byteInputStream())
        val items = doc.getElementsByTagName("item")
        val out = ArrayList<Post>()
        val seen = HashSet<String>()
        for (i in 0 until items.length) {
            val it = items.item(i) as? Element ?: continue
            fun text(tag: String) = it.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val title = strip(text("title"))
            val link = text("link").replace(Regex("[?&]call_from=[^&]*"), "")
            if (title.isEmpty() || !link.startsWith("http") || !seen.add(link)) continue
            if (src.filter && !Topics.isRelevant(title)) continue
            val published = parseDate(text("pubDate"))?.coerceAtMost(now)
            if (published != null && now - published > KEEP_MS) continue
            val ageH = (now - (published ?: now)) / 3600_000.0
            val recency = Math.pow(0.5, ageH / 12.0) // 정부 발표는 12시간마다 절반
            val interest = Topics.threadPick(title)
            val desc = strip(text("description")).take(200)
            out += Post(
                id = "${src.id}:$link", source = src.id, title = title, url = link,
                score = Math.round((recency * 50 + interest * 0.3 + 15).coerceAtMost(100.0)).toInt(),
                pick = Math.round((interest * 0.8 + recency * 20).coerceAtMost(100.0)).toInt(),
                firstSeenAt = now, publishedAt = published, tags = Topics.tagsOf(title),
                summary = desc.takeIf { d -> d.isNotEmpty() && d != title },
            )
        }
        return out.sortedByDescending { it.time }.take(MAX_PER_SOURCE)
    }

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RealEstateNewsFinder (personal feed reader)")
        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private data class Cache(val at: Long, val errors: Map<String, String>, val posts: List<Post>)

    private fun readCache(ctx: Context): Cache? = runCatching {
        val o = JSONObject(file(ctx).readText())
        val errs = o.getJSONObject("errors")
        val arr = o.getJSONArray("posts")
        Cache(o.getLong("at"), errs.keys().asSequence().associateWith { errs.getString(it) }, (0 until arr.length()).map { Post.fromJson(arr.getJSONObject(it)) })
    }.getOrNull()

    /** 반드시 백그라운드 스레드에서 호출. network=false면 저장해 둔 결과만 */
    fun load(ctx: Context, network: Boolean): Pair<List<Source>, List<Post>> {
        var cache = readCache(ctx)
        val now = System.currentTimeMillis()
        if (network && (cache == null || now - cache.at >= INTERVAL_MS)) {
            val old = cache?.posts.orEmpty().associateBy { it.id }
            val errors = HashMap<String, String>()
            val posts = ArrayList<Post>()
            for (s in SOURCES) {
                try {
                    // 처음 본 시각은 유지 (새 글 표시·알림용)
                    posts += parse(http(s.feed), s, now).map { p -> old[p.id]?.let { p.copy(firstSeenAt = it.firstSeenAt) } ?: p }
                } catch (e: Exception) {
                    errors[s.id] = e.message ?: "연결 실패"
                    posts += old.values.filter { it.source == s.id } // 실패하면 이전 결과 유지
                }
            }
            cache = Cache(now, errors, posts)
            runCatching {
                file(ctx).writeText(JSONObject().put("at", now).put("errors", JSONObject(errors as Map<*, *>)).put("posts", JSONArray().apply { posts.forEach { put(it.toJson()) } }).toString())
            }
        }
        val c = cache ?: return emptyList<Source>() to emptyList()
        val sources = SOURCES.map { s -> Source(s.id, s.name, s.board, s.color, "gov", ok = s.id !in c.errors, error = c.errors[s.id]) }
        return sources to c.posts
    }
}
