package com.realestatenewsfinder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** kind: news(언론) | gov(정부·공공) | community(커뮤니티) */
data class Source(
    val id: String,
    val name: String,
    val board: String,
    val color: Long,
    val kind: String,
    val ok: Boolean,
    val error: String?,
)

data class Post(
    val id: String,
    val source: String,
    val title: String,
    val url: String,
    val views: Int? = null,
    val comments: Int? = null,
    val likes: Int? = null,
    /** 중요도 (0~100) */
    val score: Int = 0,
    /** 스레드 추천 (0~100): 독자가 궁금해할 내용일수록 높음 */
    val pick: Int = 0,
    val viewsPerMin: Int = 0,
    val firstSeenAt: Long = 0,
    val publishedAt: Long? = null,
    val tags: List<String> = emptyList(),
    /** 같은 주제를 다룬 다른 매체 수 */
    val related: Int = 0,
    /** 구글 뉴스 글의 실제 언론사 이름 */
    val outlet: String? = null,
    /** 피드에 들어 있던 기사 앞부분 */
    val summary: String? = null,
    /** 같은 주제를 다룬 글 묶음에서 대표가 아닌 글이면, 대표 글의 id */
    val dupOf: String? = null,
) {
    val time: Long get() = publishedAt ?: firstSeenAt

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("source", source).put("title", title).put("url", url)
        .put("views", views ?: JSONObject.NULL).put("comments", comments ?: JSONObject.NULL).put("likes", likes ?: JSONObject.NULL)
        .put("score", score).put("pick", pick).put("viewsPerMin", viewsPerMin).put("firstSeenAt", firstSeenAt)
        .put("publishedAt", publishedAt ?: JSONObject.NULL).put("tags", JSONArray(tags)).put("related", related)
        .put("outlet", outlet ?: JSONObject.NULL).put("summary", summary ?: JSONObject.NULL).put("dupOf", dupOf ?: JSONObject.NULL)

    companion object {
        private fun JSONObject.intOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else optInt(k)
        private fun JSONObject.strOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

        fun fromJson(p: JSONObject) = Post(
            id = p.getString("id"), source = p.getString("source"), title = p.getString("title"), url = p.getString("url"),
            views = p.intOrNull("views"), comments = p.intOrNull("comments"), likes = p.intOrNull("likes"),
            score = p.optInt("score", 0), pick = p.optInt("pick", 0), viewsPerMin = p.optInt("viewsPerMin", 0),
            firstSeenAt = p.optLong("firstSeenAt", 0),
            publishedAt = if (!p.has("publishedAt") || p.isNull("publishedAt")) null else p.optLong("publishedAt"),
            tags = p.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            related = p.optInt("related", 0), outlet = p.strOrNull("outlet"), summary = p.strOrNull("summary"), dupOf = p.strOrNull("dupOf"),
        )
    }
}

data class Feed(val updatedAt: Long, val sources: List<Source>, val posts: List<Post>, val topics: List<String> = emptyList()) {
    val sourceById: Map<String, Source> by lazy { sources.associateBy { it.id } }

    fun kindOf(post: Post): String = sourceById[post.source]?.kind ?: "news"

    /** 한 종류(kind)의 소스와 글만 */
    fun only(kind: String): Feed = copy(sources = sources.filter { it.kind == kind }, posts = posts.filter { kindOf(it) == kind })

    companion object {
        fun parse(json: String): Feed {
            val o = JSONObject(json)
            val sources = o.getJSONArray("sources").let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val st = s.optJSONObject("status")
                    Source(
                        id = s.getString("id"), name = s.getString("name"), board = s.optString("board"),
                        color = parseColor(s.optString("color")), kind = s.optString("kind", "news"),
                        ok = st?.optBoolean("ok", false) ?: false,
                        error = st?.optString("error")?.takeIf { it.isNotEmpty() },
                    )
                }
            }
            val posts = o.getJSONArray("posts").let { arr -> (0 until arr.length()).map { Post.fromJson(arr.getJSONObject(it)) } }
            val topics = o.optJSONArray("topics")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            return Feed(o.optLong("updatedAt", 0), sources, posts, topics)
        }

        private fun parseColor(hex: String): Long =
            hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFF888888
    }
}

object FeedRepository {
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "posts.json")

    /** 클라우드에서 최신 피드를 받아오고, 성공하면 캐시에 저장 */
    suspend fun fetch(ctx: Context): Feed = withContext(Dispatchers.IO) {
        val url = Prefs(ctx).feedUrl
        // raw.githubusercontent.com 캐시를 피하려고 쿼리 추가
        val conn = URL("$url?t=${System.currentTimeMillis() / 60000}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) {
                throw Exception(
                    if (conn.responseCode == 404) "아직 클라우드 데이터가 없어요 (수집기가 한 번도 실행되지 않음)"
                    else "서버 응답 ${conn.responseCode}",
                )
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val feed = Feed.parse(text)
            cacheFile(ctx).writeText(text)
            withLocal(ctx, feed, network = true)
        } finally {
            conn.disconnect()
        }
    }

    /** 화면을 처음 열 때: 저장해 둔 결과만 즉시 표시 */
    fun cached(ctx: Context): Feed? = runCatching { Feed.parse(cacheFile(ctx).readText()) }.getOrNull()?.let { withLocal(ctx, it, network = false) }

    /** 클라우드에서 막힌 정부 보도자료를 휴대폰에서 직접 받아 합침 */
    private fun withLocal(ctx: Context, feed: Feed, network: Boolean): Feed {
        val (sources, posts) = runCatching { GovLocal.load(ctx, network) }.getOrDefault(emptyList<Source>() to emptyList())
        if (sources.isEmpty()) return feed
        val ids = sources.map { it.id }.toSet()
        return feed.copy(
            sources = feed.sources.filter { it.id !in ids } + sources,
            posts = feed.posts.filter { it.source !in ids } + posts,
            topics = feed.topics.ifEmpty { Topics.names },
        )
    }
}

/** 알림 조건: 선택한 소스의 새 글 중 (중요도 >= 기준) 또는 (제목에 키워드 포함) */
fun matchesAlert(post: Post, minScore: Int, keywords: List<String>, mutedSources: Set<String>): Boolean {
    if (post.source in mutedSources || post.dupOf != null) return false
    val title = post.title.lowercase()
    if (keywords.any { it.isNotBlank() && title.contains(it.trim().lowercase()) }) return true
    return post.score >= minScore
}
