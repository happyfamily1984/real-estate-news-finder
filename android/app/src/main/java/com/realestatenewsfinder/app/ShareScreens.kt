package com.realestatenewsfinder.app

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** 고르는 알약 (선택하면 파란 그라데이션) */
@Composable
fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(38.dp)
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF4F8BFF), Brand)), CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            .clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun PillRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * 스레드용 내보내기 공통 화면: (고르기) → 이미지 → 글 → 갤러리 저장 / 스레드에 올리기.
 * render가 null이면 자료를 아직 받는 중. renderKey가 바뀌면 이미지를 다시 만듦.
 */
@Composable
fun ThreadShareScreen(
    title: String, cacheId: String, hint: String,
    text: String, onText: (String) -> Unit,
    renderKey: Any?, render: (() -> List<Bitmap>)?,
    status: String? = null,
    onBack: () -> Unit, onSent: () -> Unit,
    /** 블로그 제목·태그에 쓸 이름: 검색에 걸리게 지역·단지 이름이 앞에 오는 것 (예: "안양시 동안구 최고 실거래 단지"). 없으면 화면 제목 */
    blogName: String? = null,
    /** 블로그 제목·본문을 미리 써 둔 경우(보고서): 화면 제목으로 만들지 않고 이것을 씀 */
    blogTitleText: String? = null, blogBodyText: String? = null,
    /** 앱이 만든 첫 장이 제목만 있는 표지인지(보고서). 그러면 첨부한 표지가 그 자리를 대신함 */
    coverFirst: Boolean = false,
    /** 자료 기준일 (보고서 날짜 등). 없으면 글에 적힌 날짜, 그것도 없으면 오늘 */
    dataDate: LocalDate? = null,
    controls: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var pieces by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var files by remember { mutableStateOf<List<File>?>(null) }
    var zoom by remember { mutableStateOf<Int?>(null) }
    var showAutoHelp by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    // 올릴 곳(스레드·오픈톡·블로그)에 맞춰 글을 다듬음. 블로그는 제목 + 소제목이 있는 글로 바꿔서 따로 고칠 수 있게 함
    val platform = remember { Platform.load(ctx); Platform.current }
    val blog = platform == Platform.BLOG
    val blogKey = blogName?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null } ?: title
    // 블로그 글 틀: 날짜·글·계정에 따라 달라짐 (두 계정은 항상 다른 틀). '다른 틀로'를 누르면 다음 틀
    Account.load(ctx)
    val account = Account.current
    val today = remember { LocalDate.now(KST) }
    var shift by remember { mutableIntStateOf(0) }
    val blogId = "$cacheId|$blogKey"
    val style = BlogMix.styleIndex(blogId, account, today, shift)
    var blogTitle by remember(blogKey, style) { mutableStateOf(blogTitleText ?: BlogText.title(blogKey, today, style)) }
    val blogBase = remember(if (blogBodyText != null) blogBodyText else text, blogKey, style) { BlogMix.restyle(blogBodyText ?: BlogText.body(blogKey, text, style), style) }
    var blogBody by remember(blogBase) { mutableStateOf(blogBase) }
    // 중개사 한마디: 직접 쓴 2~3줄이 본문 단락으로 들어감
    var opinion by remember { mutableStateOf("") }
    // 평가: 숫자가 든 단락마다 AI가 한 줄 풀이를 씀 (무료 키). 글을 손으로 고친 뒤에는 덮어쓰지 않음
    var evalNote by remember { mutableStateOf("") }
    var evalRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(blog, blogBase, evalRun) {
        if (!blog) return@LaunchedEffect
        if (BlogMix.hasEvals(blogBase) || BlogMix.evalTargets(blogBase).isEmpty()) { evalNote = ""; return@LaunchedEffect }
        if (!Summarizer.hasKey(ctx)) { evalNote = "설정에 무료 AI 키(Gemini 등)를 넣으면 숫자 단락마다 평가가 들어가요."; return@LaunchedEffect }
        evalNote = "working"
        delay(1200) // 고르기를 바꾸는 동안에는 기다림
        val ev = Summarizer.evaluate(ctx, blogBase)
        evalNote = when {
            ev == null -> "평가를 받지 못했어요: ${Summarizer.lastError() ?: "알 수 없는 오류"}"
            ev.isEmpty() -> "넣을 만한 평가가 없었어요. '평가 다시 받기'를 눌러 보세요."
            blogBody != blogBase -> "글을 고친 뒤라 평가를 넣지 않았어요. '평가 다시 받기'를 누르면 처음 글에 평가를 넣어요."
            else -> { blogBody = BlogMix.withEvals(blogBase, ev, style); "평가 ${ev.size}개를 넣었어요. 올리기 전에 읽고 고쳐 주세요." }
        }
    }
    val outText = if (blog) BlogMix.withOpinion(blogBody, opinion, style) else text
    var dupAsk by remember { mutableStateOf<BlogLog.Sent?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && sent) onSent() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler(onBack = onBack)

    // 첫 이미지: 외부 AI로 만들 프롬프트(날짜·글·계정·'다른 프롬프트'에 따라 꼴이 바뀜) + 만든 이미지 첨부
    var coverShift by remember { mutableIntStateOf(0) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    val coverPrompt = remember(title, blogKey, text, coverShift, Account.current) {
        // 큰 제목은 화면 제목(단지 이름·주제), 지역은 배경을 고를 때만 씀
        val all = if (blogBodyText != null) blogBodyText + "\n" + text else text
        val today = LocalDate.now(KST)
        CoverPrompt.build(title, all, CoverPrompt.seed(cacheId + blogKey, Account.current, today, coverShift), context = blogKey, asOf = dataDate ?: CoverPrompt.asOf(all, today) ?: today)
    }
    LaunchedEffect(renderKey, render != null, cover) {
        files = null
        if (render == null) { pieces = emptyList(); return@LaunchedEffect }
        val (p, f) = withContext(Dispatchers.Default) {
            val made = runCatching { render() }.getOrDefault(emptyList())
            val imgs = cover?.let { c -> listOf(CoverPrompt.withMark(c, Watermark.of(ctx))) + if (coverFirst) made.drop(1) else made } ?: made
            imgs to ThreadsKit.writeCache(ctx, cacheId, imgs)
        }
        pieces = p; files = f
    }

    fun shareNow() { files?.let { sent = true; if (blog) BlogLog.mark(ctx, blogId, account); PlatformKit.send(ctx, platform, it, if (blog) blogTitle else "", outText) } }
    // 같은 자료를 다른 계정 블로그에 올린 적이 있으면 먼저 알려 줌
    fun share() { val other = if (blog) BlogLog.otherAccount(ctx, blogId, account) else null; if (other != null) dupAsk = other else shareNow() }
    dupAsk?.let { d -> BlogDupDialog(d, onGo = { dupAsk = null; shareNow() }, onCancel = { dupAsk = null }) }
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
    val ready = files != null && pieces.isNotEmpty()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (controls != null) Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = controls)
            }
            Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Text("이미지 ${pieces.size}장", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(10.dp))
                    val w = (LocalConfiguration.current.screenWidthDp * 0.62f).dp
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(pieces) { i, b ->
                            Image(remember(b) { b.asImageBitmap() }, "이미지 ${i + 1}", contentScale = ContentScale.FillWidth,
                                modifier = Modifier.width(w).clip(RoundedCornerShape(10.dp)).clickable { zoom = i }.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
                        }
                    }
                    val note = status ?: if (files == null) "이미지 만드는 중…" else if (pieces.isEmpty()) "이미지로 만들 자료가 없어요. 글만 복사해서 쓸 수 있어요." else null
                    if (note != null) Text(note, Modifier.padding(16.dp), color = muted, fontSize = 14.sp)
                    if (render != null) {
                        Spacer(Modifier.height(14.dp))
                        CoverBox(coverPrompt, cover, onAttach = { cover = it }, onClear = { cover = null }, onNext = { coverShift++ }, replaces = coverFirst)
                    }
                }
            }
            Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${platform.label}에 들어갈 글", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        if (platform == Platform.THREADS) Text("${text.length}/500", fontSize = 12.sp, color = if (text.length > 500) MaterialTheme.colorScheme.error else muted)
                        else Text("${outText.length}자", fontSize = 12.sp, color = muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (blog) {
                        OutlinedTextField(blogTitle, { blogTitle = it }, Modifier.fillMaxWidth(), label = { Text("제목") }, textStyle = LocalTextStyle.current.copy(fontSize = 15.sp))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(blogBody, { blogBody = it }, Modifier.fillMaxWidth(), label = { Text("본문") }, textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 21.sp))
                        Spacer(Modifier.height(8.dp))
                        BlogExtras(opinion, { opinion = it }, style, account, evalNote,
                            onNextStyle = { shift++ }, onEvalAgain = { blogBody = blogBase; evalRun++ })
                    } else OutlinedTextField(text, onText, Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 21.sp))
                    Spacer(Modifier.height(8.dp))
                    Text(hint + when (platform) {
                        Platform.BLOG -> "\n블로그용으로 제목·소제목·태그를 붙였어요. 올리면 네이버 블로그 글쓰기 화면에 사진이 들어가고, 본문은 붙여넣기 하면 돼요."
                        Platform.OPENTALK -> "\n올리면 카카오톡에서 오픈톡 방을 고르는 화면이 열려요. 사진을 보낸 뒤 글을 붙여넣기 하세요."
                        else -> ""
                    }, fontSize = 12.sp, lineHeight = 17.sp, color = muted)
                    Spacer(Modifier.height(6.dp))
                    if (blog) OutlinedButton(onClick = { ThreadsKit.copy(ctx, "제목", blogTitle) }) { Text("제목 복사") }
                    Button(onClick = { ThreadsKit.copy(ctx, "본문", outText) }) {
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
            OutlinedButton(enabled = ready, shape = CircleShape, modifier = Modifier.height(52.dp), onClick = {
                val n = ThreadsKit.saveToGallery(ctx, files!!, "renf_${cacheId.hashCode().toUInt()}_${System.currentTimeMillis() / 1000}")
                Toast.makeText(ctx, if (n > 0) "사진 앱에 ${n}장 저장했어요 (RealEstateNews 앨범)" else "안드로이드 10 이상에서 저장할 수 있어요", Toast.LENGTH_SHORT).show()
            }) { Text("갤러리 저장") }
            GradientButton("${platform.label}에 올리기", null, onClick = {
                if (ready) {
                    ThreadsKit.copy(ctx, "본문", outText)
                    if (platform != Platform.THREADS || ThreadsAuto.isEnabled(ctx)) share() else showAutoHelp = true
                }
            }, modifier = Modifier.weight(1f))
        }
    }
}

/** 지역 하나의 지표를 스레드용으로: 넣을 지표를 고르면 추이·순위 이미지와 글이 만들어짐 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegionShareScreen(stats: Stats, region: String, onBack: () -> Unit, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val available = remember(stats, region) { stats.seriesFor(region) }
    // 기본: 그 지역 자체 자료가 있는 지표 (없으면 상위 지역 자료 전부)
    var picked by remember(region) {
        mutableStateOf(available.filter { it.second == region }.map { it.first.id }.ifEmpty { available.map { it.first.id } }.take(4).toSet())
    }
    val series = available.map { it.first }.filter { it.id in picked }
    var text by remember(region, picked) { mutableStateOf(RegionText.thread(stats, region, series, prefs.casualNow)) }
    ThreadShareScreen(
        title = "$region 지표", cacheId = "region-$region", hint = "통계 숫자를 그대로 넣어 만든 글이에요. 자유롭게 고쳐 쓰세요.", blogName = "$region 아파트 시세 지표",
        text = text, onText = { text = it },
        renderKey = picked to region, render = { StatCards.renderRegion(ctx, stats, region, series) },
        status = if (series.isEmpty()) "넣을 지표를 하나 이상 골라주세요." else null,
        onBack = onBack, onSent = onSent,
    ) {
        Text("넣을 지표", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { (s, at) ->
                val src = if (s.source.startsWith("KB")) "KB" else "부동산원"
                ChoicePill("$src ${s.short}" + (if (at != region) " · ${stats.shortName(at)}" else ""), s.id in picked) {
                    picked = if (s.id in picked) picked - s.id else picked + s.id
                }
            }
        }
        if (available.any { it.second != region }) {
            Text("'· 지역명'이 붙은 지표는 $region 단위 자료가 없어서 가장 가까운 상위 지역 값이에요.", fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 단지 실거래를 스레드용으로. 유형(매매·전세·월세)과 기간(1개월~5년)을 고름.
 * newKeys가 있으면 '새 실거래 분석'(이전 평균과 비교)을 기본으로 보여줌.
 */
@Composable
fun DealShareScreen(
    lawdCd: String, complexId: String, aptNm: String, umdNm: String,
    initialType: String, initialPeriod: DealPeriod, fresh: List<Deal>,
    onBack: () -> Unit, onSent: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val core = remember { DealCore.get(ctx) }
    val regionName = remember { core.regions.get(lawdCd)?.optString("fullName").orEmpty() }
    val freshValid = remember(fresh) { fresh.filter { !it.canceled } }
    var type by remember { mutableStateOf(freshValid.firstOrNull()?.type ?: initialType) }
    var period by remember { mutableStateOf(initialPeriod) }
    var analyze by remember { mutableStateOf(freshValid.isNotEmpty()) }
    var deals by remember { mutableStateOf<List<Deal>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    val newKeys = remember(analyze, type, freshValid) { if (analyze) freshValid.filter { it.type == type }.map { it.key }.toSet() else emptySet() }

    LaunchedEffect(type, period) {
        deals = null; error = null
        try {
            val today = core.molit.today()
            val all = withContext(Dispatchers.IO) { core.service.getDeals(lawdCd, complexId, type, period.fetchMonths) }
            deals = all.filter { it.date >= period.since(today) }
        } catch (e: Exception) {
            error = e.message ?: "실거래 자료를 받지 못했어요"
        }
    }
    // 새 거래가 기간 밖(오래된 계약)이라 목록에 없으면 비교용으로 더해 줌
    val shown = remember(deals, newKeys) { deals?.let { d -> d + freshValid.filter { it.key in newKeys && d.none { x -> x.key == it.key } } } }
    LaunchedEffect(shown, analyze, type, period) {
        val d = shown ?: return@LaunchedEffect
        text = if (newKeys.isNotEmpty()) DealText.fresh(aptNm, regionName, umdNm, freshValid.filter { it.type == type }, d, period, prefs.casualNow)
        else DealText.summary(aptNm, regionName, umdNm, type, period, d, prefs.casualNow)
    }
    val place = listOf(regionName.substringAfter(' ', regionName), umdNm).filter { it.isNotBlank() }.joinToString(" ")
    ThreadShareScreen(
        title = aptNm, cacheId = "deal-$lawdCd-$complexId", hint = "국토부 실거래 숫자를 그대로 넣어 만든 글이에요. 자유롭게 고쳐 쓰세요.", blogName = "$regionName $umdNm $aptNm 실거래가",
        text = text, onText = { text = it },
        renderKey = Triple(shown, newKeys, type),
        render = shown?.let { d -> { DealCards.render(ctx, DealCards.Input(aptNm, place, type, period, d, newKeys, core.molit.today())) } },
        status = error?.let { "⚠ $it" } ?: if (shown == null) (if (period.months >= 36) "${period.label}치 실거래를 받는 중… 처음에는 시간이 걸려요" else "실거래 받는 중…") else null,
        onBack = onBack, onSent = onSent,
    ) {
        if (freshValid.isNotEmpty()) {
            Text("만들 내용", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            PillRow {
                ChoicePill("새 실거래 분석", analyze) { analyze = true; freshValid.firstOrNull()?.let { type = it.type } }
                ChoicePill("기간 요약", !analyze) { analyze = false }
            }
        }
        Text("거래 유형", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        PillRow { Molit.TYPES.forEach { (t, label) -> ChoicePill("$label 실거래가", type == t) { type = t } } }
        Text(if (analyze) "비교할 기간" else "기간", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        PillRow { DealPeriod.entries.forEach { p -> ChoicePill(p.label, period == p) { period = p } } }
    }
}


/**
 * 블로그 글을 남의 글·어제 글과 다르게 만드는 칸: 중개사 한마디(직접 쓰기) + 지금 틀 + 평가 상태.
 * onEvalAgain이 null이면 평가 줄을 보여 주지 않음(뉴스 글은 AI가 본문을 쓸 때 평가까지 씀).
 */
@Composable
fun BlogExtras(
    opinion: String, onOpinion: (String) -> Unit, style: Int, account: Int, evalNote: String,
    onNextStyle: () -> Unit, onEvalAgain: (() -> Unit)?, nextLabel: String = "다른 틀로",
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val st = BlogMix.style(style)
    OutlinedTextField(opinion, onOpinion, Modifier.fillMaxWidth(), label = { Text("중개사 한마디 (직접 쓰기, 2~3줄)") },
        placeholder = { Text("예: 요즘 이 평형은 전세 문의가 매매보다 많아요.", fontSize = 14.sp) }, minLines = 2,
        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 21.sp))
    Text(
        if (opinion.isBlank()) "비워 두면 숫자만 바뀐 글이 돼요. 직접 본 것·들은 것을 2~3줄 쓰면 '${st.mark} ${st.opinion}' 단락으로 ${if (st.opinionFirst) "첫 문단 뒤" else "참고 앞"}에 들어가요."
        else "올릴 때 '${st.mark} ${st.opinion}' 단락으로 ${if (st.opinionFirst) "첫 문단 뒤" else "참고 앞"}에 들어가요.",
        fontSize = 12.sp, lineHeight = 17.sp, color = if (opinion.isBlank()) MaterialTheme.colorScheme.error else muted, modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("글 틀 ${Math.floorMod(style, BlogMix.STYLES.size) + 1}/${BlogMix.STYLES.size} · ${Watermark.ACCOUNTS.getOrElse(account) { "" }} 계정용 (계정마다 다른 틀)", fontSize = 12.sp, lineHeight = 17.sp, color = muted, modifier = Modifier.weight(1f))
        TextButton(onClick = onNextStyle) { Text(nextLabel, fontSize = 13.sp) }
    }
    if (onEvalAgain != null && evalNote.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        if (evalNote == "working") { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (evalNote == "working") "숫자 단락마다 평가를 쓰는 중…" else evalNote, fontSize = 12.sp, lineHeight = 17.sp, color = muted, modifier = Modifier.weight(1f))
        if (evalNote != "working") TextButton(onClick = onEvalAgain) { Text("평가 다시 받기", fontSize = 13.sp) }
    }
}

/** 같은 자료를 다른 계정 블로그에 올린 적이 있을 때 */
@Composable
fun BlogDupDialog(d: BlogLog.Sent, onGo: () -> Unit, onCancel: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel,
    title = { Text("다른 계정 블로그에 올린 자료예요") },
    text = {
        Text("${UploadLog.label(d.at)}에 ${Watermark.ACCOUNTS.getOrElse(d.account) { "다른" }} 계정으로 올렸어요.\n\n같은 글이 두 블로그에 있으면 한쪽이 비슷한 글로 묶여 검색에서 빠질 수 있어요. " +
            "글 틀은 계정에 맞게 바꿔 뒀지만 숫자는 같으니, 중개사 한마디를 다르게 쓰고 올리는 것이 좋아요.", fontSize = 14.sp, lineHeight = 20.sp)
    },
    confirmButton = { TextButton(onClick = onGo) { Text("그래도 올리기", fontWeight = FontWeight.Bold) } },
    dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
)
