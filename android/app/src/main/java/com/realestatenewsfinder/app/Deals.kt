package com.realestatenewsfinder.app

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.text.Collator
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong
import org.xml.sax.InputSource

/** 화면에 그대로 보여줄 수 있는 메시지를 가진 오류 */
open class ApiError(val status: Int, message: String) : RuntimeException(message)

class MolitException(message: String) : ApiError(502, message)

val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** 국토부 실거래 1건 (매매/전세/월세 공통) */
data class Deal(
    val type: String,
    val complexId: String,
    val aptNm: String,
    val umdNm: String,
    val jibun: String,
    val buildYear: Int?,
    val area: Double,
    val floor: Int,
    val date: String,
    val price: Long = 0,
    val dong: String = "",
    val dealingGbn: String = "",
    val canceled: Boolean = false,
    val cancelDate: String = "",
    val deposit: Long = 0,
    val monthlyRent: Long = 0,
    val contractType: String = "",
    val contractTerm: String = "",
    var key: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("complexId", complexId); put("aptNm", aptNm); put("umdNm", umdNm)
        put("jibun", jibun); put("buildYear", buildYear ?: JSONObject.NULL); put("area", area)
        put("floor", floor); put("date", date); put("canceled", canceled); put("key", key)
        if (type == "trade") {
            put("price", price); put("dong", dong); put("dealingGbn", dealingGbn); put("cancelDate", cancelDate)
        } else {
            put("deposit", deposit); put("monthlyRent", monthlyRent)
            put("contractType", contractType); put("contractTerm", contractTerm)
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = Deal(
            type = o.getString("type"), complexId = o.getString("complexId"), aptNm = o.optString("aptNm"),
            umdNm = o.optString("umdNm"), jibun = o.optString("jibun"),
            buildYear = if (o.isNull("buildYear")) null else o.optInt("buildYear"),
            area = o.optDouble("area", 0.0), floor = o.optInt("floor"), date = o.getString("date"),
            price = o.optLong("price"), dong = o.optString("dong"), dealingGbn = o.optString("dealingGbn"),
            canceled = o.optBoolean("canceled"), cancelDate = o.optString("cancelDate"),
            deposit = o.optLong("deposit"), monthlyRent = o.optLong("monthlyRent"),
            contractType = o.optString("contractType"), contractTerm = o.optString("contractTerm"),
            key = o.optString("key"),
        )
    }
}

object Molit {
    const val TRADE_URL = "https://apis.data.go.kr/1613000/RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade"
    const val RENT_URL = "https://apis.data.go.kr/1613000/RTMSDataSvcAptRent/getRTMSDataSvcAptRent"
    val TYPES = linkedMapOf("trade" to "매매", "jeonse" to "전세", "monthly" to "월세")

    fun apiForType(type: String) = if (type == "trade") "trade" else "rent"

    data class Parsed(val items: List<Map<String, String>>, val totalCount: Int)

    /** XML 응답 파싱 (javax DOM → 안드로이드/JVM 모두 동작) */
    fun parse(xml: String): Parsed {
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.newDocumentBuilder().parse(InputSource(StringReader(xml.trim())))
        } catch (e: Exception) {
            throw MolitException("국토부 서버 응답을 읽지 못했어요. 잠시 후 다시 시도해 주세요.")
        }
        val root = doc.documentElement
        fun text(parent: Element, tag: String): String {
            val n = parent.getElementsByTagName(tag)
            return if (n.length > 0) n.item(0).textContent.trim() else ""
        }
        if (root.tagName == "OpenAPI_ServiceResponse") {
            throw MolitException(friendly(text(root, "returnAuthMsg").ifEmpty { text(root, "errMsg") }, text(root, "returnReasonCode")))
        }
        if (root.tagName != "response") throw MolitException("알 수 없는 응답 형식입니다.")
        val code = text(root, "resultCode")
        if (code != "00" && code != "000") throw MolitException(friendly(text(root, "resultMsg"), code))

        val list = mutableListOf<Map<String, String>>()
        val items = root.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            val m = HashMap<String, String>()
            val kids = item.childNodes
            for (j in 0 until kids.length) {
                val k = kids.item(j)
                if (k is Element) m[k.tagName] = k.textContent.trim()
            }
            list.add(m)
        }
        return Parsed(list, text(root, "totalCount").toIntOrNull() ?: 0)
    }

    fun friendly(msg: String, code: String): String = when {
        msg.contains("SERVICE_KEY_IS_NOT_REGISTERED") || msg.contains("SERVICE KEY") || code == "30" || code == "20" ->
            "API 키 인증에 실패했어요. 키를 정확히 복사했는지, 매매·전월세 두 API 모두 활용신청했는지 확인해 주세요. (승인 직후라면 1~2시간 뒤 다시 시도)"
        msg.contains("LIMITED_NUMBER") || code == "22" -> "오늘 API 호출 한도를 초과했어요. 내일 다시 시도해 주세요."
        msg.contains("DEADLINE") || code == "31" -> "API 활용기간이 만료됐어요. 공공데이터포털에서 연장 신청해 주세요."
        else -> "국토부 API 오류: $msg ($code)"
    }

    private fun num(s: String?): Double = s?.replace(",", "")?.trim()?.toDoubleOrNull() ?: 0.0

    fun complexKey(lawdCd: String, umdNm: String?, jibun: String?, aptNm: String?) =
        listOf(lawdCd, umdNm.orEmpty().trim(), jibun.orEmpty().trim(), aptNm.orEmpty().replace(Regex("\\s+"), "")).joinToString("|")

    fun complexId(key: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.substring(0, 12)
    }

    private fun date(it: Map<String, String>) =
        "%d-%02d-%02d".format(num(it["dealYear"]).toInt(), num(it["dealMonth"]).toInt(), num(it["dealDay"]).toInt())

    fun normalizeTrade(lawdCd: String, it: Map<String, String>) = Deal(
        type = "trade",
        complexId = complexId(complexKey(lawdCd, it["umdNm"], it["jibun"], it["aptNm"])),
        aptNm = it["aptNm"].orEmpty(), umdNm = it["umdNm"].orEmpty(), jibun = it["jibun"].orEmpty(),
        buildYear = num(it["buildYear"]).toInt().takeIf { y -> y > 0 },
        area = num(it["excluUseAr"]), floor = num(it["floor"]).toInt(), date = date(it),
        price = num(it["dealAmount"]).toLong(), dong = it["aptDong"].orEmpty(), dealingGbn = it["dealingGbn"].orEmpty(),
        canceled = it["cdealType"].orEmpty().trim() == "O", cancelDate = it["cdealDay"].orEmpty(),
    )

    fun normalizeRent(lawdCd: String, it: Map<String, String>): Deal {
        val rent = num(it["monthlyRent"]).toLong()
        return Deal(
            type = if (rent > 0) "monthly" else "jeonse",
            complexId = complexId(complexKey(lawdCd, it["umdNm"], it["jibun"], it["aptNm"])),
            aptNm = it["aptNm"].orEmpty(), umdNm = it["umdNm"].orEmpty(), jibun = it["jibun"].orEmpty(),
            buildYear = num(it["buildYear"]).toInt().takeIf { y -> y > 0 },
            area = num(it["excluUseAr"]), floor = num(it["floor"]).toInt(), date = date(it),
            deposit = num(it["deposit"]).toLong(), monthlyRent = rent,
            contractType = it["contractType"].orEmpty(), contractTerm = it["contractTerm"].orEmpty(),
        )
    }

    /** 거래 고유 키 (동일 조건 거래가 여러 건이면 #n 으로 구분) */
    fun assignKeys(deals: List<Deal>): List<Deal> {
        val seen = HashMap<String, Int>()
        for (d in deals) {
            val amount = if (d.type == "trade") "${d.price}" else "${d.deposit}/${d.monthlyRent}"
            val k = listOf(d.type, d.complexId, d.date, d.area, d.floor, amount, d.dong).joinToString("|")
            val n = (seen[k] ?: 0) + 1
            seen[k] = n
            d.key = "$k#$n"
        }
        return deals
    }

    /** 이번 달 포함 최근 n개월 YYYYMM */
    fun recentYms(n: Int, today: LocalDate): List<String> =
        (0 until n).map { today.withDayOfMonth(1).minusMonths(it.toLong()) }.map { "%d%02d".format(it.year, it.monthValue) }
}

fun interface HttpGet {
    fun get(url: String, headers: Map<String, String>): Pair<Int, String>
}

val defaultHttp = HttpGet { url, headers ->
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 25000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    try {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        code to stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: java.io.IOException) {
        throw MolitException("인터넷 연결을 확인해 주세요. (${e.javaClass.simpleName})")
    } finally {
        c.disconnect()
    }
}

/**
 * 국토부 API 호출 + 캐시 (이번 달·지난달: 6시간, 이전 달: 7일. 둘 다 디스크에 저장)
 * 데이터 경로: 개인 API 키가 있으면 국토부 직접 호출, 없으면 중계 서버(proxy) 경유
 */
class MolitClient(
    private val keyProvider: () -> String,
    private val proxyProvider: () -> String,
    private val proxyToken: String,
    private val http: HttpGet,
    private val cacheDir: File?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mem = ConcurrentHashMap<String, Pair<Long, List<Deal>>>()
    private val pool = Executors.newFixedThreadPool(4)

    fun today(): LocalDate = java.time.Instant.ofEpochMilli(clock()).atZone(KST).toLocalDate()

    private fun encodeKey(key: String) =
        if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(key)) key else URLEncoder.encode(key, "UTF-8")

    fun fetchLive(
        api: String,
        lawdCd: String,
        ym: String,
        key: String = keyProvider(),
        proxy: String = proxyProvider(),
    ): List<Deal> {
        val k = key.trim()
        val px = proxy.trim().trimEnd('/')
        if (k.isEmpty() && px.isEmpty()) {
            throw ApiError(400, "데이터 연결이 설정되지 않았어요. ⚙️ 설정에서 중계 서버 주소 또는 API 키를 입력해 주세요.")
        }
        if (k.isEmpty() && !px.startsWith("https://")) throw ApiError(400, "중계 서버 주소는 https:// 로 시작해야 해요.")
        val base = if (api == "trade") Molit.TRADE_URL else Molit.RENT_URL
        val headers = if (k.isEmpty() && proxyToken.isNotEmpty()) mapOf("X-App-Token" to proxyToken) else emptyMap()
        val all = mutableListOf<Map<String, String>>()
        for (page in 1..50) {
            val params = "LAWD_CD=$lawdCd&DEAL_YMD=$ym&pageNo=$page&numOfRows=1000"
            val url = if (k.isNotEmpty()) "$base?serviceKey=${encodeKey(k)}&$params" else "$px/molit/$api?$params"
            val (status, body) = http.get(url, headers)
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{")) {
                // 중계 서버의 오류 메시지
                val msg = runCatching { JSONObject(trimmed).optString("error") }.getOrNull().orEmpty()
                throw MolitException(msg.ifEmpty { "중계 서버 오류 (HTTP $status)" })
            }
            if (!trimmed.startsWith("<")) {
                throw MolitException(
                    when {
                        k.isEmpty() -> "중계 서버에 연결하지 못했어요 (HTTP $status). 주소를 확인해 주세요."
                        status == 401 || status == 403 || body.contains("Unauthorized", true) -> Molit.friendly("SERVICE_KEY_IS_NOT_REGISTERED", "30")
                        else -> "국토부 서버 오류 (HTTP $status). 잠시 후 다시 시도해 주세요."
                    },
                )
            }
            val parsed = Molit.parse(body)
            all.addAll(parsed.items)
            if (parsed.items.isEmpty() || all.size >= parsed.totalCount) break
        }
        val deals = all.map { if (api == "trade") Molit.normalizeTrade(lawdCd, it) else Molit.normalizeRent(lawdCd, it) }
        return Molit.assignKeys(deals)
    }

    companion object {
        const val OLD_TTL = 7 * 24 * 3600 * 1000L
        const val RECENT_TTL = 6 * 3600 * 1000L
    }

    private fun diskFile(ck: String) = cacheDir?.let { File(it, "molit/${ck.replace(':', '_')}.json") }

    /**
     * 한 시군구의 한 달 거래. 받은 자료는 임시 저장 공간(캐시)에 두고 다시 씀 → 국토부 호출 횟수를 줄임.
     *  - 지난 달들(이번 달·지난달 제외): 7일. 신고가 끝난 달이라 거의 바뀌지 않음
     *  - 이번 달·지난달: 6시간. 새 신고가 계속 들어오므로 짧게 (앱을 껐다 켜도 유지)
     * force: 캐시를 무시하고 새로 받음 (새 실거래 알림 확인용 → 알림은 늘 최신 자료로)
     * memory: false면 메모리에 올려 두지 않음 (5년치처럼 양이 많을 때)
     */
    fun fetchMonth(api: String, lawdCd: String, ym: String, force: Boolean = false, memory: Boolean = true): List<Deal> {
        val ck = "$api:$lawdCd:$ym"
        val recent = ym in Molit.recentYms(2, today())
        val ttl = if (recent) RECENT_TTL else OLD_TTL
        val now = clock()
        if (!force) {
            mem[ck]?.let { if (now - it.first < ttl) return it.second }
            diskFile(ck)?.takeIf { it.exists() }?.let { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    if (now - o.getLong("at") < ttl) {
                        val arr = o.getJSONArray("deals")
                        val deals = (0 until arr.length()).map { Deal.fromJson(arr.getJSONObject(it)) }
                        if (memory) mem[ck] = o.getLong("at") to deals
                        return deals
                    }
                }
            }
        }
        val deals = fetchLive(api, lawdCd, ym)
        if (memory) mem[ck] = now to deals
        diskFile(ck)?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(JSONObject().put("at", now).put("deals", JSONArray(deals.map { it.toJson() })).toString())
            }
        }
        return deals
    }

    /** 기한이 지난 캐시 파일 지우기 (저장 공간이 계속 불어나지 않게). 앱을 켤 때 한 번 */
    fun cleanCache() {
        val dir = cacheDir?.let { File(it, "molit") } ?: return
        val now = clock()
        runCatching { dir.listFiles()?.forEach { f -> if (now - f.lastModified() > OLD_TTL) f.delete() } }
    }

    /** 지정한 달들을 한꺼번에 (과거 가격 비교용). 지난 달 자료는 7일 동안 디스크에 캐시됨 */
    fun fetchYms(api: String, lawdCd: String, yms: List<String>): List<Deal> {
        val futures = yms.map { ym -> pool.submit(Callable { fetchMonth(api, lawdCd, ym) }) }
        return futures.flatMap {
            try {
                it.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    fun fetchMonths(api: String, lawdCd: String, months: Int): List<Deal> {
        val futures = Molit.recentYms(months, today()).map { ym -> pool.submit(Callable { fetchMonth(api, lawdCd, ym) }) }
        return futures.flatMap {
            try {
                it.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }
}


// ---------------------------------------------------------------------------
// 저장소: filesDir/db.json (구독, 알림함, 본 거래 기록, 설정)
// ---------------------------------------------------------------------------
class DealStore(private val file: File?) {
    val data: JSONObject = load()

    private fun load(): JSONObject {
        val o = file?.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: JSONObject()
        if (!o.has("subscriptions")) o.put("subscriptions", JSONArray())
        if (!o.has("notifications")) o.put("notifications", JSONArray())
        if (!o.has("seen")) o.put("seen", JSONObject())
        if (!o.has("settings")) o.put("settings", JSONObject())
        return o
    }

    val subscriptions: JSONArray get() = data.getJSONArray("subscriptions")
    val notifications: JSONArray get() = data.getJSONArray("notifications")
    val seen: JSONObject get() = data.getJSONObject("seen")
    val settings: JSONObject get() = data.getJSONObject("settings")

    @Synchronized
    fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(data.toString())
        if (!tmp.renameTo(f)) { f.writeText(data.toString()); tmp.delete() }
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

// ---------------------------------------------------------------------------
// 시군구
// ---------------------------------------------------------------------------
class DealRegions(json: String) {
    val raw = JSONArray(json)
    private val byCode = HashMap<String, JSONObject>()

    init {
        for (r in raw.objects()) {
            val sido = r.getString("sido")
            for (s in r.getJSONArray("sigungu").objects()) {
                byCode[s.getString("code")] = JSONObject()
                    .put("code", s.getString("code")).put("name", s.getString("name"))
                    .put("sido", sido).put("fullName", "$sido ${s.getString("name")}")
            }
        }
    }

    fun get(code: String?): JSONObject? = code?.let { byCode[it] }
}

// ---------------------------------------------------------------------------
// 포맷 (알림 문구)
// ---------------------------------------------------------------------------
object DealFmt {
    private fun comma(n: Long) = "%,d".format(n)
    fun manwon(v: Long): String {
        val eok = v / 10000
        val rest = v % 10000
        return when {
            eok > 0 && rest > 0 -> "${eok}억 ${comma(rest)}만"
            eok > 0 -> "${eok}억"
            else -> "${comma(rest)}만"
        }
    }

    fun price(d: Deal) = when (d.type) {
        "trade" -> manwon(d.price)
        "jeonse" -> manwon(d.deposit)
        else -> "${manwon(d.deposit)} / 월 ${comma(d.monthlyRent)}만"
    }

    /** 84.9329 → "84.93" (국토부 원자료는 소수 넷째 자리까지 옴) */
    fun area(a: Double): String = java.math.BigDecimal(a).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun line(d: Deal): String {
        val md = d.date.substring(5).replace('-', '/')
        return "[${Molit.TYPES[d.type]}] ${area(d.area)}㎡ ${d.floor}층 · ${price(d)} · $md${if (d.canceled) " (해제)" else ""}"
    }
}

// ---------------------------------------------------------------------------
// 단지 목록 / 거래 조회
// ---------------------------------------------------------------------------
class DealService(private val molit: MolitClient, val complexMonths: Int = 6) {
    private val collator = Collator.getInstance(Locale.KOREAN)

    fun getComplexes(lawdCd: String): List<JSONObject> {
        val all = molit.fetchMonths("trade", lawdCd, complexMonths) + molit.fetchMonths("rent", lawdCd, complexMonths)
        val map = LinkedHashMap<String, JSONObject>()
        for (d in all) {
            val c = map.getOrPut(d.complexId) {
                JSONObject().put("id", d.complexId).put("lawdCd", lawdCd).put("aptNm", d.aptNm).put("umdNm", d.umdNm)
                    .put("jibun", d.jibun).put("buildYear", d.buildYear ?: JSONObject.NULL)
                    .put("counts", JSONObject().put("trade", 0).put("jeonse", 0).put("monthly", 0))
                    .put("lastDealDate", JSONObject.NULL).put("lastTradePrice", JSONObject.NULL).put("lastTradeDate", JSONObject.NULL)
            }
            if (c.isNull("buildYear") && d.buildYear != null) c.put("buildYear", d.buildYear)
            if (d.canceled) continue
            val counts = c.getJSONObject("counts")
            counts.put(d.type, counts.getInt(d.type) + 1)
            if (c.isNull("lastDealDate") || d.date > c.getString("lastDealDate")) c.put("lastDealDate", d.date)
            if (d.type == "trade" && (c.isNull("lastTradeDate") || d.date > c.getString("lastTradeDate"))) {
                c.put("lastTradeDate", d.date).put("lastTradePrice", d.price)
            }
        }
        return map.values.sortedWith { a, b -> collator.compare(a.getString("aptNm"), b.getString("aptNm")) }
    }

    fun getComplex(lawdCd: String, id: String) = getComplexes(lawdCd).firstOrNull { it.getString("id") == id }

    fun getDeals(lawdCd: String, complexId: String, type: String, months: Int = complexMonths, force: Boolean = false): List<Deal> {
        val api = Molit.apiForType(type)
        val all = if (force) Molit.recentYms(months, molit.today()).flatMap { molit.fetchMonth(api, lawdCd, it, true) }
        else molit.fetchMonths(api, lawdCd, months)
        return all.filter { it.complexId == complexId && it.type == type }
            .sortedWith(compareByDescending<Deal> { it.date }.thenByDescending { it.floor })
    }

    companion object {
        fun summarize(deals: List<Deal>): JSONObject {
            val valid = deals.filter { !it.canceled }
            if (valid.isEmpty()) return JSONObject().put("count", 0)
            val v = valid.map { if (it.type == "trade") it.price else it.deposit }
            val perPy = valid.map { (if (it.type == "trade") it.price else it.deposit) / (it.area / 3.3058) }
            return JSONObject().put("count", valid.size).put("avg", v.average().roundToLong())
                .put("min", v.min()).put("max", v.max()).put("avgPerPyeong", perPy.average().roundToLong())
        }
    }
}

// ---------------------------------------------------------------------------
// 알림 구독 / 신규·해제 거래 감지
// ---------------------------------------------------------------------------
fun interface DealNotifier {
    /** @return 실제로 알림을 표시했으면 true */
    fun show(id: Int, title: String, body: String, hash: String): Boolean
}

class DealAlerts(
    private val store: DealStore,
    private val molit: MolitClient,
    private val service: DealService,
    private val notifier: DealNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val POLL_MONTHS = 2 // 신고기한 30일 → 이번 달 + 지난달만 계속 바뀜
        const val MAX_NOTIFICATIONS = 300
    }

    private fun nowIso() = Instant.ofEpochMilli(clock()).toString()

    /** 최신 계약일 순으로 정렬된 거래 목록 → 알림 본문 */
    private fun sorted(deals: List<Deal>) = deals.sortedWith(compareByDescending<Deal> { it.date }.thenByDescending { it.floor })

    private fun body(deals: List<Deal>): String {
        val s = sorted(deals)
        val lines = s.take(5).map(DealFmt::line).toMutableList()
        if (s.size > 5) lines.add("외 ${s.size - 5}건")
        return lines.joinToString("\n")
    }

    /** "매매 2 · 전세 1" */
    private fun typeSummary(deals: List<Deal>) =
        Molit.TYPES.entries.mapNotNull { (t, l) -> deals.count { it.type == t }.takeIf { it > 0 }?.let { "$l $it" } }.joinToString(" · ")

    private fun notify(sub: JSONObject, title: String, body: String, kind: String, deals: List<Deal> = emptyList(), type: String = sub.getString("type")) {
        val n = JSONObject().put("id", UUID.randomUUID().toString()).put("subscriptionId", sub.getString("id"))
            .put("lawdCd", sub.getString("lawdCd")).put("complexId", sub.getString("complexId")).put("type", type)
            .put("aptNm", sub.optString("aptNm")).put("umdNm", sub.optString("umdNm"))
            .put("kind", kind).put("title", title).put("body", body).put("createdAt", nowIso()).put("read", false)
            .put("deals", JSONArray(sorted(deals).take(30).map { it.toJson() }))
        val list = store.notifications
        list.put(n)
        while (list.length() > MAX_NOTIFICATIONS) list.remove(0)
        store.save()
        // 알림을 누르면 그 단지 상세 화면에서 해당 거래로 이동 (NEW 표시)
        val hash = "#/complex/${sub.getString("lawdCd")}/${sub.getString("complexId")}?type=$type&n=${n.getString("id")}"
        // 같은 단지는 휴대폰 알림도 하나로 (새 알림이 이전 알림을 대체)
        notifier.show("${sub.getString("lawdCd")}:${sub.getString("complexId")}".hashCode(), title, body, hash)
    }

    private fun seenFor(api: String, lawdCd: String): JSONObject? = store.seen.optJSONObject("$api:$lawdCd")

    private fun ensureBaseline(api: String, lawdCd: String) {
        if (seenFor(api, lawdCd) != null) return
        val months = JSONObject()
        for (ym in Molit.recentYms(POLL_MONTHS, molit.today())) {
            val m = JSONObject()
            for (d in molit.fetchMonth(api, lawdCd, ym)) m.put(d.key, if (d.canceled) "canceled" else "1")
            months.put(ym, m)
        }
        store.seen.put("$api:$lawdCd", JSONObject().put("initializedAt", nowIso()).put("months", months))
        store.save()
    }

    @Synchronized
    fun subscribe(lawdCd: String, complexId: String, type: String): Pair<JSONObject, Int> {
        if (type !in Molit.TYPES) throw ApiError(400, "type은 trade|jeonse|monthly 중 하나여야 합니다")
        store.subscriptions.objects().firstOrNull {
            it.getString("lawdCd") == lawdCd && it.getString("complexId") == complexId && it.getString("type") == type
        }?.let { return it to 0 }

        val complex = service.getComplex(lawdCd, complexId) ?: throw ApiError(404, "단지를 찾을 수 없습니다")
        val sub = JSONObject().put("id", UUID.randomUUID().toString()).put("lawdCd", lawdCd).put("complexId", complexId)
            .put("aptNm", complex.getString("aptNm")).put("umdNm", complex.getString("umdNm")).put("type", type)
            .put("createdAt", nowIso())
        val api = Molit.apiForType(type)
        ensureBaseline(api, lawdCd)

        val since = molit.today().minusDays(30).toString()
        val recent = service.getDeals(lawdCd, complexId, type, POLL_MONTHS).filter { it.date >= since }
        // 직전 확인 이후 신고돼 '최근 한 달'로 이미 보낸 거래는 다음 확인 때 중복 알림하지 않음
        val months = seenFor(api, lawdCd)!!.getJSONObject("months")
        val skip = recent.filter { d -> months.optJSONObject(d.date.substring(0, 7).replace("-", ""))?.has(d.key) != true }.map { it.key }
        sub.put("skipKeys", JSONArray(skip))
        store.subscriptions.put(sub)
        store.save()

        val label = "${complex.getString("aptNm")} ${Molit.TYPES[type]}"
        notify(
            sub, "🔔 $label 알림 시작",
            if (recent.isNotEmpty()) "최근 한 달 실거래 ${recent.size}건\n${body(recent)}" else "최근 한 달간 신고된 실거래가 없습니다. 새 거래가 신고되면 알려드릴게요.",
            "recent",
            recent,
        )
        return sub to recent.size
    }

    @Synchronized
    fun unsubscribe(id: String): Boolean {
        val list = store.subscriptions
        for (i in 0 until list.length()) {
            if (list.getJSONObject(i).getString("id") == id) {
                list.remove(i)
                store.save()
                return true
            }
        }
        return false
    }

    data class PollResult(val groups: Int, val sent: Int, val errors: List<Pair<String, String>>)

    @Synchronized
    fun poll(): PollResult {
        val subs = store.subscriptions.objects()
        val groups = subs.groupBy { "${Molit.apiForType(it.getString("type"))}:${it.getString("lawdCd")}" }
        val yms = Molit.recentYms(POLL_MONTHS, molit.today())
        var sent = 0
        val errors = mutableListOf<Pair<String, String>>()
        // 단지별로 모아서(매매·전세·월세 섞어) 알림 1개로 보냄
        data class Bucket(val sub: JSONObject, val fresh: MutableList<Deal> = mutableListOf(), val canceled: MutableList<Deal> = mutableListOf())
        val buckets = LinkedHashMap<String, Bucket>()

        for ((g, gsubs) in groups) {
            val (api, lawdCd) = g.split(":")
            try {
                ensureBaseline(api, lawdCd)
                val rec = seenFor(api, lawdCd)!!
                val prevMonths = rec.getJSONObject("months")
                val fresh = mutableListOf<Deal>()
                val canceled = mutableListOf<Deal>()
                val next = JSONObject()
                for (ym in yms) {
                    val deals = molit.fetchMonth(api, lawdCd, ym, force = true)
                    val prev = prevMonths.optJSONObject(ym) ?: JSONObject()
                    val cur = JSONObject()
                    for (d in deals) {
                        cur.put(d.key, if (d.canceled) "canceled" else "1")
                        if (!prev.has(d.key)) {
                            if (!d.canceled) fresh.add(d)
                        } else if (d.canceled && prev.getString(d.key) != "canceled") {
                            canceled.add(d)
                        }
                    }
                    for (k in prev.keys()) if (!cur.has(k)) cur.put(k, prev.get(k))
                    next.put(ym, cur)
                }
                rec.put("months", next)
                store.save()

                for (s in gsubs) {
                    val skip = s.optJSONArray("skipKeys")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                    s.remove("skipKeys")
                    val type = s.getString("type")
                    val b = buckets.getOrPut("${s.getString("lawdCd")}:${s.getString("complexId")}") { Bucket(s) }
                    b.fresh += fresh.filter { it.complexId == s.getString("complexId") && it.type == type && it.key !in skip }
                    b.canceled += canceled.filter { it.complexId == s.getString("complexId") && it.type == type }
                }
            } catch (e: Exception) {
                errors.add(g to (e.message ?: e.javaClass.simpleName))
            }
        }
        val wantCanceled = store.settings.optBoolean("notiCanceled", true)
        for (b in buckets.values) {
            if (!wantCanceled) b.canceled.clear()
            if (b.fresh.isEmpty() && b.canceled.isEmpty()) continue
            val apt = b.sub.getString("aptNm")
            val title = when {
                b.fresh.isNotEmpty() && b.canceled.isNotEmpty() -> "🏠 $apt 새 실거래 ${b.fresh.size}건 · 해제 ${b.canceled.size}건"
                b.fresh.isNotEmpty() -> "🏠 $apt 새 실거래 ${b.fresh.size}건 (${typeSummary(b.fresh)})"
                else -> "⚠️ $apt 거래 해제 ${b.canceled.size}건"
            }
            val all = b.fresh + b.canceled
            notify(b.sub, title, body(all), if (b.fresh.isNotEmpty()) "new" else "canceled", all, sorted(all).first().type)
            sent++
        }
        val keys = store.seen.keys().asSequence().toList()
        for (k in keys) if (k !in groups) store.seen.remove(k)
        store.data.put("lastPollAt", nowIso())
        store.save()
        return PollResult(groups.size, sent, errors)
    }
}
