package com.realestatenewsfinder.app

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val UpRed = Color(0xFFE5484D)
private val DownBlue = Color(0xFF2563EB)

@Composable
private fun valueColor(s: StatSeries, v: Double?): Color = when {
    v == null -> MaterialTheme.colorScheme.onSurfaceVariant
    s.kind == "change" -> if (v > 0) UpRed else if (v < 0) DownBlue else MaterialTheme.colorScheme.onSurface
    s.base != null -> if (v >= s.base) UpRed else DownBlue
    else -> MaterialTheme.colorScheme.onSurface
}

/** 통계 탭: ① 전국 지표 ② 지역별(시도·시군구를 골라 그 지역 지표 만들기) ③ 실거래(단지 실거래·알림) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    stats: Stats?, error: String?, loading: Boolean, onRefresh: () -> Unit, onOpen: (StatSeries) -> Unit,
    onRegion: (String) -> Unit = {}, onDealRegion: (String) -> Unit = {}, onDealOpen: (DealTarget) -> Unit = {},
    onDealTool: (String) -> Unit = {}, onLab: (String) -> Unit = {},
    mode: Int = 0, onMode: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text("통계", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                when (mode) { 1 -> "지역을 고르면 그 지역 지표로 이미지와 글이 만들어져요"; 2 -> "단지 실거래를 보고 알림을 받거나 스레드용으로 만들어요"; else -> "지표를 누르면 스레드용 그래프 이미지와 글이 만들어져요" },
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("전국 지표", "지역별", "실거래").forEachIndexed { i, label -> ChoicePill(label, mode == i) { onMode(i) } }
            }
        }
        when (mode) {
            1 -> RegionPicker(stats, error, onRegion)
            2 -> DealHome(onDealRegion, onDealOpen, onDealTool)
            else -> PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.fillMaxSize()) {
                    if (error != null) item { Text("⚠ $error", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                    if (stats == null) item { EmptyText(if (error == null) "불러오는 중…" else "통계를 받지 못했어요. 아래로 당겨 다시 시도하세요.") }
                    if (stats != null) item {
                        val shape = RoundedCornerShape(20.dp)
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 5.dp).fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)).clickable { onLab("signals") }.padding(start = 16.dp, top = 13.dp, bottom = 13.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("🧪 실험실 · 내 집 마련 타이밍 신호 3가지", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("매매가·매수 심리·전세를 한 글로", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(stats?.series.orEmpty(), key = { it.id }) { s -> StatCard(s, stats?.sido.orEmpty()) { onOpen(s) } }
                    if (stats != null) item {
                        Text(
                            "자료: 한국부동산원 R-ONE, KB부동산 데이터허브 · 확인 ${ago(stats.updatedAt)}",
                            Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 지역 고르기: 시도 → 그 시도 전체 또는 시군구·권역. 고르면 그 지역의 지표 화면으로 */
@Composable
private fun RegionPicker(stats: Stats?, error: String?, onRegion: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (stats == null) { EmptyText(if (error == null) "불러오는 중…" else "통계를 받지 못했어요."); return }
    var sido by remember { mutableStateOf(prefs.statSido.takeIf { it in stats.sido } ?: stats.sido.firstOrNull().orEmpty()) }
    val children = remember(stats, sido) { stats.childrenOf(sido) }
    val sale = stats.series.firstOrNull { it.kind == "change" && it.region(children.firstOrNull() ?: sido) != null } ?: stats.series.firstOrNull { it.kind == "change" }
    LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item { PillRow { stats.sido.forEach { n -> ChoicePill(n, n == sido) { sido = n; prefs.statSido = n } } } }
        if (stats.parents.isEmpty()) item {
            Text("시·군·구 자료는 다음 통계 수집 뒤부터 보여요. 지금은 시도 단위만 만들 수 있어요.", fontSize = 13.sp, lineHeight = 19.sp, color = muted, modifier = Modifier.padding(6.dp))
        }
        items(listOf(sido) + children, key = { it }) { region ->
            val v = sale?.region(region)?.latest
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().shadow(3.dp, shape, ambientColor = Color(0x262563EB), spotColor = Color(0x262563EB)).clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainer).clickable { onRegion(region) }.padding(start = 16.dp, top = 13.dp, bottom = 13.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (region == sido) "$sido 전체" else stats.shortName(region), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (Stats.isZone(region)) Text("권역", fontSize = 11.sp, color = muted)
                }
                if (sale != null && v != null) Column(horizontalAlignment = Alignment.End) {
                    Text(sale.fmt(v), fontSize = 16.sp, fontWeight = FontWeight.Black, color = valueColor(sale, v))
                    Text("${sale.short} ${sale.cycle}", fontSize = 11.sp, color = muted)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted.copy(alpha = 0.7f), modifier = Modifier.size(26.dp))
            }
        }
        item { Text("자료: 한국부동산원 R-ONE 주간아파트가격동향, KB부동산", fontSize = 12.sp, color = muted, modifier = Modifier.padding(6.dp)) }
    }
}

@Composable
private fun StatCard(s: StatSeries, sido: List<String>, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 5.dp).fillMaxWidth()
            .shadow(5.dp, shape, ambientColor = Color(0x262563EB), spotColor = Color(0x262563EB))
            .clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(s.cycle, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp))
            }
            Text("${s.source} · ${s.dateLabel} 기준", fontSize = 12.sp, color = muted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf("전국", "서울", "수도권").mapNotNull { s.region(it) }.forEach { r ->
                    Column {
                        Text(r.name, fontSize = 12.sp, color = muted)
                        Text(s.fmt(r.latest), fontSize = 20.sp, fontWeight = FontWeight.Black, color = valueColor(s, r.latest))
                        val d = if (r.latest != null && r.prev != null) r.latest!! - r.prev!! else null
                        if (d != null) Text((if (d > 0) "▲ " else if (d < 0) "▼ " else "- ") + String.format("%.${s.digits}f", kotlin.math.abs(d)), fontSize = 11.sp, color = muted)
                    }
                }
            }
            val rank = s.regions.filter { it.name in sido && it.latest != null }.sortedByDescending { it.latest!! }
            if (rank.size >= 5) {
                Spacer(Modifier.height(8.dp))
                Text("최고 ${rank.first().name} ${s.fmt(rank.first().latest)} · 최저 ${rank.last().name} ${s.fmt(rank.last().latest)}", fontSize = 12.sp, color = muted, maxLines = 1)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted.copy(alpha = 0.7f), modifier = Modifier.size(26.dp))
    }
}

/** 통계 한 지표를 스레드용으로: 그래프 이미지(지역별 순위, 추이) + 글. 글은 숫자를 그대로 끼워 넣어 만듦 (AI 사용 안 함) */
@Composable
fun StatShareScreen(s: StatSeries, sido: List<String>, onBack: () -> Unit, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var text by remember(s.id) { mutableStateOf(StatText.thread(s, sido, prefs.casualTone)) }
    var pieces by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var files by remember { mutableStateOf<List<File>?>(null) }
    var zoom by remember { mutableStateOf<Int?>(null) }
    var showAutoHelp by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && sent) onSent() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler(onBack = onBack)

    LaunchedEffect(s.id) {
        val (p, f) = withContext(Dispatchers.Default) {
            val imgs = StatCards.render(ctx, s, sido)
            imgs to ThreadsKit.writeCache(ctx, "stat-${s.id}", imgs)
        }
        pieces = p; files = f
    }

    fun share() { files?.let { sent = true; ThreadsKit.upload(ctx, it, text) } }
    if (showAutoHelp) AlertDialog(
        onDismissRequest = { showAutoHelp = false },
        title = { Text("자동 입력 켜기") },
        text = { Text("접근성에서 '부동산뉴스파인더 · 스레드 자동 입력'을 켜면 스레드 새 글 화면에 본문이 자동으로 들어가요.\n\n켜지 않아도 본문은 복사돼 있어서 붙여넣기 하면 돼요.", fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = { TextButton(onClick = { showAutoHelp = false; ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("접근성 설정 열기") } },
        dismissButton = { TextButton(onClick = { showAutoHelp = false; share() }) { Text("그냥 올리기") } },
    )
    zoom?.let { i ->
        pieces.getOrNull(i)?.let { b ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { zoom = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(Color.Black).clickable { zoom = null }, contentAlignment = Alignment.Center) {
                    Image(remember(b) { b.asImageBitmap() }, "이미지 ${i + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text(s.title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(vertical = 14.dp)) {
                    Text("이미지 ${pieces.size}장", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    val w = (LocalConfiguration.current.screenWidthDp * 0.62f).dp
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(pieces) { i, b ->
                            Image(remember(b) { b.asImageBitmap() }, "이미지 ${i + 1}", contentScale = ContentScale.FillWidth,
                                modifier = Modifier.width(w).clip(RoundedCornerShape(10.dp)).clickable { zoom = i }.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
                        }
                    }
                    if (files == null) Text("그래프 만드는 중…", Modifier.padding(16.dp), color = muted)
                }
            }
            Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("스레드에 들어갈 글", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text("${text.length}/500", fontSize = 12.sp, color = if (text.length > 500) MaterialTheme.colorScheme.error else muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 21.sp))
                    Spacer(Modifier.height(8.dp))
                    Text("통계 숫자를 그대로 넣어 만든 글이에요. 자유롭게 고쳐 쓰세요.", fontSize = 12.sp, color = muted)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { ThreadsKit.copy(ctx, "본문", text) }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("복사하기")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(enabled = files != null, shape = CircleShape, modifier = Modifier.height(52.dp), onClick = {
                val n = ThreadsKit.saveToGallery(ctx, files!!, "renf_stat_${System.currentTimeMillis() / 1000}")
                Toast.makeText(ctx, if (n > 0) "사진 앱에 ${n}장 저장했어요 (RealEstateNews 앨범)" else "안드로이드 10 이상에서 저장할 수 있어요", Toast.LENGTH_SHORT).show()
            }) { Text("갤러리 저장") }
            GradientButton("스레드에 올리기", null, onClick = {
                if (files != null) {
                    ThreadsKit.copy(ctx, "본문", text)
                    if (ThreadsAuto.isEnabled(ctx)) share() else showAutoHelp = true
                }
            }, modifier = Modifier.weight(1f))
        }
    }
}

/** 네이버 로그인 상태 (앱 안 브라우저 저장소의 쿠키로 판단. 비밀번호는 다루지 않음) */
object NaverSession {
    fun loggedIn(): Boolean = runCatching {
        val c = android.webkit.CookieManager.getInstance().getCookie("https://nid.naver.com").orEmpty() + ";" +
            android.webkit.CookieManager.getInstance().getCookie("https://cafe.naver.com").orEmpty()
        c.contains("NID_AUT=") && c.contains("NID_SES=")
    }.getOrDefault(false)
}

/**
 * 네이버 로그인 화면: 사용자가 네이버 페이지에 직접 로그인. 앱은 비밀번호를 다루지 않음.
 * - '로그인 상태 유지'를 미리 체크해 둠 → 한 번 로그인하면 앱을 껐다 켜도 유지됨
 * - 아이디·비밀번호는 휴대폰의 자동 완성(삼성 패스·구글 비밀번호 관리자)이 채워줄 수 있음
 * - 로그인이 끝나면 화면을 자동으로 닫음
 */
@Composable
fun NaverLoginScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Column(Modifier.weight(1f)) {
                Text("네이버 로그인", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("'로그인 상태 유지'를 켠 채로 로그인하면 다음부터는 다시 묻지 않아요", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { android.webkit.CookieManager.getInstance().flush(); onBack() }) { Text("완료", fontWeight = FontWeight.Bold) }
        }
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                android.webkit.WebView(c).apply {
                    @Suppress("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.saveFormData = true
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                    val cm = android.webkit.CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView, url: String) {
                            if (url.contains("nid.naver.com")) {
                                // '로그인 상태 유지'가 꺼져 있으면 켜 둠 (세션이 앱을 꺼도 남도록)
                                view.evaluateJavascript(
                                    "(function(){try{['#nvlong','#keep','input[name=nvlong]','#login_keep'].forEach(function(s){var e=document.querySelector(s); if(e&&e.type==='checkbox'&&!e.checked) e.click();});}catch(x){}})()", null,
                                )
                            } else if (NaverSession.loggedIn()) {
                                // 로그인이 끝나 카페로 넘어옴 → 저장하고 닫기
                                cm.flush()
                                android.widget.Toast.makeText(ctx, "네이버에 로그인했어요.", android.widget.Toast.LENGTH_SHORT).show()
                                onBack()
                            }
                        }
                    }
                    loadUrl("https://nid.naver.com/nidlogin.login?url=" + java.net.URLEncoder.encode("https://m.cafe.naver.com/jaegebal", "UTF-8"))
                }
            },
        )
    }
}
