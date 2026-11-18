package com.realestatenewsfinder.app

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** header: 첫 이미지가 기사 머리 부분 캡처인지 (아니면 직접 그린 표지) */
private data class ExportResult(val pieces: List<Bitmap>, val files: List<File>, val header: Boolean = false)

/**
 * 스레드용 내보내기.
 * 기본은 직접 만든 카드 이미지(표지 + 핵심 정리) + 요약 글. 기사 화면은 캡처해 올리지 않음.
 * 커뮤니티 글은 원문 캡처(분할 이미지)로 바꿀 수 있음.
 */
@Composable
fun ExportDetail(post: Post, source: Source?, onBack: () -> Unit, onSent: () -> Unit = {}) {
    val ctx = LocalContext.current
    // 스레드로 보내고 앱이 뒤로 가면 이 화면을 닫음 → 다시 앱을 열면 목록이 보임
    var sent by remember { mutableStateOf(false) }
    // 이미 스레드로 보낸 글인지 (중복 업로드 막기). 보낼 때 기록하고, 다시 올리려 하면 한 번 물어봄
    LaunchedEffect(Unit) { UploadLog.load(ctx) }
    val uploadedAt = UploadLog.sent[post.id]
    var askAgain by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && sent) onSent() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler(onBack = onBack)
    val prefs = remember { Prefs(ctx) }
    val kind = source?.kind ?: "news"
    val sourceName = post.outlet ?: source?.name ?: post.source
    var attempt by remember { mutableIntStateOf(0) }
    var scan by remember(attempt) { mutableStateOf<ScanState>(ScanState.Loading(0, "페이지 여는 중")) }
    // 커뮤니티 글은 원문 캡처(작성자 모자이크) + 요약이 기본. 뉴스·정부 글은 카드
    var useCapture by remember { mutableStateOf(kind == "community") }
    var mosaicOn by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ExportResult?>(null) }

    val basic = remember(post) { Summarizer.basic(post) }
    var thread by remember { mutableStateOf(basic.thread) }
    var headline by remember { mutableStateOf(basic.headline) }
    var pointsText by remember { mutableStateOf(basic.points.joinToString("\n")) }
    var summaryState by remember { mutableStateOf("") } // "", "working", "done", 또는 안내·실패 사유
    var summaryRun by remember { mutableIntStateOf(0) }
    var zoomIndex by remember { mutableStateOf<Int?>(null) }
    var showAutoHelp by remember { mutableStateOf(false) }
    // 뉴스·정부 글: 본문 끝에 원문 기사 링크 (켜고 끌 수 있음)
    var includeLink by remember { mutableStateOf(prefs.includeLink) }
    val scanned = (scan as? ScanState.Done)?.result
    val link = remember(scanned?.finalUrl) { articleLink(scanned?.finalUrl, post.url) }
    val linkOn = kind != "community" && includeLink
    val outText = if (linkOn) thread.trimEnd() + "\n\n" + link else thread

    if (showAutoHelp) {
        val send = { showAutoHelp = false; result?.files?.let { sent = true; UploadLog.mark(ctx, post.id); ThreadsKit.upload(ctx, it, outText) }; Unit }
        AlertDialog(
            onDismissRequest = { showAutoHelp = false },
            title = { Text("자동 입력 켜기") },
            text = {
                Text(
                    "접근성에서 '부동산뉴스파인더 · 스레드 자동 입력'을 켜면 스레드 새 글 화면에 본문이 자동으로 입력돼요.\n\n" +
                        "켜지지 않고 '제한된 설정'이라고 나오면: 휴대폰 설정 > 애플리케이션 > 부동산뉴스파인더 > 오른쪽 위 ⋮ > '제한된 설정 허용'을 먼저 눌러주세요.\n\n" +
                        "켜지 않아도 본문은 복사돼 있어서 붙여넣기 하면 돼요.",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAutoHelp = false
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("접근성 설정 열기") }
            },
            dismissButton = { TextButton(onClick = send) { Text("그냥 올리기") } },
        )
    }

    val done = (scan as? ScanState.Done)?.result
    val scanEnded = scan !is ScanState.Loading

    // 원문을 읽으면 자동으로 요약 (Gemini → Groq → OpenRouter). '다시 요약'을 누르면 재실행
    LaunchedEffect(scanEnded, summaryRun) {
        if (!scanEnded) return@LaunchedEffect
        if (summaryRun == 0 && summaryState == "done") return@LaunchedEffect
        if (!Summarizer.hasKey(ctx)) { summaryState = "AI 요약을 쓰려면 설정에서 무료 AI 키(Gemini 등)를 넣어주세요. 지금은 제목과 기사 앞부분만 들어가요."; return@LaunchedEffect }
        val body = done?.text.orEmpty()
        if (body.length < 80 && post.summary.isNullOrBlank() && post.details.isEmpty()) { summaryState = "원문 내용을 읽지 못했어요. '다시 읽기'를 누르거나 직접 고쳐주세요."; return@LaunchedEffect }
        summaryState = "working"
        val sum = Summarizer.summarize(ctx, post, kind, sourceName, body)
        if (sum != null) {
            // 표지 제목은 기사 제목 그대로 둠 (AI가 주제를 바꾸지 못하게)
            // 공고(청약·LH)는 카드 내용을 상세 페이지에서 뽑은 정보 그대로 둠 (AI가 줄이거나 바꾸지 않게)
            thread = sum.thread; if (post.details.isEmpty()) pointsText = sum.points.joinToString("\n"); summaryState = "done"
        } else summaryState = "요약 실패: ${Summarizer.lastError() ?: "알 수 없는 오류"}"
    }

    // 이미지 만들기: 카드(기본) 또는 원문 캡처 분할. 내용을 고치면 잠시 뒤 다시 그림
    LaunchedEffect(useCapture, mosaicOn, done, headline, pointsText, scanEnded) {
        // 캡처를 기다리는 중이면 그대로 둠. 원문을 끝내 못 읽었으면 카드로 대신함
        if (useCapture && done == null && !scanEnded) return@LaunchedEffect
        if (!useCapture) delay(500)
        result = withContext(Dispatchers.Default) {
            var header = false
            val pieces = if (useCapture && done != null) {
                val masked = if (mosaicOn) ThreadsKit.mosaic(done.bitmap, done.authorRects) else done.bitmap
                val c = ThreadsKit.cropRect(masked, done.contentRect)
                val body = Bitmap.createBitmap(masked, c.left, c.top, c.width(), c.height())
                // 장수를 줄이려고 한 장을 길게 (가로의 3배까지 한 장, 여러 장이면 2.4배 안팎). 여백에서만 잘라 내용이 끊기지 않게
                ThreadsKit.split(body, done.imageRects.map { (it.top - c.top)..(it.bottom - c.top) }, maxRatio = 3f, idealRatio = 2.4f)
            } else {
                val meta = CardMaker.Meta(post.tags.firstOrNull() ?: kindLabel(kind), sourceName, post.time, Watermark.of(ctx))
                val points = pointsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                // 뉴스·정부 글: 1번 이미지는 기사 머리 부분(매체명·제목·날짜·요약) 캡처, 그다음이 핵심 정리
                val head = if (kind != "community" && done != null) runCatching { CardMaker.articleHeader(done.bitmap, done.titleRect, done.imageRects, meta) }.getOrNull() else null
                if (head != null) { header = true; listOf(head) + CardMaker.pointCards(post.title, points, meta) }
                else CardMaker.render(post.title, Summary(thread, headline, points), meta)
            }
            ExportResult(pieces, ThreadsKit.writeCache(ctx, post.id, pieces), header)
        }
    }

    // 사진을 누르면 전체 화면 미리보기 (좌우로 넘기기, 누르면 닫힘)
    val pieces = result?.pieces.orEmpty()
    zoomIndex?.let { start ->
        if (pieces.isNotEmpty()) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { zoomIndex = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                val pager = rememberPagerState(initialPage = start.coerceIn(0, pieces.size - 1)) { pieces.size }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                        val img = remember(pieces[page]) { pieces[page].asImageBitmap() }
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clickable { zoomIndex = null }, contentAlignment = Alignment.Center) {
                            Image(img, "이미지 ${page + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Text("${pager.currentPage + 1} / ${pieces.size}", color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp).background(Color(0x99000000), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
        }
    }

    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("스레드용 만들기", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        }
        // 이미 스레드로 보낸 글이면 맨 위에 알림. 잘못 표시됐으면 지울 수 있음
        if (uploadedAt != null) Row(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF12A150).copy(alpha = 0.12f)).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✓ ${UploadLog.label(uploadedAt)}에 스레드로 보낸 글이에요", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0E7C3E), modifier = Modifier.weight(1f))
            TextButton(onClick = { UploadLog.clear(ctx, post.id) }) { Text("표시 지우기", fontSize = 13.sp) }
        }
        Box(Modifier.weight(1f)) {
            PageScanner(post, attempt, community = kind == "community") { scan = it }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 원문 읽기 상태
                when (val s = scan) {
                    is ScanState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("원문 읽는 중… ${s.progress}%", fontSize = 13.sp, color = muted)
                    }
                    is ScanState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("원문을 읽지 못했어요: ${s.message}", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { summaryState = ""; attempt++ }) { Text("다시 읽기") }
                    }
                    is ScanState.Done -> {}
                }

                // 이미지
                Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(vertical = 14.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("이미지 ${pieces.size}장", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            if (kind == "community") {
                                Text("글 캡처", fontSize = 13.sp)
                                Spacer(Modifier.width(4.dp))
                                Switch(checked = useCapture, onCheckedChange = { useCapture = it }, modifier = Modifier.height(28.dp))
                                if (useCapture) {
                                    Spacer(Modifier.width(10.dp))
                                    Text("모자이크", fontSize = 13.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Switch(checked = mosaicOn, onCheckedChange = { mosaicOn = it }, modifier = Modifier.height(28.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val w = (LocalConfiguration.current.screenWidthDp * 0.62f).dp
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(pieces) { i, b ->
                                Box {
                                    val img = remember(b) { b.asImageBitmap() }
                                    Image(img, "이미지 ${i + 1}", contentScale = ContentScale.FillWidth,
                                        modifier = Modifier.width(w).clip(RoundedCornerShape(10.dp)).clickable { zoomIndex = i }.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
                                    Text("${i + 1}/${pieces.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(6.dp).background(Color(0x99000000), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                        }
                        if (result == null) Text(if (useCapture) "원문 캡처를 기다리는 중…" else "이미지 만드는 중…", Modifier.padding(16.dp), color = muted)
                        if (kind == "community") Text(
                            if (useCapture) "글 화면을 제목·작성자 줄부터 본문까지 캡처했어요 (광고·댓글 제외, 작성자는 모자이크). 길면 여백에서 나눠요." else "캡처 대신 직접 만든 카드예요.",
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), fontSize = 12.sp, color = muted,
                        )
                        if (kind != "community") Text(
                            if (result?.header == true) "1번은 기사 머리 부분(매체명 · 제목 · 날짜 · 요약) 캡처, 그다음은 핵심 정리 카드예요."
                            else "기사 제목 위치를 찾지 못하면 직접 만든 표지 카드가 들어가요.",
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), fontSize = 12.sp, color = muted,
                        )
                    }
                }

                // 카드 내용 (고치면 이미지가 다시 그려짐)
                if (!useCapture) Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("카드 내용 (고치면 이미지에 반영)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        if (result?.header != true) {
                            OutlinedTextField(headline, { headline = it }, Modifier.fillMaxWidth(), label = { Text("표지 제목") }, textStyle = LocalTextStyle.current.copy(fontSize = 15.sp))
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedTextField(pointsText, { pointsText = it }, Modifier.fillMaxWidth(), label = { Text("핵심 정리 (한 줄에 하나 · 항목: 내용)") }, textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 21.sp))
                    }
                }

                // 스레드에 들어갈 글
                Card(colors = cardColors, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("스레드에 들어갈 글", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text("${outText.length}/500", fontSize = 12.sp, color = if (outText.length > 500) MaterialTheme.colorScheme.error else muted)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(thread, { thread = it }, Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 21.sp))
                        if (kind != "community") {
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("기사 링크 넣기", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(if (linkOn) link else "글 끝에 원문 링크를 붙이지 않아요", fontSize = 12.sp, color = muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                Switch(checked = includeLink, onCheckedChange = { includeLink = it; prefs.includeLink = it })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        when (summaryState) {
                            "working" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp)); Text("원문을 읽고 요약하는 중…", fontSize = 13.sp)
                            }
                            "" -> {}
                            "done" -> Text(
                                if (Summarizer.lastDropped.isEmpty()) "숫자·날짜는 올리기 전에 원문과 한 번 대조해 주세요."
                                else "원문에 없는 숫자(${Summarizer.lastDropped.joinToString(", ")})가 들어간 줄은 뺐어요. 원문과 대조해 주세요.",
                                fontSize = 12.sp, color = muted,
                            )
                            else -> Text(summaryState, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = scanEnded && summaryState != "working", onClick = { summaryRun++ }) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("다시 요약")
                            }
                            Button(onClick = { ThreadsKit.copy(ctx, "본문", outText) }) {
                                Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("복사하기")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        // 하단: 저장 / 스레드에 올리기
        val files = result?.files
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = files != null, shape = CircleShape, modifier = Modifier.height(52.dp),
                onClick = {
                    val n = ThreadsKit.saveToGallery(ctx, files!!, "renf_${post.source}_${System.currentTimeMillis() / 1000}")
                    Toast.makeText(ctx, if (n > 0) "사진 앱에 ${n}장 저장했어요 (RealEstateNews 앨범)" else "안드로이드 10 이상에서 저장할 수 있어요", Toast.LENGTH_SHORT).show()
                },
            ) { Text("갤러리 저장") }
            val go = {
                if (files != null) {
                    ThreadsKit.copy(ctx, "본문", outText) // 자동 입력이 안 되면 붙여넣기만 하면 되도록
                    // 자동 입력이 켜져 있으면: (설정에 따라) 계정 확인·전환 → 보내기 → 본문 붙여넣기
                    if (ThreadsAuto.isEnabled(ctx)) { sent = true; UploadLog.mark(ctx, post.id); ThreadsKit.upload(ctx, files, outText) } else showAutoHelp = true
                }
            }
            if (askAgain) AlertDialog(
                onDismissRequest = { askAgain = false },
                title = { Text("이미 올린 글이에요") },
                text = { Text("이 글은 ${uploadedAt?.let { UploadLog.label(it) } ?: "전"}에 스레드로 보냈어요. 한 번 더 올릴까요?", fontSize = 14.sp, lineHeight = 20.sp) },
                confirmButton = { TextButton(onClick = { askAgain = false; go() }) { Text("다시 올리기", fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { askAgain = false }) { Text("취소") } },
            )
            GradientButton(if (uploadedAt != null) "다시 올리기" else "스레드에 올리기", null, onClick = { if (uploadedAt != null && !sent) askAgain = true else go() }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 스레드 글에 넣을 원문 링크. 실제로 열린 주소(구글 뉴스 → 언론사 기사)를 우선 쓰고, 광고 추적용 꼬리표는 뗌.
 * 아직 원문을 못 열었거나 구글 주소에 머물러 있으면 수집된 주소를 그대로 씀.
 */
fun articleLink(finalUrl: String?, postUrl: String): String {
    val base = finalUrl?.takeIf { it.startsWith("http") && !it.contains("news.google.com") && !it.contains("consent.google") } ?: postUrl
    val q = base.indexOf('?')
    if (q < 0) return base.substringBefore('#')
    val kept = base.substring(q + 1).substringBefore('#').split('&')
        .filter { it.isNotEmpty() && !Regex("^(utm_[a-z]+|fbclid|gclid|ref|from|oc|ocid|cmpid|mibextid)=", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    return base.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
}

fun kindLabel(kind: String) = when (kind) {
    "gov" -> "정부 발표"
    "community" -> "커뮤니티"
    else -> "부동산 뉴스"
}
