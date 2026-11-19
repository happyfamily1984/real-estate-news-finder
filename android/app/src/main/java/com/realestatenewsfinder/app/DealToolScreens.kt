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
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Composable
private fun Label(text: String) = Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

/** 고른 지역: 시군구 하나 또는 시도 전체 */
class DealArea(
    /** 저장·선택 값: 시군구 코드 또는 "ALL:시도이름" */
    val key: String,
    val codes: List<String>,
    /** 글 머리: "인천 미추홀구" / "서울" */
    val label: String,
    /** 이미지 제목: "미추홀구" / "서울 전체" */
    val cardPlace: String,
    /** 시군구 코드 → 이름 (시도 전체일 때 단지 옆에 붙임) */
    val guName: Map<String, String>,
) {
    val all: Boolean get() = codes.size > 1

    companion object {
        fun of(core: DealCore, key: String): DealArea? {
            if (key.startsWith("ALL:")) {
                val sido = core.regions.raw.objects().firstOrNull { it.getString("sido") == key.removePrefix("ALL:") } ?: return null
                val gus = sido.getJSONArray("sigungu").objects()
                val short = shortSido(sido.getString("sido"))
                return DealArea(key, gus.map { it.getString("code") }, short, "$short 전체", gus.associate { it.getString("code") to it.getString("name") })
            }
            val r = core.regions.get(key) ?: return null
            return DealArea(key, listOf(key), "${shortSido(r.getString("sido"))} ${r.getString("name")}", r.getString("name"), emptyMap())
        }
    }
}

/** 시도 → 시군구 고르기 (두 줄 알약). 시군구 줄 맨 앞에 '전체'(그 시도 전부). 고른 값은 다음에도 기억 */
@Composable
private fun RegionSelect(area: DealArea?, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val core = remember { DealCore.get(ctx) }
    val sidos = remember { core.regions.raw.objects() }
    val current = area?.key.orEmpty()
    var sido by remember {
        mutableStateOf(if (current.startsWith("ALL:")) current.removePrefix("ALL:") else core.regions.get(current)?.optString("sido") ?: prefs.dealSido.ifBlank { sidos.first().getString("sido") })
    }
    Label("지역" + (area?.let { " · ${if (it.all) it.cardPlace else it.label}" } ?: " · 아래에서 고르세요"))
    PillRow { sidos.forEach { s -> val n = s.getString("sido"); ChoicePill(shortSido(n), n == sido) { sido = n; prefs.dealSido = n } } }
    PillRow {
        val gus = sidos.firstOrNull { it.getString("sido") == sido }?.getJSONArray("sigungu")?.objects().orEmpty()
        if (gus.size > 1) ChoicePill("${shortSido(sido)} 전체", current == "ALL:$sido") { onPick("ALL:$sido") }
        gus.forEach { g -> ChoicePill(g.getString("name"), g.getString("code") == current) { onPick(g.getString("code")) } }
    }
    if (area?.all == true) Text("시도 전체는 시군구마다 따로 받아서 처음에는 시간이 걸려요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val TOOL_PERIODS = listOf(DealPeriod.M1, DealPeriod.M3, DealPeriod.M6, DealPeriod.Y1)

/** 받아 온 매매 거래와, 단지가 어느 시군구 것인지 */
class AreaTrades(val deals: List<Deal>, val codeOf: Map<String, String>)

/** 그 지역(시군구 하나 또는 시도 전체)의 기간 안 매매 거래를 받아 옴. 반환: (거래, 오류, 진행 문구) */
@Composable
private fun rememberTrades(area: DealArea?, period: DealPeriod): Triple<AreaTrades?, String?, String> {
    val ctx = LocalContext.current
    val core = remember { DealCore.get(ctx) }
    var trades by remember { mutableStateOf<AreaTrades?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("실거래 받는 중…") }
    LaunchedEffect(area?.key, period) {
        trades = null; error = null
        val a = area ?: return@LaunchedEffect
        try {
            val since = period.since(core.molit.today())
            val all = mutableListOf<Deal>()
            val codeOf = HashMap<String, String>()
            a.codes.forEachIndexed { k, code ->
                progress = if (a.all) "실거래 받는 중… ${a.guName[code].orEmpty()} (${k + 1}/${a.codes.size})" else "실거래 받는 중…"
                val ds = withContext(Dispatchers.IO) { core.molit.fetchMonths("trade", code, period.fetchMonths) }.filter { it.date >= since }
                ds.forEach { codeOf[it.complexId] = code }
                all += ds
            }
            trades = AreaTrades(all, codeOf)
            Prefs(ctx).dealArea = a.key
            if (!a.all) Prefs(ctx).let { p -> p.dealRecent = listOf(a.key) + p.dealRecent.filter { it != a.key } }
        } catch (e: Exception) {
            error = e.message ?: "실거래 자료를 받지 못했어요"
        }
    }
    return Triple(trades, error, progress)
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
    var areaKey by remember { mutableStateOf(prefs.dealArea.ifBlank { prefs.dealRecent.firstOrNull().orEmpty() }) }
    val area = remember(areaKey) { DealArea.of(core, areaKey) }
    var period by remember { mutableStateOf(DealPeriod.M6) }
    val (trades, error, progress) = rememberTrades(area, period)
    // 1억~200억 (소수 한 자리까지)
    val budget = ((eok.toDoubleOrNull() ?: 0.0).coerceIn(1.0, 200.0) * 10000).toLong()
    // 아파트만 보기: 빌라형·소규모를 거르려고 최근 6개월 매매·전월세 거래도 받아서 단지 규모를 짐작함 (시도 전체는 양이 많아 매매만)
    var aptOnly by remember { mutableStateOf(true) }
    var extra by remember { mutableStateOf<List<Deal>>(emptyList()) }
    LaunchedEffect(areaKey) {
        extra = emptyList()
        val a = area ?: return@LaunchedEffect
        extra = withContext(Dispatchers.IO) {
            runCatching { a.codes.flatMap { code -> core.molit.fetchMonths("trade", code, 6) + (if (a.all) emptyList() else core.molit.fetchMonths("rent", code, 6)) } }.getOrDefault(emptyList())
        }
    }
    val result = remember(trades, band, budget, aptOnly, extra) {
        trades?.let { t -> DealTools.budget(t.deals, band, budget, aptOnly, extra, if (area?.all == true) t.codeOf.mapValues { area.guName[it.value].orEmpty() } else emptyMap()) }
    }
    val seed = remember { (System.currentTimeMillis() / 1000).toInt() }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(result, area?.key) { text = result?.let { DealTools.budgetText(area?.label.orEmpty(), band, budget, it, prefs.casualTone, seed + it.items.size) } ?: "" }
    ThreadShareScreen(
        title = "내 돈으로 살 수 있는 아파트", cacheId = "budget", hint = "국토부 실거래로 뽑은 목록이에요. 자유롭게 고쳐 쓰세요.",
        text = text, onText = { text = it },
        renderKey = Triple(result, band, budget),
        render = result?.let { r -> { DealCards.budget(ctx, area?.cardPlace.orEmpty(), band, budget, period, r, core.molit.today()) } },
        status = when { area == null -> "지역을 골라주세요."; error != null -> "⚠ $error"; result == null -> progress; result.total == 0 -> "이 기간에 ${band}㎡ 실거래가 없어요. 기간을 늘려보세요."; else -> null },
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
        RegionSelect(area) { areaKey = it }
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
    var compare by remember { mutableStateOf<DealTools.PriceCompare?>(null) }
    val seed = remember { (System.currentTimeMillis() / 1000).toInt() }

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
        // 글은 바로 보여주고, 물가 자료는 그 뒤에 받아서 되면 한 줄을 붙임 (못 받아도 글은 그대로)
        compare = null
        text = DealTools.historyText(aptNm, place, key, out, prefs.casualTone, seed = seed + key)
        val years = out.filter { it.ok && it.yearsAgo > 0 }.map { today.year - it.yearsAgo }
        val dine = StatsRepository.cached(ctx)?.dineout?.takeIf { it.isNotEmpty() }
            ?: kotlinx.coroutines.withTimeoutOrNull(20_000) { withContext(Dispatchers.IO) { runCatching { DineOut.load(ctx, today.year, years) }.getOrNull() } }
        val c = dine?.let { DealTools.priceCompare(out, it, today.year, seed + key) }
        if (c != null) {
            val plain = text
            compare = c
            // 그 사이 글을 고쳤으면 건드리지 않음
            if (plain == DealTools.historyText(aptNm, place, key, out, prefs.casualTone, seed = seed + key)) text = DealTools.historyText(aptNm, place, key, out, prefs.casualTone, c, seed + key)
        }
    }
    val areas = remember(recent) { recent.orEmpty().groupBy(DealText::areaKey).entries.sortedBy { it.key }.map { it.key to it.value.size } }
    ThreadShareScreen(
        title = "$aptNm · 그때와 지금", cacheId = "history-${target.complexId}", hint = "국토부 실거래 숫자를 그대로 넣어 만든 글이에요. 자유롭게 고쳐 쓰세요.",
        text = text, onText = { text = it },
        renderKey = Triple(points, areaKey, compare),
        render = points?.let { p -> { DealCards.history(ctx, aptNm, place, areaKey ?: 0, p, today, note = compare?.line) } },
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
    var areaKey by remember { mutableStateOf(prefs.dealArea.ifBlank { prefs.dealRecent.firstOrNull().orEmpty() }) }
    val area = remember(areaKey) { DealArea.of(core, areaKey) }
    var period by remember { mutableStateOf(DealPeriod.Y1) }
    var items by remember { mutableStateOf<List<DealTools.PresaleItem>?>(null) }
    var itemsError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { PresaleRepository.load(ctx) }.onSuccess { items = it }.onFailure { itemsError = it.message } }
    val (trades, error, progress) = rememberTrades(area, period)
    val today = remember { core.molit.today() }
    // 시군구마다 따로 짝을 지은 뒤 합쳐서 오른 금액이 큰 순으로
    val matches = remember(trades, items, band) {
        val t = trades; val its = items; val a = area
        if (t == null || its == null || a == null) null
        else a.codes.flatMap { code -> DealTools.presaleCompare(its, code, t.deals.filter { t.codeOf[it.complexId] == code }, band, today.year) }.sortedByDescending { it.diff }
    }
    val seed = remember { (System.currentTimeMillis() / 1000).toInt() }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(matches, period) { text = matches?.let { DealTools.presaleText(area?.label.orEmpty(), band, period, it, prefs.casualTone, seed, today) } ?: "" }
    val inRegion = items?.count { i -> area?.codes.orEmpty().any { it in i.lawd } } ?: 0
    ThreadShareScreen(
        title = "분양가 vs 실거래가", cacheId = "presale", hint = "청약홈 분양가와 국토부 실거래 숫자로 만든 글이에요. 평가 문장은 자유롭게 고쳐 쓰세요.",
        text = text, onText = { text = it },
        renderKey = Triple(matches, band, period),
        render = matches?.let { m -> { DealCards.presale(ctx, area?.cardPlace.orEmpty(), band, period, m, today) } },
        status = when {
            area == null -> "지역을 골라주세요."
            itemsError != null -> "⚠ $itemsError"
            error != null -> "⚠ $error"
            matches == null -> progress
            matches.isEmpty() -> "비교할 단지가 없어요. (이 지역 분양 공고 ${inRegion}건 중 최근 ${period.label} 안에 ${band}㎡ 실거래가 있는 10년 이내 단지 없음)"
            else -> null
        },
        onBack = onBack, onSent = onSent,
    ) {
        Label("평형")
        PillRow { DealTools.BANDS.forEach { b -> ChoicePill("${b}㎡", band == b) { band = b } } }
        RegionSelect(area) { areaKey = it }
        Label("실거래 기간")
        PillRow { TOOL_PERIODS.forEach { p -> ChoicePill(p.label, period == p) { period = p } } }
        Text("최근 10년 안에 지어진 단지만, 청약홈에 분양가가 남아 있는 최근 5년 분양 단지를 비교해요.", fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 지역별 최고 실거래 단지에 쓰는 자료: 시군구 하나의 기간 내 '단지별 최고가 거래' 상위권.
 * 5년이면 달마다 한 번씩 60번 넘게 받아야 해서, 받은 거래를 메모리에 다 들고 있지 않고 달마다 추려 가며 모음.
 * 달별 자료는 공용 캐시(다른 화면과 같이 씀)에, 추린 결과는 7일 동안 따로 저장해 둠
 */
object TopDeals {
    private const val KEEP = 30
    private const val TTL = 7 * 24 * 3600_000L

    private fun file(ctx: Context, code: String, months: Int) = File(ctx.cacheDir, "top/${code}_$months.json")

    /** 평형(0·59·84) → 그 시군구의 상위 단지들 */
    suspend fun load(ctx: Context, core: DealCore, code: String, period: DealPeriod, keepMonths: Boolean = true, onProgress: (Int, Int) -> Unit = { _, _ -> }): Map<Int, List<DealTools.TopItem>> = withContext(Dispatchers.IO) {
        // keepMonths: 달별 원자료를 공용 캐시에 저장할지. false면 받아서 추리기만 함
        fun month(ym: String) = if (keepMonths) core.molit.fetchMonth("trade", code, ym, memory = false) else core.molit.fetchLive("trade", code, ym)
        val f = file(ctx, code, period.months)
        runCatching {
            val o = org.json.JSONObject(f.readText())
            if (System.currentTimeMillis() - o.getLong("at") < TTL) {
                return@withContext DealTools.TOP_BANDS.associateWith { b -> o.getJSONArray("$b").objects().map { DealTools.TopItem(Deal.fromJson(it)) } }
            }
        }
        val today = core.molit.today()
        val since = period.since(today)
        val yms = Molit.recentYms(period.fetchMonths, today)
        var best = DealTools.TOP_BANDS.associateWith { emptyList<DealTools.TopItem>() }
        var failed = 0
        yms.chunked(4).forEachIndexed { k, chunk ->
            // 네 달씩 동시에 받음. 실패하면 한 번 더 시도
            val got = kotlinx.coroutines.coroutineScope {
                chunk.map { ym -> async { runCatching { month(ym) }.getOrElse { runCatching { month(ym) }.getOrNull() } } }.map { it.await() }
            }
            failed += got.count { it == null }
            val deals = got.filterNotNull().flatten().filter { it.date >= since }
            best = best.mapValues { (b, prev) -> DealTools.topDeals(deals, b, KEEP, prev) }
            onProgress(minOf((k + 1) * 4, yms.size), yms.size)
        }
        // 많이 빠졌으면(접속 문제) 저장하지 않고 알림 → 다음에 다시 받음
        if (failed > yms.size / 4) throw Exception("실거래 자료를 다 받지 못했어요 (${failed}개월 실패). 잠시 후 다시 시도해 주세요.")
        if (failed == 0) runCatching {
            f.parentFile?.mkdirs()
            val o = org.json.JSONObject().put("at", System.currentTimeMillis())
            best.forEach { (b, list) -> o.put("$b", org.json.JSONArray(list.map { it.deal.toJson() })) }
            f.writeText(o.toString())
        }
        best
    }
}

private val TOP_PERIODS = listOf(DealPeriod.Y1, DealPeriod.Y3, DealPeriod.Y5)

/** 지역별 최고 실거래 단지: 지역(시군구 또는 시도 전체)·기간(기본 5년)·평형을 고르면 단지별 최고가 1~10위 */
@Composable
fun TopScreen(onBack: () -> Unit, onSent: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val core = remember { DealCore.get(ctx) }
    var band by remember { mutableIntStateOf(0) }
    var areaKey by remember { mutableStateOf(prefs.dealArea.ifBlank { prefs.dealRecent.firstOrNull().orEmpty() }) }
    val area = remember(areaKey) { DealArea.of(core, areaKey) }
    // 기간: 시·군·구 하나는 5년이 기본, 시도 전체는 받을 양이 많아 1년이 기본 (3년·5년도 고를 수 있음)
    var picked by remember(area?.all) { mutableStateOf(if (area?.all == true) DealPeriod.Y1 else DealPeriod.Y5) }
    val period = picked
    var byBand by remember { mutableStateOf<Map<Int, List<DealTools.TopItem>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("실거래 받는 중…") }
    val today = remember { core.molit.today() }
    LaunchedEffect(areaKey, period) {
        byBand = null; error = null
        val a = area ?: return@LaunchedEffect
        try {
            var merged = DealTools.TOP_BANDS.associateWith { emptyList<DealTools.TopItem>() }
            a.codes.forEachIndexed { k, code ->
                val gu = a.guName[code].orEmpty()
                // 시도 전체를 3년·5년으로 볼 때는 달별 원자료를 기기에 저장하지 않음 (파일이 수천 개라 저장 공간이 불어남). 순위 결과만 7일 저장
                val one = TopDeals.load(ctx, core, code, period, keepMonths = !(a.all && period.months > 12)) { done, total ->
                    progress = "최근 ${period.label} 실거래 받는 중… " + (if (a.all) "$gu (${k + 1}/${a.codes.size}) " else "") + "${done}/${total}개월"
                }
                // 시도 전체는 시군구마다 상위권만 모아 합침
                merged = merged.mapValues { (b, prev) -> (prev + one[b].orEmpty().map { it.copy(gu = gu) }).sortedByDescending { it.price }.take(30) }
            }
            byBand = merged
            prefs.dealArea = a.key
        } catch (e: Exception) {
            error = e.message ?: "실거래 자료를 받지 못했어요"
        }
    }
    val items = byBand?.get(band)?.take(10)
    val seed = remember { (System.currentTimeMillis() / 1000).toInt() }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(items, period) { text = items?.let { DealTools.topText(area?.label.orEmpty(), band, period, it, prefs.casualTone, seed) } ?: "" }
    ThreadShareScreen(
        title = "지역별 최고 실거래 단지", cacheId = "top", hint = "국토부 실거래 최고가로 뽑은 순위예요. 자유롭게 고쳐 쓰세요.",
        text = text, onText = { text = it },
        renderKey = Triple(items, band, period),
        render = items?.let { list -> { DealCards.top(ctx, area?.cardPlace.orEmpty(), band, period, list, today) } },
        status = when { area == null -> "지역을 골라주세요."; error != null -> "⚠ $error"; items == null -> progress; items.isEmpty() -> "이 기간에 매매 실거래가 없어요."; else -> null },
        onBack = onBack, onSent = onSent,
    ) {
        RegionSelect(area) { areaKey = it }
        Label("기간")
        PillRow { TOP_PERIODS.forEach { p -> ChoicePill(p.label, period == p) { picked = p } } }
        if (area?.all == true && period.months > 12) Text(
            "시도 전체 ${period.label}은 처음에 몇 분 걸리고 국토부 하루 조회 한도를 많이 써요. 하루에 한두 곳만 돌리는 걸 권해요. 한 번 받으면 7일 동안은 바로 나와요.",
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.error,
        )
        Label("평형")
        PillRow { DealTools.TOP_BANDS.forEach { b -> ChoicePill(if (b == 0) "전체" else "${b}㎡", band == b) { band = b } } }
        Text(
            "단지마다 기간 안에서 가장 비싸게 거래된 한 건을 기준으로 순위를 매겨요. 5년치는 달마다 따로 받아서 처음에는 시간이 걸리고, 한 번 받으면 7일 동안 저장해 둬요.",
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
