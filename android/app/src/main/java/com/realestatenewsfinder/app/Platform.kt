package com.realestatenewsfinder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate

/**
 * 올릴 곳. 첫 화면에서 먼저 고르고, 그 뒤에 여는 글·통계·실거래 자료가 그곳에 맞게 만들어짐.
 * - 스레드: 짧은 글(500자) + 이미지, 스레드 앱 새 글 화면으로
 * - 오픈톡: 카드뉴스 이미지 + 짧은 글, 카카오톡 대화방 고르기 화면으로
 * - 블로그: 제목 + 소제목이 있는 긴 글 + 이미지, 네이버 블로그 앱 글쓰기 화면으로
 */
enum class Platform(val id: String, val label: String, val pkg: String, val appName: String) {
    THREADS("threads", "스레드", ThreadsKit.THREADS_PACKAGE, "스레드"),
    OPENTALK("opentalk", "오픈톡", "com.kakao.talk", "카카오톡"),
    BLOG("blog", "블로그", "com.nhn.android.blog", "네이버 블로그");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: THREADS

        /** 지금 고른 곳 (화면이 바로 바뀌도록 Compose 상태) */
        var current by mutableStateOf(THREADS)
            private set
        private var loaded = false

        fun load(ctx: Context) {
            if (loaded) return
            loaded = true
            current = of(Prefs(ctx).platform)
        }

        fun select(ctx: Context, p: Platform) {
            loaded = true
            current = p
            Prefs(ctx).platform = p.id
        }
    }
}

/** 올릴 계정: 0 = 부알스(부동산 알려주는 스레드), 1 = 중개의 신. 고르면 카드 워터마크와 스레드로 올릴 계정이 함께 바뀜 */
object Account {
    var current by mutableStateOf(0)
        private set
    private var loaded = false
    fun load(ctx: Context) { if (!loaded) { loaded = true; current = Prefs(ctx).markAccount } }
    fun select(ctx: Context, i: Int) { loaded = true; current = i; Prefs(ctx).markAccount = i }
}

/** 첫 화면 위쪽의 '올릴 곳'과 '계정' 고르기 */
@Composable
fun PlatformBar(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Platform.load(ctx); Account.load(ctx)
    androidx.compose.foundation.layout.Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("올릴 곳", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
            Spacer(Modifier.width(8.dp))
            PillRow { Platform.entries.forEach { p -> ChoicePill(p.label, Platform.current == p) { Platform.select(ctx, p) } } }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("계정", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
            Spacer(Modifier.width(8.dp))
            PillRow { Watermark.ACCOUNTS.forEachIndexed { i, name -> ChoicePill(name, Account.current == i) { Account.select(ctx, i) } } }
        }
    }
}

object PlatformKit {
    fun installed(ctx: Context, p: Platform) = runCatching { ctx.packageManager.getPackageInfo(p.pkg, 0); true }.getOrDefault(false)

    /** 사진 + 글을 그 앱으로 넘기는 인텐트. 한 장이면 SEND, 여러 장이면 SEND_MULTIPLE */
    fun intent(uris: List<Uri>, title: String, text: String): Intent =
        (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))).apply {
            type = "image/jpeg"
            if (title.isNotBlank()) { putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TITLE, title) }
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * 오픈톡·블로그로 보내기 (스레드는 ThreadsKit.upload).
     * - 오픈톡: 카카오톡을 열어 대화방 고르기 화면까지. 글은 복사돼 있어 사진을 보낸 뒤 붙여넣기
     * - 블로그: 네이버 블로그 앱 글쓰기 화면에 사진을 넣은 상태까지. 제목과 본문은 차례로 복사해 둠(본문이 맨 위) → 붙여넣기
     * 앱이 없으면 공유할 앱 목록을 띄움
     */
    fun send(ctx: Context, p: Platform, files: List<File>, title: String, text: String) {
        if (p == Platform.THREADS) { ThreadsKit.upload(ctx, files, text); return }
        if (p == Platform.BLOG && title.isNotBlank()) ThreadsKit.copy(ctx, "제목", title)
        ThreadsKit.copy(ctx, "본문", text)
        // 접근성 자동 입력이 켜져 있으면 블로그 글쓰기 화면에 제목·본문을 넣어 줌
        val auto = p == Platform.BLOG && ThreadsAuto.isEnabled(ctx)
        if (auto) BlogAuto.start(title, text)
        val uris = files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
        val intent = intent(uris, if (p == Platform.BLOG) title else "", text)
        // 블로그 앱에 글쓰기나 다른 화면이 열려 있으면 그 위에 겹쳐 오류가 남 → 열려 있던 화면을 닫고 새 글쓰기 화면으로 엶
        if (p == Platform.BLOG) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val has = installed(ctx, p)
        val ok = has && runCatching { ctx.startActivity(intent.setPackage(p.pkg)) }.isSuccess
        if (!ok) runCatching { ctx.startActivity(Intent.createChooser(intent.setPackage(null), "공유할 앱 선택")) }
        Toast.makeText(
            ctx,
            when {
                !ok -> "${p.appName} 앱을 열지 못해 공유 목록을 띄웠어요. 글은 복사돼 있어요."
                p == Platform.OPENTALK -> "오픈톡 방을 고르면 사진이 가요. 글은 복사돼 있으니 붙여넣기 하세요."
                auto -> "글쓰기 화면이 열리면 제목과 본문을 넣을게요. 잠시만 기다려 주세요."
                else -> "사진을 넣었어요. 접근성 자동 입력을 켜면 제목·본문도 들어가요. 지금은 본문을 붙여넣기 하세요."
            },
            Toast.LENGTH_LONG,
        ).show()
    }
}

/**
 * 통계·실거래처럼 숫자로 만든 글을 블로그 글 꼴로 바꿈 (AI 사용 안 함).
 * 검색에 잘 걸리는 블로그 글 구성: 핵심 낱말이 앞에 오는 제목 → 결론부터 쓰는 첫 문단 → 소제목으로 나눈 본문 → 한 줄 안내 → 자료 출처 → 태그
 */
object BlogText {
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")

    fun title(screenTitle: String, today: LocalDate): String {
        val t = screenTitle.replace(EMOJI, "").replace(" · ", " ").replace(Regex("\\s+"), " ").trim()
        return "$t (${today.year}년 ${today.monthValue}월 기준)"
    }

    /** 글에서 태그로 쓸 낱말: 기본 태그 + 제목에 나온 지역·단지 이름 */
    fun tags(screenTitle: String): List<String> {
        val words = screenTitle.replace(EMOJI, "").split(Regex("[^가-힣A-Za-z0-9]+")).filter { it.length in 2..12 && !it.all(Char::isDigit) }
        val skip = setOf("실험실", "전체", "TOP", "vs", "사려면", "얼마", "필요할까", "으로", "있는", "그때와", "지금", "타이밍", "정리", "비교", "계산", "신호", "가지", "최근", "그때와", "지금")
        return (words.filter { it !in skip }.take(3) + listOf("부동산", "아파트", "실거래가", "부동산시세", "내집마련")).distinct().take(8)
    }

    fun body(screenTitle: String, text: String): String {
        val paras = text.replace(EMOJI, "").replace("✔ ", "").lines().joinToString("\n") { it.trimStart() }.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) return ""
        val notes = paras.filter { it.startsWith("※") }
        val main = paras.filter { !it.startsWith("※") }
        val out = mutableListOf(main.first())
        if (main.size > 1) {
            val last = main.last()
            // 마지막 문단이 한두 줄짜리 맺음말·질문이면 '마무리'로 따로 뺌
            val closing = main.size > 2 && last.lines().size <= 2
            val middle = if (closing) main.subList(1, main.size - 1) else main.subList(1, main.size)
            out += "■ 숫자로 보는 핵심\n\n" + middle.joinToString("\n\n")
            if (closing) out += "■ 마무리\n\n$last"
        }
        out += "■ 참고\n\n" + (notes.map { it.removePrefix("※").trim() } + "숫자는 공개된 통계·실거래 자료를 그대로 옮긴 것이고, 사거나 팔라는 권유가 아닙니다.").joinToString("\n") { "- $it" }
        out += "자료: 국토교통부 실거래가 공개시스템, 한국부동산원, KB부동산"
        out += tags(screenTitle).joinToString(" ") { "#$it" }
        return out.joinToString("\n\n")
    }
}
