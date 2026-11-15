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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Newspaper
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
        HotPostWorker.schedule(this)
        handleIntent(intent)
        setContent { AppTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 알림에서 넘어온 글이 있으면 앱 안에서 열도록 전달 */
    private fun handleIntent(intent: Intent?) {
        val json = intent?.getStringExtra(EXTRA_POST) ?: return
        intent.removeExtra(EXTRA_POST)
        runCatching { PostStore.fromJson(org.json.JSONObject(json)) }.getOrNull()?.let { pendingPost.value = it }
    }

    companion object {
        const val EXTRA_POST = "post"
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

enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    HOME("뉴스", Icons.Filled.Newspaper, Icons.Outlined.Newspaper),
    GOV("정부·공공", Icons.Filled.AccountBalance, Icons.Outlined.AccountBalance),
    COMMUNITY("커뮤니티", Icons.Filled.Forum, Icons.Outlined.Forum),
    RECENT("최근 본", Icons.Outlined.History, Icons.Outlined.History),
    SETTINGS("설정", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/** communityOnly: 조회수·댓글이 있는 커뮤니티 탭에서만 보이는 정렬 */
enum class SortBy(val label: String, val communityOnly: Boolean = false) {
    SCORE("중요도"), PICK("스레드 추천"), LATEST("최신"), VIEWS("조회수", true), COMMENTS("댓글", true)
}

/** 글 열기: 브라우저로 열고 최근 본 글에 기록 */
fun openPost(ctx: Context, p: Post) {
    PostStore.recent(ctx).add(p)
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf(FeedRepository.cached(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // 즐겨찾기/최근 목록이 바뀌면 화면 갱신
    var storeVersion by remember { mutableIntStateOf(0) }
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
    if (tab != Tab.HOME) BackHandler { tab = Tab.HOME }

    val feedNews = remember(feed) { feed?.only("news") }
    val feedGov = remember(feed) { feed?.only("gov") }
    val feedCommunity = remember(feed) { feed?.only("community") }

    val favIds = emptySet<String>()
    // 내보내기(스레드용)로 보낼 글. 글을 길게 누르거나 미리보기에서 '내보내기'
    var exportPost by remember { mutableStateOf<Post?>(null) }
    val toExport: (Post) -> Unit = { p -> exportPost = p }
    // 글 누르면 앱 안에서 모바일 화면 스캔 이미지로 보기
    var preview by remember { mutableStateOf<Post?>(null) }
    val open: (Post) -> Unit = { PostStore.recent(ctx).add(it); storeVersion++; preview = it }

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
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(feedNews, error, loading, favIds, ::refresh, open, toExport, onBell = { tab = Tab.SETTINGS })
                Tab.GOV -> HomeScreen(feedGov, error, loading, favIds, ::refresh, open, toExport, onBell = { tab = Tab.SETTINGS })
                Tab.COMMUNITY -> HomeScreen(feedCommunity, error, loading, favIds, ::refresh, open, toExport, onBell = { tab = Tab.SETTINGS }, community = true)
                Tab.RECENT -> SavedScreen(
                    title = "최근 본 글",
                    posts = remember(storeVersion) { PostStore.recent(ctx).all() },
                    empty = "최근에 본 글이 없어요.",
                    feed = feed, favIds = favIds, onOpen = open, onLongPress = toExport,
                    action = "기록 지우기" to { PostStore.recent(ctx).clear(); storeVersion++ },
                )
                Tab.SETTINGS -> SettingsScreen(feed, onBack = { tab = Tab.HOME }, onSetup = { onboarding = true })
            }
        }
    }
    // 글 미리보기(스캔 이미지)는 홈 위에 겹쳐서 띄움 → 돌아오면 목록 위치 그대로
    preview?.let { p -> key(p.id) {
        PreviewScreen(
            post = p,
            source = feed?.sourceById?.get(p.source),
            onExport = { preview = null; toExport(p) },
            onOpenSite = { openPost(ctx, p) },
            onBack = { preview = null },
        )
    } }
    // 스레드용 내보내기 (미리보기의 '내보내기' 또는 글 길게 누르기)
    exportPost?.let { p -> key("x" + p.id) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ExportDetail(p, feed?.sourceById?.get(p.source), onBack = { exportPost = null })
        }
    } }
    }
}

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            val sel = t == current
            NavigationBarItem(
                selected = sel,
                onClick = { onSelect(t) },
                icon = { Icon(if (sel) t.on else t.off, null) },
                label = { Text(t.label, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun Header(showDot: Boolean, onBell: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).background(Brand, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Apartment, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("부동산뉴스파인더", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        IconButton(onClick = onBell) {
            Box {
                Icon(Icons.Filled.Notifications, "알림 설정", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                if (showDot) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(HotRed, CircleShape))
            }
        }
    }
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
    var sourceMenu by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SortBy.SCORE) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }

    val posts = remember(feed, source, topic, sort, query) {
        val list = feed?.posts.orEmpty()
            .filter { source.isEmpty() || it.source == source }
            .filter { topic.isEmpty() || topic in it.tags }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        when (sort) {
            SortBy.SCORE -> list.sortedByDescending { it.score }
            SortBy.PICK -> list.sortedByDescending { it.pick }
            SortBy.VIEWS -> list.sortedByDescending { it.views ?: -1 }
            SortBy.COMMENTS -> list.sortedByDescending { it.comments ?: -1 }
            SortBy.LATEST -> list.sortedByDescending { it.time }
        }
    }
    val hasNew = remember(feed) { feed?.posts?.any { isNew(it) } == true }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header(hasNew, onBell)
        PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 주제 칩: 사람들이 많이 찾는 주제별로 모아 보기
                        item { SourceChip("전체", null, topic == "", ok = true) { topic = "" } }
                        items(feed?.topics.orEmpty()) { t ->
                            SourceChip(t, null, topic == t, ok = true) { topic = if (topic == t) "" else t }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchPill(query, { query = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        // 매체(소스) 선택
                        Box {
                            val picked = feed?.sourceById?.get(source)
                            Surface(onClick = { sourceMenu = true }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.height(44.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)) {
                                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(picked?.name ?: "출처", fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                                    Text(" ▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                DropdownMenuItem(text = { Text("전체") }, onClick = { source = ""; sourceMenu = false })
                                feed?.sources.orEmpty().forEach { s ->
                                    DropdownMenuItem(text = { Text(if (s.ok) s.name else "${s.name} ⚠") }, onClick = { source = s.id; sourceMenu = false })
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box {
                            val pickSort = sort == SortBy.PICK
                            Surface(onClick = { sortMenu = true }, shape = CircleShape, color = (if (pickSort) PickGreen else Brand).copy(alpha = 0.12f), modifier = Modifier.height(44.dp)) {
                                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(sort.label, color = if (pickSort) PickGreen else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortBy.entries.filter { community || !it.communityOnly }.forEach {
                                    DropdownMenuItem(text = { Text(it.label) }, onClick = { sort = it; sortMenu = false })
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
fun SavedScreen(
    title: String, posts: List<Post>, empty: String, feed: Feed?, favIds: Set<String>,
    onOpen: (Post) -> Unit, onLongPress: (Post) -> Unit, action: Pair<String, () -> Unit>? = null,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (action != null && posts.isNotEmpty()) TextButton(onClick = action.second) { Text(action.first) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            if (posts.isEmpty()) item { EmptyText(empty) }
            items(posts, key = { it.id }) { p ->
                PostCard(p, feed?.sourceById?.get(p.source), p.id in favIds, { onOpen(p) }, { onLongPress(p) })
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
fun SourceChip(label: String, dot: Color?, selected: Boolean, ok: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .height(38.dp)
            .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(8.dp).background(if (selected) Color.White else dot, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                if (ok) label else "$label ⚠",
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = if (ok) 0.85f else 0.45f),
            )
        }
    }
}

@Composable
fun SearchPill(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("제목 검색", color = muted, fontSize = 15.sp)
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
    val failed = feed?.sources?.filter { !it.ok }?.map { s -> s.name + (s.error?.let { " ($it)" } ?: "") }.orEmpty()
    val parts = buildList {
        if (feed != null) add("클라우드 수집 ${ago(feed.updatedAt)}")
        if (failed.isNotEmpty()) add("수집 실패: ${failed.joinToString()}")
        if (error != null) add("⚠ $error")
    }
    if (parts.isEmpty()) return
    val color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.History, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(parts.joinToString(" · "), fontSize = 12.sp, color = color, maxLines = 4)
    }
}

fun scoreColor(score: Int) = when {
    score >= 85 -> HotRed
    score >= 75 -> HotOrange
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
    val number = if (showPick) p.pick else p.score
    val accent = if (showPick) (if (p.pick >= 70) PickGreen else Calm) else scoreColor(p.score)
    val srcColor = src?.let { Color(it.color) } ?: Color.Gray
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 왼쪽 색 막대
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.width(54.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$number", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = accent, lineHeight = 24.sp)
            Text(if (showPick) "추천" else "중요도", fontSize = 10.sp, color = muted, lineHeight = 12.sp)
        }
        Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                p.title, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.outlet ?: src?.name ?: p.source, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = srcColor, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(srcColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                if (isNew(p)) Text(" NEW", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = HotRed, maxLines = 1)
                if (!showPick && p.pick >= 70) Text("  ✍추천", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PickGreen, maxLines = 1)
                p.tags.firstOrNull()?.let { Text("  #$it", fontSize = 12.sp, color = muted, maxLines = 1) }
                if (p.related > 0) Text("  ${p.related + 1}곳 보도", fontSize = 12.sp, color = HotOrange, fontWeight = FontWeight.SemiBold, maxLines = 1)
                p.views?.let { Stat(Icons.Outlined.Visibility, fmt(it)) }
                p.comments?.let { Stat(Icons.Outlined.ChatBubbleOutline, fmt(it)) }
                Text("  " + ago(p.time), fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted.copy(alpha = 0.6f), modifier = Modifier.padding(end = 6.dp))
    }
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
