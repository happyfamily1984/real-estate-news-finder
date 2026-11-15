package com.realestatenewsfinder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 뽐뿌 부동산포럼을 휴대폰에서 직접 받아옴.
 * 뽐뿌는 클라우드(데이터센터) 접속을 막아서(HTTP 403) GitHub Actions에서는 받을 수 없음.
 * 사이트에 부담을 주지 않도록 15분에 한 번만 받고, 접근을 제한당하면 1시간 쉼.
 */
object PpomppuLocal {
    const val ID = "ppomppu"
    private const val LIST_URL = "https://www.ppomppu.co.kr/zboard/zboard.php?id=house"
    private const val INTERVAL_MS = 15 * 60_000L
    private const val COOLDOWN_MS = 60 * 60_000L
    private const val MAX_POSTS = 30

    private fun file(ctx: Context) = File(ctx.filesDir, "ppomppu-local.json")
    private fun source(error: String?) = Source(ID, "뽐뿌", "부동산포럼", 0xFFD9534F, "community", ok = error == null, error = error)

    private fun firstInt(text: String): Int? = Regex("\\d[\\d,]*").find(text)?.value?.replace(",", "")?.toIntOrNull()

    /** 목록 HTML → 글 (공지 제외). 점수는 이 목록 안에서의 상대 순위(조회·댓글·추천) */
    fun parse(doc: Document, now: Long = System.currentTimeMillis()): List<Post> {
        val seen = HashSet<String>()
        val raw = doc.select("tr.baseList").mapNotNull { row ->
            if (row.hasClass("baseNotice") || row.selectFirst(".baseNotice") != null) return@mapNotNull null
            // 썸네일 링크와 제목 링크가 같은 클래스를 쓰는 게시판이 있어, 글자가 있는 마지막 링크를 제목으로
            val link = row.select("a.baseList-title").lastOrNull { it.text().isNotBlank() } ?: return@mapNotNull null
            val no = Regex("[?&]no=(\\d+)").find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            if (!seen.add(no)) return@mapNotNull null
            val title = link.text().replace(Regex("\\s+"), " ").trim()
            if (title.isEmpty()) return@mapNotNull null
            Post(
                id = "$ID:$no", source = ID, title = title, url = "https://www.ppomppu.co.kr/zboard/view.php?id=house&no=$no",
                views = row.selectFirst(".baseList-views")?.text()?.let(::firstInt),
                comments = row.selectFirst(".baseList-c")?.text()?.let(::firstInt) ?: 0,
                likes = row.selectFirst(".baseList-rec")?.text()?.let(::firstInt) ?: 0,
                firstSeenAt = now, tags = Topics.tagsOf(title),
            )
        }
        // 같은 목록 안에서의 백분위 (댓글에 가중치)
        fun pct(values: List<Int>): (Int) -> Double = { v -> if (values.size <= 1) 0.5 else values.count { it < v }.toDouble() / (values.size - 1).coerceAtLeast(1) }
        val pv = pct(raw.map { it.views ?: 0 }); val pc = pct(raw.map { it.comments ?: 0 }); val pl = pct(raw.map { it.likes ?: 0 })
        return raw.map { p ->
            val base = (pv(p.views ?: 0) + pc(p.comments ?: 0) * 1.2 + pl(p.likes ?: 0)) / 3.2
            val score = Math.round(base.coerceIn(0.0, 1.0) * 85).toInt()
            p.copy(score = score, pick = Math.round(Topics.threadPick(p.title) * 0.6 + score * 0.4).toInt())
        }.sortedByDescending { it.score }.take(MAX_POSTS)
    }

    private fun fetch(): Document {
        val conn = URL(LIST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            // 뽐뿌는 EUC-KR: 글자 인코딩은 응답 헤더·meta 태그에서 자동 판별
            return conn.inputStream.use { Jsoup.parse(it, null, LIST_URL) }
        } finally {
            conn.disconnect()
        }
    }

    private data class Cache(val at: Long, val error: String?, val posts: List<Post>)

    private fun readCache(ctx: Context): Cache? = runCatching {
        val o = JSONObject(file(ctx).readText())
        val arr = o.getJSONArray("posts")
        Cache(o.getLong("at"), o.optString("error").takeIf { it.isNotEmpty() }, (0 until arr.length()).map { Post.fromJson(arr.getJSONObject(it)) })
    }.getOrNull()

    /** 반드시 백그라운드 스레드에서 호출. network=false면 저장해 둔 결과만. 한 번도 받은 적 없으면 null */
    fun load(ctx: Context, network: Boolean): Pair<Source, List<Post>>? {
        var cache = readCache(ctx)
        val now = System.currentTimeMillis()
        val wait = if (cache?.error?.let { it.contains("403") || it.contains("429") || it.contains("430") } == true) COOLDOWN_MS else INTERVAL_MS
        if (network && (cache == null || now - cache.at >= wait)) {
            val old = cache?.posts.orEmpty().associateBy { it.id }
            cache = try {
                val posts = parse(fetch(), now).map { p -> old[p.id]?.let { p.copy(firstSeenAt = it.firstSeenAt) } ?: p }
                if (posts.isEmpty()) Cache(now, "글을 찾지 못했습니다 (사이트 구조 변경 가능성)", old.values.toList()) else Cache(now, null, posts)
            } catch (e: Exception) {
                Cache(now, e.message ?: "연결 실패", old.values.toList()) // 실패하면 이전 결과 유지
            }
            runCatching {
                file(ctx).writeText(JSONObject().put("at", cache.at).put("error", cache.error ?: "").put("posts", JSONArray().apply { cache.posts.forEach { put(it.toJson()) } }).toString())
            }
        }
        val c = cache ?: return null
        return source(c.error) to c.posts
    }
}
