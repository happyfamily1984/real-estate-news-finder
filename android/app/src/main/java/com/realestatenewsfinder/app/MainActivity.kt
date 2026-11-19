package com.realestatenewsfinder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// 디자인 시안 색상
val Brand = Color(0xFF2563EB)
private val HotRed = Color(0xFFF0304F)
private val HotOrange = Color(0xFFFF7A1A)
private val Calm = Color(0xFF5B8DEF)
private val PickGreen = Color(0xFF12A150)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 글 스캔(전체 페이지 캡처)용: 첫 WebView가 만들어지기 전에 호출해야 화면 밖 부분까지 그려짐
        android.webkit.WebView.enableSlowWholeDocumentDraw()
        enableEdgeToEdge()
        UploadLog.load(this)
        HotPostWorker.schedule(this)
        runCatching { DealPollWorker.schedule(this, replace = false) }
        handleIntent(intent)
        setContent { AppTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 알림에서 넘어온 글이 있으면 앱 안에서 열도록 전달 */
    private fun handleIntent(intent: Intent?) {
        // 실거래 알림 → 그 단지 상세 화면
        intent?.getStringExtra(EXTRA_DEAL)?.let { hash ->
            intent.removeExtra(EXTRA_DEAL)
            DealTarget.parse(hash)?.let { pendingDeal.value = it }
        }
        val json = intent?.getStringExtra(EXTRA_POST) ?: return
        intent.removeExtra(EXTRA_POST)
        runCatching { PostStore.fromJson(org.json.JSONObject(json)) }.getOrNull()?.let { pendingPost.value = it }
    }

    companion object {
        const val EXTRA_POST = "post"
        const val EXTRA_DEAL = "deal"
        /** 실거래 알림을 눌러 열어야 할 단지 */
        val pendingDeal = mutableStateOf<DealTarget?>(null)
        /** 알림을 눌러 열어야 할 글 (App이 받아서 미리보기를 띄움) */
        val pendingPost = mutableStateOf<Post?>(null)
    }
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF7FA8FF), background = Color(0xFF0F1218), surface = Color(0xFF0F1218),
            surfaceContainer = Color(0xFF1A1E27), outlineVariant = Color(0xFF2B303C),
        )
    } else {
        lightColorScheme(
            primary = Brand, background = Color(0xFFF3F6FB), surface = Color(0xFFF3F6FB),
            surfaceContainer = Color.White, onSurfaceVariant = Color(0xFF7D8696), outlineVariant = Color(0xFFE1E6EF),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 실거래 내보내기 화면에 넘기는 값 */
data class DealShareArgs(val target: DealTarget, val aptNm: String, val umdNm: String, val type: String, val period: DealPeriod, val fresh: List<Deal>)

enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    HOME("뉴스", Icons.Filled.Home, Icons.Outlined.Home),
    GOV("정부·공공", Icons.Filled.AccountBalance, Icons.Outlined.AccountBalance),
    COMMUNITY("커뮤니티", Icons.Filled.Forum, Icons.Outlined.Forum),
    STATS("통계", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    SETTINGS("설정", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/** communityOnly: 조회수·댓글이 있는 커뮤니티 탭에서만 보이는 정렬 */
enum class SortBy(val label: String, val communityOnly: Boolean = false) {
    LATEST("최신"), SCORE("중요도"), PICK("스레드 추천"), VIEWS("조회수", true), COMMENTS("댓글", true)
}

/** 글 열기: 브라우저로 원문 열기 */
fun openPost(ctx: Context, p: Post) {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf(FeedRepository.cached(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            try {
                feed = FeedRepository.fetch(ctx)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "불러오기 실패"
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // 통계 탭: 처음 열 때 받아옴
    var stats by remember { mutableStateOf(StatsRepository.cached(ctx)) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var statsLoading by remember { mutableStateOf(false) }
    var statShare by remember { mutableStateOf<StatSeries?>(null) }
    var naverLogin by remember { mutableStateOf(false) }
    // 통계 탭 안의 화면들: 지역 지표, 실거래(시군구 단지 목록 → 단지 상세 → 내보내기)
    var statsMode by rememberSaveable { mutableIntStateOf(0) }
    var regionShare by remember { mutableStateOf<String?>(null) }
    var dealRegion by remember { mutableStateOf<String?>(null) }
    var dealComplex by remember { mutableStateOf<DealTarget?>(null) }
    var dealShare by remember { mutableStateOf<DealShareArgs?>(null) }
    // 실거래 활용: 내 돈으로 살 수 있는 집, 과거 가격 비교(단지를 먼저 고름), 분양가 vs 실거래
    var dealTool by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) } // 단지를 고르는 목적: "history"(과거 가격 비교), "funding"(자금 계산)
    var fundingOf by remember { mutableStateOf<Triple<DealTarget, String, String>?>(null) }
    var signalsOpen by remember { mutableStateOf(false) }
    var historyOf by remember { mutableStateOf<Triple<DealTarget, String, String>?>(null) }
    val dealFromNotification = MainActivity.pendingDeal.value
    LaunchedEffect(dealFromNotification) {
        if (dealFromNotification != null) {
            dealShare = null
            dealComplex = dealFromNotification
            MainActivity.pendingDeal.value = null
        }
    }
    fun refreshStats() {
        scope.launch {
            statsLoading = true
            try { stats = StatsRepository.fetch(ctx); statsError = null } catch (e: Exception) { statsError = e.message ?: "불러오기 실패" }
            statsLoading = false
        }
    }
    LaunchedEffect(tab) { if (tab == Tab.STATS && !statsLoading) refreshStats() }
    // 뒤로가기: 내보내기 → 미리보기 → 목록 → 뉴스 탭 → 종료 확인 (나중에 등록한 것이 먼저 동작)
    var askExit by remember { mutableStateOf(false) }
    BackHandler { if (tab != Tab.HOME) tab = Tab.HOME else askExit = true }
    if (askExit) {
        AlertDialog(
            onDismissRequest = { askExit = false },
            title = { Text("종료하시겠습니까?") },
            text = { Text("부동산뉴스파인더를 종료합니다.") },
            confirmButton = { TextButton(onClick = { askExit = false; (ctx as? android.app.Activity)?.finish() }) { Text("종료", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { askExit = false }) { Text("취소") } },
        )
    }

    // 머리의 계정 전환 버튼: 스레드를 열어 올릴 계정인지 확인하고, 아니면 전환
    val switchAccount: () -> Unit = {
        val account = Prefs(ctx).threadsAccount
        val problem = when {
            account.isBlank() -> "설정에서 올릴 스레드 계정을 먼저 적어주세요."
            !ThreadsAuto.isEnabled(ctx) -> "접근성에서 '부동산뉴스파인더 · 스레드 자동 입력'을 먼저 켜주세요."
            !ThreadsAuto.startSwitch(ctx, account) -> "스레드 앱을 열 수 없어요."
            else -> null
        }
        if (problem != null) Toast.makeText(ctx, problem, Toast.LENGTH_LONG).show()
    }
    val feedNews = remember(feed) { feed?.only("news") }
    val feedGov = remember(feed) { feed?.only("gov") }
    val feedCommunity = remember(feed) { feed?.only("community") }

    val favIds = emptySet<String>()
    // 내보내기(스레드용)로 보낼 글. 글을 길게 누르거나 미리보기에서 '내보내기'
    var exportPost by remember { mutableStateOf<Post?>(null) }
    val toExport: (Post) -> Unit = { p -> exportPost = p }
    // 글 누르면 앱 안에서 모바일 화면 스캔 이미지로 보기
    var preview by remember { mutableStateOf<Post?>(null) }
    val open: (Post) -> Unit = { preview = it }

    // 알림을 눌러 들어온 글 → 앱 안 미리보기로 열기
    val fromNotification = MainActivity.pendingPost.value
    LaunchedEffect(fromNotification) {
        if (fromNotification != null) {
            exportPost = null
            open(fromNotification)
            MainActivity.pendingPost.value = null
        }
    }

    if (preview != null) BackHandler { preview = null }
    if (exportPost != null) BackHandler { exportPost = null }


    var onboarding by remember { mutableStateOf(!Prefs(ctx).onboardingDone) }
    if (onboarding) {
        OnboardingScreen { onboarding = false }
        return
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(tab) { tab = it } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(feedNews, error, loading, favIds, ::refresh, open, toExport, onBell = switchAccount)
                Tab.GOV -> HomeScreen(feedGov, error, loading, favIds, ::refresh, open, toExport, onBell = switchAccount)
                Tab.COMMUNITY -> HomeScreen(feedCommunity, error, loading, favIds, ::refresh, open, toExport, onBell = switchAccount, community = true)
                Tab.STATS -> StatsScreen(
                    stats, statsError, statsLoading, ::refreshStats, onOpen = { statShare = it },
                    onRegion = { regionShare = it }, onDealRegion = { pickFor = null; dealRegion = it }, onDealOpen = { dealComplex = it },
                    onLab = { signalsOpen = true },
                    onDealTool = { id ->
                        if (id == "history" || id == "funding") {
                            // 단지를 먼저 골라야 함: 마지막으로 본 지역의 단지 목록을 열고, 없으면 아래에서 지역을 고르라고 알림
                            pickFor = id
                            val last = Prefs(ctx).dealRecent.firstOrNull()
                            if (last != null) dealRegion = last else Toast.makeText(ctx, "아래 '단지 찾기'에서 시·군·구를 고르면 단지를 선택할 수 있어요.", Toast.LENGTH_LONG).show()
                        } else dealTool = id
                    },
                    mode = statsMode, onMode = { statsMode = it },
                )
                Tab.SETTINGS -> SettingsScreen(feed, onBack = { tab = Tab.HOME }, onSetup = { onboarding = true }, onNaverLogin = { naverLogin = true })
            }
        }
    }
    // 글 미리보기(스캔 이미지)는 홈 위에 겹쳐서 띄움 → 돌아오면 목록 위치 그대로
    preview?.let { p -> key(p.id) {
        PreviewScreen(
            post = p,
            source = feed?.sourceById?.get(p.source),
            onExport = { toExport(p) }, // 미리보기는 그대로 두고 위에 띄움 → 뒤로가기로 한 단계씩 돌아옴
            onOpenSite = { openPost(ctx, p) },
            onBack = { preview = null },
            onNaverLogin = { naverLogin = true },
        )
    } }
    // 스레드용 내보내기 (미리보기의 '내보내기' 또는 글 길게 누르기)
    exportPost?.let { p -> key("x" + p.id) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ExportDetail(p, feed?.sourceById?.get(p.source), onBack = { exportPost = null },
                // 스레드로 보낸 뒤에는 내보내기·미리보기를 닫아, 앱에 돌아오면 목록이 보이게
                onSent = { exportPost = null; preview = null })
        }
    } }
    // 통계 지표를 스레드용으로 (통계 탭에서 지표를 누르면)
    statShare?.let { s -> key("s" + s.id) { StatShareScreen(s, stats?.sido.orEmpty(), onBack = { statShare = null }, onSent = { statShare = null }) } }
    // 지역 지표 (통계 > 지역별에서 지역을 누르면)
    regionShare?.let { r -> stats?.let { st -> key("r$r") { RegionShareScreen(st, r, onBack = { regionShare = null }, onSent = { regionShare = null }) } } }
    // 실거래: 단지 목록 → 단지 상세 → 스레드 내보내기 (위에 겹쳐 띄워서 뒤로가기로 한 단계씩)
    dealRegion?.let { code -> key("dr$code") {
        ComplexListScreen(code, onBack = { dealRegion = null; pickFor = null }, note = when (pickFor) { "history" -> "과거 가격과 비교할 단지를 고르세요"; "funding" -> "자금 계산할 단지를 고르세요"; else -> null }) { t, aptNm, umdNm ->
            when (pickFor) { "history" -> historyOf = Triple(t, aptNm, umdNm); "funding" -> fundingOf = Triple(t, aptNm, umdNm); else -> dealComplex = t }
        }
    } }
    dealComplex?.let { t -> key("dc" + t.toUri()) {
        ComplexDetailScreen(t, onBack = { dealComplex = null }, onHistory = { aptNm, umdNm -> historyOf = Triple(t, aptNm, umdNm) }) { aptNm, umdNm, type, period, fresh -> dealShare = DealShareArgs(t, aptNm, umdNm, type, period, fresh) }
    } }
    fundingOf?.let { (t, aptNm, umdNm) -> key("df" + t.complexId) { FundingScreen(t, aptNm, umdNm, onBack = { fundingOf = null }, onSent = { fundingOf = null }) } }
    if (signalsOpen) stats?.let { st -> SignalShareScreen(st, onBack = { signalsOpen = false }, onSent = { signalsOpen = false }) }
    historyOf?.let { (t, aptNm, umdNm) -> key("dh" + t.complexId) { HistoryScreen(t, aptNm, umdNm, onBack = { historyOf = null }, onSent = { historyOf = null }) } }
    when (dealTool) {
        "budget" -> BudgetScreen(onBack = { dealTool = null }, onSent = { dealTool = null })
        "presale" -> PresaleScreen(onBack = { dealTool = null }, onSent = { dealTool = null })
        "top" -> TopScreen(onBack = { dealTool = null }, onSent = { dealTool = null })
    }
    dealShare?.let { a -> key("ds" + a.target.toUri()) {
        DealShareScreen(a.target.lawdCd, a.target.complexId, a.aptNm, a.umdNm, a.type, a.period, a.fresh, onBack = { dealShare = null }, onSent = { dealShare = null })
    } }
    if (naverLogin) NaverLoginScreen(onBack = { naverLogin = false })
    }
}

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), ambientColor = Color(0x332563EB), spotColor = Color(0x332563EB))
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .navigationBarsPadding().padding(horizontal = 8.dp).height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val sel = t == current
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                    .background(if (sel) primary.copy(alpha = 0.07f) else Color.Transparent)
                    .clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(if (sel) t.on else t.off, null, tint = if (sel) primary else muted, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(3.dp))
                Text(t.label, fontSize = 12.sp, maxLines = 1, fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.Medium, color = if (sel) primary else muted)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(34.dp).height(3.dp).background(if (sel) primary else Color.Transparent, CircleShape))
            }
        }
    }
}

/** 상단: 하늘색 배경 + 아파트 단지 그림 + 로고 · 앱 이름 · 계정 전환 버튼 */
@Composable
fun Header(showDot: Boolean, onBell: () -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val dark = bg.red < 0.5f
    Box(Modifier.fillMaxWidth()) {
        // 그림은 상태 표시줄 뒤까지 채움
        Canvas(Modifier.matchParentSize()) { drawSkyline(dark, bg) }
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(128.dp).padding(start = 16.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.app_logo), null,
                Modifier.size(62.dp).shadow(8.dp, RoundedCornerShape(16.dp), ambientColor = Brand, spotColor = Brand).clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("부동산\n뉴스파인더", fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(3.dp))
                Text("실시간 부동산 뉴스를 한눈에", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
            // 오른쪽 위: 스레드 계정 전환 (올릴 계정이 아니면 그 계정으로 바꿈)
            Surface(onClick = onBell, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 4.dp, modifier = Modifier.align(Alignment.Top).padding(top = 14.dp).size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.SwitchAccount, "스레드 계정 전환", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
            }
        }
    }
}

/** 헤더 배경 그림: 하늘 그라데이션, 오른쪽 아래 아파트 단지와 나무. 글자와 겹치지 않게 오른쪽 40%에만 옅게 그림 */
private fun DrawScope.drawSkyline(dark: Boolean, bg: Color) {
    val w = size.width; val h = size.height
    val sky = if (dark) Color(0xFF16243F) else Color(0xFFD6E8FF)
    drawRect(Brush.verticalGradient(listOf(sky, bg)))
    val base = h * 0.94f
    // (왼쪽 x, 폭, 높이) — 폭은 화면 폭, 높이는 헤더 높이 기준 비율. 뒤쪽 동부터
    val towers = listOf(
        listOf(0.62f, 0.060f, 0.34f), listOf(0.945f, 0.07f, 0.40f), listOf(0.69f, 0.065f, 0.46f),
        listOf(0.875f, 0.07f, 0.52f), listOf(0.755f, 0.07f, 0.58f), listOf(0.815f, 0.075f, 0.68f),
    )
    towers.forEachIndexed { i, (x, tw, th) ->
        val left = x * w; val width = tw * w; val top = base - th * h
        val front = i >= 3
        val wall = (if (dark) Color(if (front) 0xFF33466C else 0xFF283756) else Color(if (front) 0xFFF4F8FF else 0xFFE3EEFC)).copy(alpha = if (front) 0.95f else 0.7f)
        val side = if (dark) Color(0xFF223050) else Color(0xFFC6DAF5)
        drawRect(side, Offset(left + width * 0.72f, top + width * 0.10f), Size(width * 0.28f, base - top))
        drawRect(wall, Offset(left, top), Size(width * 0.74f, base - top))
        val win = if (dark) Color(0xFF8FB6FF).copy(alpha = 0.40f) else Color(0xFF7FAEEE).copy(alpha = 0.55f)
        val rowH = width * 0.22f
        var y = top + rowH * 0.7f
        while (y < base - rowH) {
            for (c in 0..2) drawRect(win, Offset(left + width * (0.08f + c * 0.22f), y), Size(width * 0.14f, rowH * 0.45f))
            y += rowH
        }
    }
    // 나무: 건물 발치에 한 줄
    val tree = if (dark) Color(0xFF1F5D3A) else Color(0xFF7CCB86)
    val tree2 = if (dark) Color(0xFF184A2E) else Color(0xFF5DB96B)
    var x = 0.60f
    var k = 0
    while (x < 1.03f) {
        val r = w * (if (k % 2 == 0) 0.024f else 0.019f)
        drawCircle(if (k % 3 == 0) tree2 else tree, r, Offset(x * w, base - r * 0.2f))
        x += 0.036f; k++
    }
    // 바닥: 나무 아래를 배경색 띠로 덮어 건물이 바닥에서 자연스럽게 올라오게
    drawRect(bg, Offset(0f, base), Size(w, h - base))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    feed: Feed?, error: String?, loading: Boolean, favIds: Set<String>,
    onRefresh: () -> Unit, onOpen: (Post) -> Unit, onLongPress: (Post) -> Unit, onBell: () -> Unit,
    community: Boolean = false,
) {
    var source by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortBy.LATEST) } // 각 탭은 최신순이 기본
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }

    val posts = remember(feed, source, topic, sort, query) {
        val list = feed?.posts.orEmpty()
            .filter { source.isEmpty() || it.source == source }
            .filter { topic.isEmpty() || topic in it.tags }
            .filter { it.dupOf == null || query.isNotBlank() } // 같은 주제를 다룬 글은 대표 하나만 (검색할 때는 전부)
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        when (sort) {
            SortBy.SCORE -> list.sortedByDescending { it.score }
            SortBy.PICK -> list.sortedByDescending { it.pick }
            SortBy.VIEWS -> list.sortedByDescending { it.views ?: -1 }
            SortBy.COMMENTS -> list.sortedByDescending { it.comments ?: -1 }
            // 최신순: 우선 노출 소스(부동산스터디)는 정해진 시간만큼 새 글로 쳐서 위로
            SortBy.LATEST -> list.sortedByDescending { it.time + (feed?.sourceById?.get(it.source)?.boostHours ?: 0) * 3600_000L }
        }
    }
    val hasNew = remember(feed) { feed?.posts?.any { isNew(it) } == true }

    Column(Modifier.fillMaxSize()) {
        Header(hasNew, onBell)
        PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (community) {
                            // 커뮤니티 탭: 주제 대신 커뮤니티 종류로 고름
                            item { SourceChip("전체", null, source == "", ok = true, icon = Icons.Filled.GridView) { source = "" } }
                            items(feed?.sources.orEmpty()) { s ->
                                SourceChip(s.name, Color(s.color), source == s.id, s.ok) { source = if (source == s.id) "" else s.id }
                            }
                        } else {
                            // 주제 칩: 사람들이 많이 찾는 주제별로 모아 보기
                            item { SourceChip("전체", null, topic == "", ok = true, icon = Icons.Filled.GridView) { topic = "" } }
                            items(feed?.topics.orEmpty()) { t ->
                                SourceChip(t, null, topic == t, ok = true) { topic = if (topic == t) "" else t }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchPill(query, { query = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        // 필터 버튼: 정렬 · 출처
                        Box {
                            val active = (source.isNotEmpty() && !community) || sort != SortBy.LATEST
                            Surface(
                                onClick = { sortMenu = true }, shape = RoundedCornerShape(16.dp),
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                                shadowElevation = 3.dp, modifier = Modifier.size(48.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Tune, "정렬 · 출처", tint = if (active) Color.White else MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                }
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                MenuLabel("정렬")
                                SortBy.entries.filter { community || !it.communityOnly }.forEach {
                                    MenuChoice(it.label, sort == it) { sort = it; sortMenu = false }
                                }
                                if (!community) { // 커뮤니티 탭은 위쪽 칩으로 고름
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                    MenuLabel("출처")
                                    MenuChoice("전체", source.isEmpty()) { source = ""; sortMenu = false }
                                    feed?.sources.orEmpty().forEach { s ->
                                        MenuChoice(if (s.ok) s.name else "${s.name} ⚠", source == s.id) { source = s.id; sortMenu = false }
                                    }
                                }
                            }
                        }
                    }
                }
                item { StatusLine(feed, error) }

                if (posts.isEmpty()) {
                    item {
                        EmptyText(
                            when {
                                feed == null && error == null -> "불러오는 중…"
                                feed == null -> "데이터를 받지 못했어요. 아래로 당겨 다시 시도하세요."
                                else -> "표시할 글이 없어요"
                            },
                        )
                    }
                }
                items(posts, key = { it.id }) { p ->
                    PostCard(p, feed?.sourceById?.get(p.source), p.id in favIds, { onOpen(p) }, { onLongPress(p) }, showPick = sort == SortBy.PICK)
                }
            }
        }
    }
}

@Composable
fun EmptyText(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp,
    )
}

@Composable
private fun MenuLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
}

@Composable
private fun MenuChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }, onClick = onClick,
        trailingIcon = { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
    )
}

@Composable
fun SourceChip(label: String, dot: Color?, selected: Boolean, ok: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier.height(44.dp)
            .shadow(if (selected) 6.dp else 3.dp, shape, ambientColor = Color(0x402563EB), spotColor = Color(0x402563EB))
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF4F8BFF), Brand)), shape)
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainer, shape),
            )
            .clip(shape).clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (selected) Color.White else MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            if (dot != null) {
                Box(Modifier.size(8.dp).background(if (selected) Color.White else dot, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                if (ok) label else "$label ⚠", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = if (ok) 0.9f else 0.45f),
            )
        }
    }
}

@Composable
fun SearchPill(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .height(48.dp)
            .shadow(3.dp, CircleShape, ambientColor = Color(0x302563EB), spotColor = Color(0x302563EB))
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("제목 검색어를 입력하세요", color = muted, fontSize = 15.sp)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun StatusLine(feed: Feed?, error: String?) {
    // 실패한 곳은 이유도 같이 표시 (원인 파악용)
    // 실패한 곳은 이유별로 묶어서 짧게 (원인 파악용)
    val failed = feed?.sources?.filter { !it.ok }.orEmpty().groupBy { it.error ?: "오류" }
        .map { (reason, list) -> list.joinToString("·") { it.name } + " ($reason)" }
    val parts = buildList {
        if (feed != null) add("클라우드 수집 ${ago(feed.updatedAt)}")
        if (failed.isNotEmpty()) add("수집 실패: ${failed.joinToString(", ")}")
        if (error != null) add("⚠ $error")
    }
    if (parts.isEmpty()) return
    val color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.History, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(parts.joinToString(" · "), fontSize = 12.sp, lineHeight = 16.sp, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

fun scoreColor(score: Int) = when {
    score >= 85 -> HotRed
    score >= 70 -> HotOrange
    else -> Calm
}

/** 새 글 표시: 30분 안에 처음 수집됐고, 기사라면 발행된 지 3시간 안쪽 */
fun isNew(p: Post): Boolean {
    val now = System.currentTimeMillis()
    return now - p.firstSeenAt < 30 * 60_000 && now - p.time < 3 * 3600_000
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostCard(p: Post, src: Source?, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, showPick: Boolean = false) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // 이미 스레드로 보낸 글: 제목을 흐리게 하고 '올림' 표시
    val uploaded = p.id in UploadLog.sent
    val number = if (showPick) p.pick else p.score
    val accent = if (showPick) (if (p.pick >= 70) PickGreen else Calm) else scoreColor(p.score)
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .fillMaxWidth()
            .shadow(5.dp, shape, ambientColor = Color(0x262563EB), spotColor = Color(0x262563EB))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 왼쪽: 색 막대 + 점수 칸 (아래에 도시 실루엣)
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Box(Modifier.width(72.dp).fillMaxHeight().background(accent.copy(alpha = 0.08f))) {
            Canvas(Modifier.matchParentSize()) {
                val heights = listOf(0.16f, 0.26f, 0.20f, 0.34f, 0.24f, 0.30f, 0.18f)
                val bw = size.width / heights.size
                heights.forEachIndexed { i, hh ->
                    drawRoundRect(accent.copy(alpha = 0.16f), Offset(i * bw + bw * 0.1f, size.height * (1f - hh)), Size(bw * 0.8f, size.height * hh), CornerRadius(3f, 3f))
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$number", fontWeight = FontWeight.Black, fontSize = 27.sp, color = accent, lineHeight = 29.sp)
                Text(if (showPick) "추천" else "중요도", fontSize = 11.sp, color = muted, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Text(
                p.title, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (uploaded) muted else MaterialTheme.colorScheme.onSurface,
                // 한글이 낱말 중간에서 끊기지 않게 (지원하는 기기에서)
                style = LocalTextStyle.current.copy(lineBreak = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase)),
            )
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(p.outlet ?: src?.name ?: p.source, primary, Modifier.widthIn(max = 120.dp))
                if (uploaded) {
                    Spacer(Modifier.width(6.dp))
                    Text("✓ 올림", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(PickGreen).padding(horizontal = 6.dp, vertical = 2.dp))
                } else if (isNew(p)) Text("  NEW", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = HotRed, maxLines = 1)
                if (!showPick && p.pick >= 70) {
                    Spacer(Modifier.width(6.dp))
                    Row(Modifier.clip(RoundedCornerShape(7.dp)).background(PickGreen.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = PickGreen, modifier = Modifier.size(13.dp))
                        Text("추천", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PickGreen, maxLines = 1)
                    }
                }
                p.tags.firstOrNull()?.let { Text("  #$it", fontSize = 12.sp, color = muted, maxLines = 1, softWrap = false) }
                if (p.related > 0) { Spacer(Modifier.width(6.dp)); Tag("${p.related + 1}곳 보도", HotOrange) }
                p.views?.let { Stat(Icons.Outlined.Visibility, fmt(it)) }
                p.comments?.let { Stat(Icons.Outlined.ChatBubbleOutline, fmt(it)) }
                Text("  " + ago(p.time), fontSize = 12.sp, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted.copy(alpha = 0.7f), modifier = Modifier.padding(end = 8.dp).size(26.dp))
    }
}

/** 옅은 바탕의 둥근 꼬리표 (출처, N곳 보도) */
@Composable
private fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun Stat(icon: ImageVector, text: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Spacer(Modifier.width(8.dp))
    Icon(icon, null, tint = muted, modifier = Modifier.size(13.dp))
    Text(" $text", fontSize = 12.sp, color = muted, maxLines = 1)
}

fun fmt(n: Int): String = when {
    n >= 10000 -> String.format("%.1f만", n / 10000.0).replace(".0만", "만")
    else -> "%,d".format(n)
}

fun ago(ts: Long): String {
    val m = ((System.currentTimeMillis() - ts) / 60000).toInt()
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분 전"
        m < 1440 -> "${m / 60}시간 전"
        else -> "${m / 1440}일 전"
    }
}
