package com.realestatenewsfinder.app

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val NewRed = Color(0xFFE5262B)

/** 빨간 동그라미 N (새로 신고된 거래) */
@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(20.dp).background(NewRed, CircleShape), contentAlignment = Alignment.Center) {
        Text("N", color = Color.White, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SoftCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().shadow(4.dp, shape, ambientColor = Color(0x262563EB), spotColor = Color(0x262563EB)).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String) = Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

/**
 * 통계 탭의 '실거래' 화면: 알림 받는 단지, 최근 알림, 지역 고르기(시도 → 시군구).
 * 시군구를 고르면 그 지역의 단지 목록으로 넘어감.
 */
@Composable
fun DealHome(onRegion: (String) -> Unit, onOpen: (DealTarget) -> Unit, onTool: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val core = remember { DealCore.get(ctx) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) } // 구독·알림이 바뀌면 다시 그림
    var polling by remember { mutableStateOf(false) }
    val sidos = remember { core.regions.raw.objects() }
    var sido by remember { mutableStateOf(prefs.dealSido.ifBlank { sidos.first().getString("sido") }) }
    val subs = remember(version) { core.subscriptionsByComplex() }
    val notes = remember(version) { core.store.notifications.objects().reversed().take(8) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text("실거래로 만들기", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("budget", "내 돈으로 살 수 있는 아파트", "예산·평형·지역을 고르면 살 수 있는 아파트 목록"),
                    Triple("history", "5·10·20년 전 가격 비교", "단지와 평형을 고르면 그때와 지금 실거래가"),
                    Triple("presale", "분양가 vs 실거래가", "10년 이내 단지의 분양가와 최근 실거래가"),
                ).forEach { (id, title, sub) ->
                    SoftCard(onClick = { onTool(id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(sub, fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                        }
                    }
                }
            }
        }
        if (subs.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, top = 4.dp)) {
                    Text("알림 받는 단지", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    TextButton(enabled = !polling, onClick = {
                        polling = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { core.alerts.poll() } }
                            polling = false; version++
                            val msg = r.fold({ if (it.errors.isNotEmpty()) "확인 실패: ${it.errors.first().second}" else if (it.sent > 0) "새 실거래 알림 ${it.sent}건" else "새로 신고된 실거래가 없어요" }, { it.message ?: "확인 실패" })
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(if (polling) "확인 중…" else "지금 확인") }
                }
            }
            items(subs, key = { "s" + it.first().getString("id") }) { group ->
                val s = group.first()
                SoftCard(onClick = { onOpen(DealTarget(s.getString("lawdCd"), s.getString("complexId"), s.getString("type"))) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.optString("aptNm"), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(core.regions.get(s.getString("lawdCd"))?.optString("fullName").orEmpty(), s.optString("umdNm")).filter { it.isNotBlank() }.joinToString(" ") +
                                    " · " + group.mapNotNull { Molit.TYPES[it.getString("type")] }.joinToString("·"),
                                fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                    }
                }
            }
        }
        if (notes.isNotEmpty()) {
            item { Text("최근 알림", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 6.dp, top = 6.dp)) }
            items(notes, key = { "n" + it.getString("id") }) { n ->
                SoftCard(onClick = { onOpen(DealTarget(n.getString("lawdCd"), n.getString("complexId"), n.optString("type", "trade"), n.getString("id"))) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (n.optString("kind") == "new") { NewBadge(); Spacer(Modifier.width(8.dp)) }
                        Text(n.optString("title"), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(runCatching { ago(java.time.Instant.parse(n.optString("createdAt")).toEpochMilli()) }.getOrDefault(""), fontSize = 11.sp, color = muted)
                    }
                    Text(n.optString("body"), fontSize = 12.sp, lineHeight = 17.sp, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Text("단지 찾기", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
            Text("시·군·구를 고르면 아파트 단지가 나와요", fontSize = 12.sp, color = muted, modifier = Modifier.padding(start = 6.dp))
        }
        val recent = prefs.dealRecent.mapNotNull { core.regions.get(it) }
        if (recent.isNotEmpty()) item {
            PillRow { recent.forEach { r -> ChoicePill(r.getString("fullName").let { f -> f.substringBefore(' ').take(2) + " " + f.substringAfter(' ') }, false) { onRegion(r.getString("code")) } } }
        }
        item { PillRow { sidos.forEach { s -> val name = s.getString("sido"); ChoicePill(shortSido(name), name == sido) { sido = name; prefs.dealSido = name } } } }
        item {
            val list = sidos.firstOrNull { it.getString("sido") == sido }?.getJSONArray("sigungu")?.objects().orEmpty()
            SoftCard {
                list.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { g ->
                            Text(
                                g.getString("name"), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onRegion(g.getString("code")) }.padding(vertical = 11.dp, horizontal = 6.dp),
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item { Text("자료: 국토교통부 실거래가 공개시스템 (계약 후 30일 안에 신고됨)", fontSize = 12.sp, color = muted, modifier = Modifier.padding(horizontal = 6.dp)) }
    }
}

/** "서울특별시" → "서울", "충청북도" → "충북", "전북특별자치도" → "전북" */
fun shortSido(name: String): String = when {
    name.startsWith("충청") || name.startsWith("전라") || name.startsWith("경상") -> "${name[0]}${if (name.contains("남")) "남" else "북"}"
    else -> name.take(2)
}

/** 시군구의 아파트 단지 목록 (최근 6개월 거래가 있는 단지). 누르면 단지 상세 */
@Composable
fun ComplexListScreen(lawdCd: String, onBack: () -> Unit, note: String? = null, onOpen: (DealTarget, String, String) -> Unit) {
    val ctx = LocalContext.current
    val core = remember { DealCore.get(ctx) }
    val region = remember(lawdCd) { core.regions.get(lawdCd) }
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    LaunchedEffect(lawdCd, attempt) {
        error = null
        try {
            list = withContext(Dispatchers.IO) { core.service.getComplexes(lawdCd) }
            Prefs(ctx).let { p -> p.dealRecent = listOf(lawdCd) + p.dealRecent.filter { it != lawdCd } }
        } catch (e: Exception) {
            error = e.message ?: "단지 목록을 받지 못했어요"
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // 정렬: 가나다순(기본) 또는 최근 매매 실거래가 높은 순 (고른 것은 다음에도 기억). 매매 거래가 없는 단지는 맨 뒤
    var byPrice by remember { mutableStateOf(Prefs(ctx).dealSortByPrice) }
    val shown = remember(list, query, byPrice) {
        val q = query.trim().replace(" ", "")
        val found = list.orEmpty().filter { q.isEmpty() || it.getString("aptNm").replace(" ", "").contains(q, true) || it.optString("umdNm").contains(q) }
        if (byPrice) found.sortedByDescending { if (it.isNull("lastTradePrice")) -1L else it.getLong("lastTradePrice") } else found
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Column {
                Text(region?.optString("fullName") ?: lawdCd, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(note ?: list?.let { "최근 6개월 거래가 있는 단지 ${it.size}곳" } ?: "단지 목록 받는 중…", fontSize = 12.sp, color = if (note != null) MaterialTheme.colorScheme.primary else muted)
            }
        }
        SearchPill(query, { query = it }, Modifier.padding(horizontal = 14.dp).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill("가나다순", !byPrice) { byPrice = false; Prefs(ctx).dealSortByPrice = false }
            ChoicePill("실거래가 높은순", byPrice) { byPrice = true; Prefs(ctx).dealSortByPrice = true }
        }
        Spacer(Modifier.height(8.dp))
        if (error != null) {
            Column(Modifier.padding(20.dp)) {
                Text("⚠ $error", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { attempt++ }) { Text("다시 시도") }
            }
        } else if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown.isEmpty()) item { EmptyText("찾는 단지가 없어요") }
                items(shown, key = { it.getString("id") }) { c ->
                    val counts = c.getJSONObject("counts")
                    SoftCard(onClick = { onOpen(DealTarget(lawdCd, c.getString("id"), if (counts.optInt("trade") > 0) "trade" else if (counts.optInt("jeonse") > 0) "jeonse" else "monthly"), c.getString("aptNm"), c.optString("umdNm")) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.getString("aptNm"), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(c.optString("umdNm").takeIf { it.isNotBlank() }, c.optInt("buildYear").takeIf { it > 0 }?.let { "${it}년" },
                                        "매매 ${counts.optInt("trade")} · 전세 ${counts.optInt("jeonse")} · 월세 ${counts.optInt("monthly")}").joinToString(" · "),
                                    fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!c.isNull("lastTradePrice")) Column(horizontalAlignment = Alignment.End) {
                                Text(DealFmt.manwon(c.getLong("lastTradePrice")), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                Text("최근 매매 ${DealText.md(c.optString("lastTradeDate"))}", fontSize = 11.sp, color = muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 단지 상세: 매매·전세·월세 / 기간(1개월~5년) / 면적별 실거래 요약과 내역.
 * 아래 버튼으로 실거래 알림 받기, 스레드 내보내기. 알림을 눌러 들어오면 새로 신고된 거래에 빨간 N.
 */
@Composable
fun ComplexDetailScreen(target: DealTarget, onBack: () -> Unit, onHistory: (aptNm: String, umdNm: String) -> Unit = { _, _ -> }, onShare: (aptNm: String, umdNm: String, type: String, period: DealPeriod, fresh: List<Deal>) -> Unit) {
    val ctx = LocalContext.current
    val core = remember { DealCore.get(ctx) }
    val scope = rememberCoroutineScope()
    val region = remember(target.lawdCd) { core.regions.get(target.lawdCd) }
    // 알림에 담긴 거래 (알림을 눌러 들어온 경우) + 이 단지의 최근 7일 새 실거래
    val noted = remember(target) { core.notificationDeals(target.notificationId) }
    val newKeys = remember(target) { noted.filter { !it.canceled }.map { it.key }.toSet() + core.recentNewKeys(target.lawdCd, target.complexId) }
    var type by remember(target) { mutableStateOf(target.type) }
    var period by remember(target) { mutableStateOf(DealPeriod.Y1) }
    var areaKey by remember(target, type) { mutableStateOf<Int?>(null) }
    var deals by remember { mutableStateOf<List<Deal>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var name by remember(target) { mutableStateOf(noted.firstOrNull()?.aptNm ?: core.store.subscriptions.objects().firstOrNull { it.getString("complexId") == target.complexId }?.optString("aptNm").orEmpty()) }
    var umd by remember(target) { mutableStateOf(noted.firstOrNull()?.umdNm.orEmpty()) }
    var buildYear by remember(target) { mutableStateOf<Int?>(null) }
    var subscribed by remember(target) { mutableStateOf(core.subscribedTypes(target.lawdCd, target.complexId)) }
    var alertDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    LaunchedEffect(target, type, period, attempt) {
        deals = null; error = null
        try {
            val today = core.molit.today()
            val all = withContext(Dispatchers.IO) { core.service.getDeals(target.lawdCd, target.complexId, type, period.fetchMonths) }
            val inPeriod = all.filter { it.date >= period.since(today) }
            deals = inPeriod
            (inPeriod.firstOrNull() ?: all.firstOrNull())?.let { d -> if (name.isBlank()) name = d.aptNm; if (umd.isBlank()) umd = d.umdNm; if (buildYear == null) buildYear = d.buildYear }
        } catch (e: Exception) {
            error = e.message ?: "실거래 자료를 받지 못했어요"
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(ctx, "알림 권한이 꺼져 있어요. 휴대폰 설정에서 알림을 허용해야 받을 수 있어요.", Toast.LENGTH_LONG).show()
    }
    if (alertDialog) {
        var pick by remember { mutableStateOf(subscribed.ifEmpty { setOf(type) }) }
        AlertDialog(
            onDismissRequest = { if (!busy) alertDialog = false },
            title = { Text("실거래 알림 받기") },
            text = {
                Column {
                    Text("새 실거래가 신고되면 알림으로 알려드려요. 받을 거래 유형을 고르세요.", fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Molit.TYPES.forEach { (t, label) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pick = if (t in pick) pick - t else pick + t }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(t in pick, { pick = if (t in pick) pick - t else pick + t })
                            Text("$label 실거래", fontSize = 15.sp)
                        }
                    }
                    if (busy) { Spacer(Modifier.height(6.dp)); Text("설정하는 중…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    if (pick.isNotEmpty() && Build.VERSION.SDK_INT >= 33 && !HotPostWorker.canNotify(ctx)) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                // 끌 유형은 구독 해제, 켤 유형은 구독 (처음 구독하면 최근 한 달 실거래를 알림으로 보여줌)
                                core.store.subscriptions.objects().filter { it.getString("lawdCd") == target.lawdCd && it.getString("complexId") == target.complexId && it.getString("type") !in pick }
                                    .forEach { core.alerts.unsubscribe(it.getString("id")) }
                                pick.forEach { core.alerts.subscribe(target.lawdCd, target.complexId, it) }
                            }
                        }
                        busy = false
                        subscribed = core.subscribedTypes(target.lawdCd, target.complexId)
                        DealPollWorker.schedule(ctx)
                        r.onFailure { Toast.makeText(ctx, it.message ?: "알림 설정 실패", Toast.LENGTH_LONG).show() }
                            .onSuccess { alertDialog = false; Toast.makeText(ctx, if (pick.isEmpty()) "알림을 껐어요" else "새 실거래가 신고되면 알려드릴게요", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("저장", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { alertDialog = false }) { Text("취소") } },
        )
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val valid = deals.orEmpty().filter { !it.canceled }
    val areas = remember(deals) { valid.groupBy(DealText::areaKey).entries.sortedBy { it.key }.map { it.key to it.value.size } }
    val shown = deals.orEmpty().filter { areaKey == null || DealText.areaKey(it) == areaKey }
    val shownValid = shown.filter { !it.canceled }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { "단지 실거래" }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(region?.optString("fullName"), umd.takeIf { it.isNotBlank() }, buildYear?.let { "${it}년 준공" }).joinToString(" · "), fontSize = 12.sp, color = muted, maxLines = 1)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SoftCard {
                    Label("거래 유형")
                    Spacer(Modifier.height(6.dp))
                    PillRow { Molit.TYPES.forEach { (t, label) -> ChoicePill(label, type == t) { type = t } } }
                    Spacer(Modifier.height(10.dp))
                    Label("기간")
                    Spacer(Modifier.height(6.dp))
                    PillRow { DealPeriod.entries.forEach { p -> ChoicePill(p.label, period == p) { period = p } } }
                    if (areas.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        Label("전용면적")
                        Spacer(Modifier.height(6.dp))
                        PillRow {
                            ChoicePill("전체", areaKey == null) { areaKey = null }
                            areas.forEach { (a, n) -> ChoicePill("${a}㎡ ($n)", areaKey == a) { areaKey = a } }
                        }
                    }
                }
            }
            if (name.isNotBlank()) item {
                SoftCard(onClick = { onHistory(name, umd) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("5·10·20년 전 가격과 비교", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("그때와 지금 실거래가를 스레드용으로", fontSize = 12.sp, color = muted)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                    }
                }
            }
            if (error != null) item {
                Column(Modifier.padding(8.dp)) {
                    Text("⚠ $error", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { attempt++ }) { Text("다시 시도") }
                }
            } else if (deals == null) item {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(if (period.months >= 36) "${period.label}치 실거래를 받는 중… 처음에는 시간이 걸려요" else "실거래 받는 중…", fontSize = 13.sp, color = muted)
                }
            } else {
                item {
                    SoftCard {
                        Text("최근 ${period.label} ${Molit.TYPES[type]} 실거래" + (areaKey?.let { " · ${it}㎡" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (shownValid.isEmpty()) Text("이 기간에 신고된 거래가 없어요", fontSize = 14.sp, color = muted)
                        else Row(Modifier.fillMaxWidth()) {
                            val vals = shownValid.map(DealText::value)
                            val cells = if (type == "monthly") listOf("거래" to "${shownValid.size}건", "평균 보증금" to DealFmt.manwon(vals.average().toLong()), "평균 월세" to "%,d만".format(shownValid.map { it.monthlyRent }.average().toLong()))
                            else listOf("거래" to "${shownValid.size}건", "평균" to DealFmt.manwon(vals.average().toLong()), "최고" to DealFmt.manwon(vals.max()), "최저" to DealFmt.manwon(vals.min()))
                            cells.forEach { (k, v) ->
                                Column(Modifier.weight(1f)) {
                                    Text(k, fontSize = 12.sp, color = muted)
                                    Text(v, fontSize = if (cells.size > 3) 14.sp else 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                items(shown, key = { it.key }) { d -> DealRow(d, d.key in newKeys) }
                item { Text("자료: 국토교통부 실거래가 (계약일 기준)", fontSize = 12.sp, color = muted, modifier = Modifier.padding(6.dp)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(shape = CircleShape, modifier = Modifier.height(52.dp), onClick = { alertDialog = true }) {
                Icon(if (subscribed.isNotEmpty()) Icons.Filled.Notifications else Icons.Outlined.NotificationsNone, null, Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (subscribed.isNotEmpty()) "알림 받는 중" else "실거래 알림 받기")
            }
            GradientButton("스레드 내보내기", null, onClick = {
                // 새로 신고된 거래가 있으면 그 거래를 분석하는 글로, 없으면 기간 요약으로
                val fresh = (noted + deals.orEmpty().filter { it.key in newKeys }).filter { !it.canceled }.distinctBy { it.key }
                onShare(name, umd, type, period, fresh)
            }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DealRow(d: Deal, fresh: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (fresh) NewRed.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fresh) { NewBadge(); Spacer(Modifier.width(8.dp)) }
        Column(Modifier.weight(1f)) {
            Text("${DealFmt.area(d.area)}㎡ · ${d.floor}층" + (if (d.dong.isNotBlank()) " · ${d.dong}동" else ""), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val tags = listOfNotNull("계약 ${d.date.substring(2).replace('-', '.')}", d.dealingGbn.takeIf { it.isNotBlank() }, d.contractType.takeIf { it.isNotBlank() }, if (d.canceled) "해제" else null)
            Text(tags.joinToString(" · "), fontSize = 12.sp, color = if (d.canceled) MaterialTheme.colorScheme.error else muted)
        }
        Text(
            DealFmt.price(d), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
            color = if (d.canceled) muted else if (fresh) NewRed else MaterialTheme.colorScheme.primary,
            textDecoration = if (d.canceled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
        )
    }
}
