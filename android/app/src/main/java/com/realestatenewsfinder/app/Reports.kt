package com.realestatenewsfinder.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * 보고서함의 보고서 하나. 채팅에서 조사를 시키면 저장소의 reports/reports.json에 올라오고, 앱이 그걸 받아 보여줌.
 * 올릴 곳마다 쓸 글이 미리 들어 있음: thread(스레드, 500자 이내) · talk(오픈톡, 짧은 안내) · blog(제목·본문·태그).
 * cards는 카드뉴스 이미지로 그릴 내용 (장마다 제목 + '항목: 내용' 줄들).
 */
data class Report(
    val id: String, val title: String, val date: String, val summary: String,
    val thread: String, val talk: String,
    val blogTitle: String, val blogBody: String, val tags: List<String>,
    val cards: List<Card>, val sources: List<String>,
) {
    data class Card(val title: String, val points: List<String>)

    /** 올릴 곳에 맞는 짧은 글 */
    fun textFor(p: Platform) = if (p == Platform.OPENTALK) talk.ifBlank { thread } else thread

    /** 블로그 본문: 본문 + 참고 자료 + 태그 */
    fun blogFull(): String = listOfNotNull(
        blogBody.trim().ifBlank { thread },
        if (sources.isEmpty()) null else "참고 자료\n" + sources.joinToString("\n") { "- $it" },
        if (tags.isEmpty()) null else tags.joinToString(" ") { "#" + it.removePrefix("#").replace(" ", "") },
    ).joinToString("\n\n")

    val time: Long get() = runCatching { LocalDate.parse(date).atTime(12, 0).atZone(KST).toInstant().toEpochMilli() }.getOrDefault(0L)
}

object Reports {
    private fun strings(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }

    /** 보고서 하나 (보고서함, 내 단지의 단지 분석·경매 글이 같은 꼴을 씀). 제목이 없으면 null */
    fun one(o: JSONObject, fallbackId: String, fallbackDate: String = ""): Report? {
        val title = o.optString("title").trim()
        if (title.isEmpty()) return null
        val blog = o.optJSONObject("blog")
        val cards = o.optJSONArray("cards")
        return Report(
            id = o.optString("id").ifBlank { fallbackId }, title = title, date = o.optString("date").ifBlank { fallbackDate }, summary = o.optString("summary"),
            thread = o.optString("thread"), talk = o.optString("talk"),
            blogTitle = blog?.optString("title").orEmpty().ifBlank { title }, blogBody = blog?.optString("body").orEmpty(), tags = strings(blog?.optJSONArray("tags")),
            cards = (0 until (cards?.length() ?: 0)).mapNotNull { k -> cards!!.optJSONObject(k)?.let { c -> Report.Card(c.optString("title").ifBlank { title }, strings(c.optJSONArray("points"))) } }.filter { it.points.isNotEmpty() },
            sources = strings(o.optJSONArray("sources")),
        )
    }

    fun parse(json: String): List<Report> {
        val arr = JSONObject(json).optJSONArray("reports") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            one(o, "r$i-${o.optString("title").trim().hashCode()}")
        }.sortedByDescending { it.date }
    }

    /**
     * 보고서 주소. 수집 자료(data 브랜치)와 달리 보고서는 저장소 기본 브랜치의 reports/reports.json에 있음.
     * 피드 주소가 깃허브 원본 주소(…/data/posts.json) 꼴이 아니면 같은 자리의 reports.json
     */
    fun urlOf(feedUrl: String): String {
        val m = Regex("^(https://raw\\.githubusercontent\\.com/[^/]+/[^/]+)/[^/]+/posts\\.json$").find(feedUrl.trim())
        return if (m != null) m.groupValues[1] + "/HEAD/reports/reports.json" else feedUrl.substringBeforeLast('/') + "/reports.json"
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "reports.json")

    suspend fun fetch(ctx: Context): List<Report> = withContext(Dispatchers.IO) {
        val conn = URL("${urlOf(Prefs(ctx).feedUrl)}?t=${System.currentTimeMillis() / 60000}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        try {
            if (conn.responseCode == 404) return@withContext emptyList()
            if (conn.responseCode != 200) throw Exception("서버 응답 ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val list = parse(text)
            cacheFile(ctx).writeText(text)
            list
        } finally {
            conn.disconnect()
        }
    }

    fun cached(ctx: Context): List<Report> = runCatching { parse(cacheFile(ctx).readText()) }.getOrDefault(emptyList())

    /** 카드뉴스 이미지: 표지 + 장마다 핵심 정리 */
    fun render(ctx: Context, r: Report, tag: String = "조사 보고서", source: String = "보고서"): List<android.graphics.Bitmap> {
        val meta = CardMaker.Meta(tag, source, r.time.takeIf { it > 0 } ?: System.currentTimeMillis(), Watermark.of(ctx))
        return listOf(CardMaker.cover(r.title, meta)) + r.cards.flatMap { CardMaker.pointCards(it.title, it.points, meta) }
    }
}

/** 보고서함 목록 (통계 탭 > 보고서함). 누르면 올릴 곳에 맞춘 내보내기 화면으로 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportBox(onOpen: (Report) -> Unit, initial: List<Report>? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf(initial ?: Reports.cached(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        scope.launch {
            loading = true
            try { reports = Reports.fetch(ctx); error = null } catch (e: Exception) { error = e.message ?: "불러오기 실패" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (initial == null) refresh(); UploadLog.load(ctx) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    PullToRefreshBox(isRefreshing = loading, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            if (error != null) item { Text("보고서를 받지 못했어요: $error", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(6.dp)) }
            if (reports.isEmpty()) item {
                Text(
                    "아직 보고서가 없어요.\n채팅에서 조사를 시키면 보고서가 여기에 올라와요. 아래로 당기면 새로 받아요.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = muted, modifier = Modifier.padding(horizontal = 6.dp, vertical = 24.dp),
                )
            }
            items(reports, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { onOpen(r) }.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (r.summary.isNotBlank()) Text(r.summary, fontSize = 13.sp, lineHeight = 18.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(listOf(r.date, "카드 ${r.cards.size + 1}장").filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = muted)
                            if (UploadLog.sent.containsKey("report-" + r.id)) Text("  ✓ 올림", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF0E7C3E))
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                }
            }
        }
    }
}

/** 보고서 하나를 올릴 곳(스레드·오픈톡·블로그)에 맞춰 내보내기 */
@Composable
fun ReportShareScreen(
    r: Report, onBack: () -> Unit, onSent: () -> Unit,
    hint: String = "조사해서 써 둔 보고서예요. 숫자·날짜는 올리기 전에 참고 자료와 한 번 대조해 주세요.", tag: String = "조사 보고서", source: String = "보고서",
) {
    val ctx = LocalContext.current
    val platform = remember { Platform.load(ctx); Platform.current }
    var text by remember(r.id) { mutableStateOf(r.textFor(platform)) }
    ThreadShareScreen(
        title = r.title, cacheId = "report-${r.id}", hint = hint,
        text = text, onText = { text = it },
        renderKey = r.id to Account.current, render = { Reports.render(ctx, r, tag, source) },
        onBack = onBack, onSent = { UploadLog.mark(ctx, "report-" + r.id); onSent() },
        blogTitleText = r.blogTitle, blogBodyText = r.blogFull(), coverFirst = true,
    )
}
