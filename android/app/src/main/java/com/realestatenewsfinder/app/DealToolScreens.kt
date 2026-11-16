package com.realestatenewsfinder.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Composable
private fun Label(text: String) = Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

/** 시도 → 시군구 고르기 (두 줄 알약). 고른 값은 다음에도 기억 */
@Composable
private fun RegionSelect(lawd: String, onLawd: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val core = remember { DealCore.get(ctx) }
    val sidos = remember { core.regions.raw.objects() }
    var sido by remember { mutableStateOf(core.regions.get(lawd)?.optString("sido") ?: prefs.dealSido.ifBlank { sidos.first().getString("sido") }) }
    Label("지역" + (core.regions.get(lawd)?.optString("fullName")?.let { " · $it" } ?: " · 아래에서 시·군·구를 고르세요"))
    PillRow { sidos.forEach { s -> val n = s.getString("sido"); ChoicePill(shortSido(n), n == sido) { sido = n; prefs.dealSido = n } } }
    PillRow {
        sidos.firstOrNull { it.getString("sido") == sido }?.getJSONArray("sigungu")?.objects().orEmpty().forEach { g ->
            ChoicePill(g.getString("name"), g.getString("code") == lawd) { onLawd(g.getString("code")) }
        }
    }
}

private val TOOL_PERIODS = listOf(DealPeriod.M1, DealPeriod.M3, DealPeriod.M6, DealPeriod.Y1)

/** 그 시군구의 기간 안 매매 거래를 받아 옴. 반환: (거래, 오류) */
@Composable
private fun rememberTrades(lawd: String, period: DealPeriod): Pair<List<Deal>?, String?> {
    val ctx = LocalContext.current
    val core = remember { DealCore.get(ctx) }
    var deals by remember { mutableStateOf<List<Deal>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lawd, period) {
        deals = null; error = null
        if (lawd.isBlank()) return@LaunchedEffect
        try {
            val since = period.since(core.molit.today())
            deals = withContext(Dispatchers.IO) { core.molit.fetchMonths("trade", lawd, period.fetchMonths) }.filter { it.date >= since }
            Prefs(ctx).let { p -> p.dealRecent = listOf(lawd) + p.dealRecent.filter { it != lawd } }
        } catch (e: Exception) {
            error = e.message ?: "실거래 자료를 받지 못했어요"
        }
    }
    return deals to error
}

/**
 * 내 돈으로 살 수 있는 아파트: 예산(1억~200억)·평형(59·84)·지역·기간(1개월~1년)을 고르면
 * 그 지역 실거래 평균이 예산 이하인 단지 목록을 스레드용 이미지와 글로 만듦
 */
@Composable
fun BudgetScreen(onBack: () -> Unit, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val core = remember { DealCore.get(ctx) }
    var eok by remember { mutableStateOf("10") }
    var band by remember { mutableIntStateOf(84) }
    var lawd by remember { mutableStateOf(prefs.dealRecent.firstOrNull().orEmpty()) }
    var period by remember { mutableStateOf(DealPeriod.M6) }
    val (deals, error) = rememberTrades(lawd, period)
    // 1억~200억 (소수 한 자리까지)
    val budget = ((eok.toDoubleOrNull() ?: 0.0).coerceIn(1.0, 200.0) * 10000).toLong()
    val regionName = core.regions.get(lawd)?.optString("fullName").orEmpty()
    // 아파트만 보기: 빌라형·소규모를 거르려고 최근 6개월 매매·전월세 거래도 받아서 단지 규모를 짐작함
    var aptOnly by remember { mutableStateOf(true) }
    var extra by remember { mutableStateOf<List<Deal>>(emptyList()) }
    LaunchedEffect(lawd) {
        extra = emptyList()
        if (lawd.isNotBlank()) extra = withContext(Dispatchers.IO) { runCatching { core.molit.fetchMonths("trade", lawd, 6) + core.molit.fetchMonths("rent", lawd, 6) }.getOrDefault(emptyList()) }
    }
    val result = remember(deals, band, budget, aptOnly, extra) { deals?.let { DealTools.budget(it, band, budget, aptOnly, extra) } }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(result, regionName, period) { text = result?.let { DealTools.budgetText(regionName, band, budget, period, it, prefs.casualTone) } ?: "" }
    ThreadShareScreen(
        title = "내 돈으로 살 수 있는 아파트", cacheId = "budget", hint = "국토부 실거래 숫자를 그대로 넣어 만든 글이에요. 자유롭게 고쳐 쓰세요.",
        text = text, onText = { text = it },
        renderKey = Triple(result, band, budget),
        render = result?.let { r -> { DealCards.budget(ctx, regionName.substringAfter(' ', regionName), band, budget, period, r, core.molit.today()) } },
        status = when { lawd.isBlank() -> "지역을 골라주세요."; error != null -> "⚠ $error"; result == null -> "실거래 받는 중…"; result.total == 0 -> "이 기간에 ${band}㎡ 실거래가 없어요. 기간을 늘려보세요."; else -> null },
        onBack = onBack, onSent = onSent,
    ) {
        Label("가진 돈 (1억 ~ 200억)")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                eok, { v -> if (v.length <= 5 && v.all { it.isDigit() || it == '.' }) eok = v }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(120.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.width(8.dp))
            Text("억원", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text("= ${DealText.won(budget)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PillRow { listOf(1, 3, 5, 7, 10, 15, 20, 30, 50, 100, 200).forEach { v -> ChoicePill("${v}억", eok == "$v") { eok = "$v" } } }
        Label("평형")
        PillRow { DealTools.BANDS.forEach { b -> ChoicePill("${b}㎡", band == b) { band = b } } }
        RegionSelect(lawd) { lawd = it }
        Label("실거래 기간")
        PillRow { TOOL_PERIODS.forEach { p -> ChoicePill(p.label, period == p) { period = p } } }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("아파트만 보기", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "빌라형 이름, 4층 이하, 거래가 거의 없는 소규모는 뺌" + (result?.excluded?.takeIf { it > 0 }?.let { " · 지금 ${it}곳 제외" } ?: ""),
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(aptOnly, { aptOnly = it })
        }
    }
}

/** 과거 가격 비교: 단지와 평형을 고르면 지금과 5·10·20년 전 실거래 평균을 비교 (자료가 있는 만큼) */
@Composable
fun HistoryScreen(target: DealTarget, aptNm: String, umdNm: String, onBack: () -> Unit, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val core = remember { DealCore.get(ctx) }
    val regionName = remember { core.regions.get(target.lawdCd)?.optString("fullName").orEmpty() }
    val place = listOf(regionName.substringAfter(' ', regionName), umdNm).filter { it.isNotBlank() }.joinToString(" ")
    var recent by remember { mutableStateOf<List<Deal>?>(null) }
    var areaKey by remember { mutableStateOf<Int?>(null) }
    var points by remember { mutableStateOf<List<DealTools.HistoryPoint>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("최근 1년 실거래 받는 중…") }
    var text by remember { mutableStateOf("") }
    val today = remember { core.molit.today() }

    // 1) 최근 1년: 이 단지의 평형 목록
    LaunchedEffect(target) {
        try {
            val all = withContext(Dispatchers.IO) { core.molit.fetchYms("trade", target.lawdCd, DealTools.historyYms(today, 0)) }
            val mine = all.filter { !it.canceled && DealTools.sameComplex(it, target.complexId, aptNm, umdNm) }
            recent = mine
            val areas = mine.groupBy(DealText::areaKey)
            // 59·84가 있으면 그걸 먼저, 없으면 거래가 가장 많은 평형
            areaKey = listOf(84, 59).firstOrNull { b -> areas.keys.any { it == b } } ?: areas.maxByOrNull { it.value.size }?.key
            if (mine.isEmpty()) error = "최근 1년 동안 이 단지의 매매 실거래가 없어서 비교할 수 없어요."
        } catch (e: Exception) {
            error = e.message ?: "실거래 자료를 받지 못했어요"
        }
    }
    // 2) 고른 평형으로 5·10·20년 전
    LaunchedEffect(areaKey, recent) {
        val key = areaKey ?: return@LaunchedEffect
        val mine = recent ?: return@LaunchedEffect
        points = null
        val buildYear = mine.firstNotNullOfOrNull { it.buildYear }
        val out = mutableListOf(DealTools.historyPoint(today, 0, mine, target.complexId, aptNm, umdNm, key, buildYear))
        for (n in DealTools.HISTORY_YEARS) {
            val yms = DealTools.historyYms(today, n)
            val skip = yms.isEmpty() || (buildYear != null && today.year - n < buildYear - 1)
            progress = "${n}년 전 실거래 받는 중…"
            val deals = if (skip) emptyList() else withContext(Dispatchers.IO) { runCatching { core.molit.fetchYms("trade", target.lawdCd, yms) }.getOrNull() }
            out += DealTools.historyPoint(today, n, deals, target.complexId, aptNm, umdNm, key, buildYear)
        }
        points = out
        text = DealTools.historyText(aptNm, place, key, out, prefs.casualTone)
    }
    val areas = remember(recent) { recent.orEmpty().groupBy(DealText::areaKey).entries.sortedBy { it.key }.map { it.key to it.value.size } }
    ThreadShareScreen(
        title = "$aptNm · 그때와 지금", cacheId = "history-${target.complexId}", hint = "국토부 실거래 숫자를 그대로 넣어 만든 글이에요. 자유롭게 고쳐 쓰세요.",
        text = text, onText = { text = it },
        renderKey = points to areaKey,
        render = points?.let { p -> { DealCards.history(ctx, aptNm, place, areaKey ?: 0, p, today) } },
        status = error?.let { "⚠ $it" } ?: if (points == null) progress else null,
        onBack = onBack, onSent = onSent,
    ) {
        Label("평형 (전용면적)")
        if (areas.isEmpty()) Text(if (error == null) "평형을 불러오는 중…" else "고를 평형이 없어요", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else PillRow { areas.forEach { (a, n) -> ChoicePill("${a}㎡ ($n)", areaKey == a) { areaKey = a } } }
        Text("지금(최근 1년)과 5년·10년·20년 전 무렵의 실거래 평균을 비교해요. 그때 없던 단지이거나 거래가 없으면 그 시점은 비워 둬요.", fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 분양가 자료 (클라우드 수집기가 청약홈에서 모은 presale.json) */
object PresaleRepository {
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "presale.json")
    @Volatile private var mem: List<DealTools.PresaleItem>? = null
    @Volatile private var loadedAt = 0L

    /** 6시간 안에 받은 것이 있으면 그대로 씀. 받기 실패하면 저장해 둔 것 */
    suspend fun load(ctx: Context): List<DealTools.PresaleItem> = withContext(Dispatchers.IO) {
        mem?.let { if (System.currentTimeMillis() - loadedAt < 6 * 3600_000L) return@withContext it }
        val url = Prefs(ctx).feedUrl.substringBeforeLast('/') + "/presale.json?t=${System.currentTimeMillis() / 3600000}"
        val text = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            try {
                if (conn.responseCode != 200) throw Exception("서버 응답 ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }.also { cacheFile(ctx).writeText(it) }
            } finally { conn.disconnect() }
        }.getOrElse { e -> runCatching { cacheFile(ctx).readText() }.getOrElse { throw Exception("분양가 자료를 받지 못했어요 (${e.message})") } }
        DealTools.parsePresale(text).also { mem = it; loadedAt = System.currentTimeMillis() }
    }
}

/** 분양가 vs 실거래: 지역·평형을 고르면 10년 이내 단지의 분양가와 최근 실거래 평균을 비교 */
@Composable
fun PresaleScreen(onBack: () -> Unit, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val core = remember { DealCore.get(ctx) }
    var band by remember { mutableIntStateOf(84) }
    var lawd by remember { mutableStateOf(prefs.dealRecent.firstOrNull().orEmpty()) }
    var period by remember { mutableStateOf(DealPeriod.Y1) }
    var items by remember { mutableStateOf<List<DealTools.PresaleItem>?>(null) }
    var itemsError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { PresaleRepository.load(ctx) }.onSuccess { items = it }.onFailure { itemsError = it.message } }
    val (deals, error) = rememberTrades(lawd, period)
    val regionName = core.regions.get(lawd)?.optString("fullName").orEmpty()
    val today = remember { core.molit.today() }
    val matches = remember(deals, items, band, lawd) { if (deals != null && items != null) DealTools.presaleCompare(items!!, lawd, deals, band, today.year) else null }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(matches, regionName, period) { text = matches?.let { DealTools.presaleText(regionName, band, period, it, prefs.casualTone) } ?: "" }
    val inRegion = items?.count { lawd in it.lawd } ?: 0
    ThreadShareScreen(
        title = "분양가 vs 실거래가", cacheId = "presale", hint = "청약홈 분양가와 국토부 실거래 숫자를 그대로 넣어 만든 글이에요. 자유롭게 고쳐 쓰세요.",
        text = text, onText = { text = it },
        renderKey = Triple(matches, band, period),
        render = matches?.let { m -> { DealCards.presale(ctx, regionName.substringAfter(' ', regionName), band, period, m, today) } },
        status = when {
            lawd.isBlank() -> "지역을 골라주세요."
            itemsError != null -> "⚠ $itemsError"
            error != null -> "⚠ $error"
            matches == null -> "분양가와 실거래 받는 중…"
            matches.isEmpty() -> "비교할 단지가 없어요. (이 지역 분양 공고 ${inRegion}건 중 최근 ${period.label} 안에 ${band}㎡ 실거래가 있는 10년 이내 단지 없음)"
            else -> null
        },
        onBack = onBack, onSent = onSent,
    ) {
        Label("평형")
        PillRow { DealTools.BANDS.forEach { b -> ChoicePill("${b}㎡", band == b) { band = b } } }
        RegionSelect(lawd) { lawd = it }
        Label("실거래 기간")
        PillRow { TOOL_PERIODS.forEach { p -> ChoicePill(p.label, period == p) { period = p } } }
        Text("최근 10년 안에 지어진 단지만, 청약홈에 분양가가 남아 있는 최근 5년 분양 단지를 비교해요.", fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
