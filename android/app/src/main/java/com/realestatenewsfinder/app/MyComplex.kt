package com.realestatenewsfinder.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * 내 단지: 깊게 보는 메인 단지들의 실거래 분석과 경매 물건.
 * 중개사 슈퍼앱 저장소의 수집기가 3시간마다 만들어 공용 임시저장소(store 브랜치)의 main/my.json에 올리고, 앱은 그걸 받아 보여줌.
 * 단지 분석·경매 물건마다 올릴 글(post)이 보고서함과 같은 꼴로 들어 있음.
 */
data class MyArea(
    val area: Int, val tradeCount: Int, val tradeMedian: Long, val tradeMin: Long, val tradeMax: Long, val changePct: Double?,
    val highPrice: Long, val highDate: String, val jeonseCount: Int, val jeonseMedian: Long, val jeonseRate: Int?,
    val monthlyCount: Int, val monthlyDeposit: Long, val monthlyRent: Long,
)

data class MyDeal(val type: String, val date: String, val area: Double, val floor: Int, val price: Long, val deposit: Long, val monthlyRent: Long, val canceled: Boolean) {
    fun amount(): String = when (type) {
        "trade" -> DealFmt.manwon(price)
        "monthly" -> DealFmt.manwon(deposit) + " / 월 " + monthlyRent + "만"
        else -> DealFmt.manwon(deposit)
    }
}

data class MyComplex(
    val id: String, val name: String, val kind: String, val lawdCd: String, val region: String, val umdNm: String,
    val aptNm: String, val buildYear: Int?, val complexId: String, val hasData: Boolean,
    val trade12: Int, val tradePrev: Int, val jeonse12: Int, val monthly12: Int,
    val insights: List<String>, val areas: List<MyArea>, val recentTrades: List<MyDeal>, val recentRents: List<MyDeal>,
    val auctionCount: Int, val post: Report?,
) {
    val kindLabel: String get() = if (kind == "offi") "오피스텔" else "아파트"
    /** 앱의 단지 상세(실거래 내역·알림)로 넘어갈 위치. 오피스텔처럼 실거래 연결이 없으면 null */
    val target: DealTarget? get() = if (complexId.isNotBlank()) DealTarget(lawdCd, complexId) else null
}

data class MyAuction(
    val id: String, val complexId: String, val complexName: String, val caseNo: String, val court: String, val dept: String, val tel: String,
    val usage: String, val address: String, val unit: String, val area: Double?,
    val appraisal: Long, val minPrice: Long, val minRate: Int, val failCount: Int,
    val saleDate: String, val saleTime: String, val place: String, val decisionDate: String,
    val caseName: String, val receivedDate: String, val claim: Long,
    val rights: String, val topPriority: String, val specRemark: String, val remark: String, val landShare: String,
    val schedule: List<Step>, val appraisalNotes: List<String>,
    val firstSeen: String, val gone: Boolean, val post: Report?,
) {
    data class Step(val date: String, val time: String, val kind: String, val result: String, val minPrice: Long, val soldPrice: Long)

    /** 알림을 한 번만 보내기 위한 표시: 같은 물건이라도 최저가나 매각기일이 바뀌면 다시 알림 */
    val stamp: String get() = "$id|$minPrice|$saleDate"

    /** 매각기일까지 남은 날 (지났으면 음수, 날짜가 없으면 null) */
    fun daysLeft(today: LocalDate = LocalDate.now(KST)): Long? = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(saleDate)) }.getOrNull()

    fun dday(today: LocalDate = LocalDate.now(KST)): String = when (val d = daysLeft(today)) {
        null -> ""
        0L -> "오늘"
        else -> if (d > 0) "D-$d" else "기일 지남"
    }
}

data class MyData(val generatedAt: String, val today: String, val court: String, val notes: List<String>, val complexes: List<MyComplex>, val auctions: List<MyAuction>) {
    /** 진행 중인 경매 (매각기일 빠른 순) */
    val live: List<MyAuction> get() = auctions.filter { !it.gone }
    val time: Long get() = runCatching { java.time.Instant.parse(generatedAt).toEpochMilli() }.getOrDefault(0L)
}

object My {
    /** 원 → "3억 8,300만원" */
    fun won(v: Long): String = DealFmt.manwon(v / 10000) + "원"

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }
    private fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

    fun parse(json: String): MyData {
        val root = JSONObject(json)
        val today = root.str("today")
        val complexes = objects(root.optJSONArray("complexes")).map { o ->
            val c = o.optJSONObject("counts") ?: JSONObject()
            val id = o.str("id")
            MyComplex(
                id = id, name = o.str("name"), kind = o.str("kind"), lawdCd = o.str("lawdCd"), region = o.str("region"), umdNm = o.str("umdNm"),
                aptNm = o.str("aptNm"), buildYear = if (o.isNull("buildYear")) null else o.optInt("buildYear"), complexId = o.str("complexId"), hasData = o.optBoolean("hasData"),
                trade12 = c.optInt("trade12"), tradePrev = c.optInt("tradePrev"), jeonse12 = c.optInt("jeonse12"), monthly12 = c.optInt("monthly12"),
                insights = strings(o.optJSONArray("insights")),
                areas = objects(o.optJSONArray("areas")).map { a ->
                    val t = a.optJSONObject("trade") ?: JSONObject()
                    val j = a.optJSONObject("jeonse") ?: JSONObject()
                    val m = a.optJSONObject("monthly") ?: JSONObject()
                    val h = t.optJSONObject("high")
                    MyArea(
                        area = a.optInt("area"), tradeCount = t.optInt("count12"), tradeMedian = t.optLong("median12"), tradeMin = t.optLong("min12"), tradeMax = t.optLong("max12"),
                        changePct = if (t.isNull("changePct")) null else t.optDouble("changePct"),
                        highPrice = h?.optLong("price") ?: 0, highDate = h?.optString("date").orEmpty(),
                        jeonseCount = j.optInt("count12"), jeonseMedian = j.optLong("median12"), jeonseRate = if (a.isNull("jeonseRate")) null else a.optInt("jeonseRate"),
                        monthlyCount = m.optInt("count12"), monthlyDeposit = m.optLong("medianDeposit"), monthlyRent = m.optLong("medianRent"),
                    )
                },
                recentTrades = objects(o.optJSONArray("recentTrades")).map(::deal), recentRents = objects(o.optJSONArray("recentRents")).map(::deal),
                auctionCount = o.optInt("auctionCount"),
                post = o.optJSONObject("post")?.let { Reports.one(it, "my-$id-$today", today) },
            )
        }
        val auctions = objects(root.optJSONArray("auctions")).map { o ->
            val id = o.str("id")
            MyAuction(
                id = id, complexId = o.str("complexId"), complexName = o.str("complexName"), caseNo = o.str("caseNo"), court = o.str("court"), dept = o.str("dept"), tel = o.str("tel"),
                usage = o.str("usage"), address = o.str("address"), unit = o.str("unit"), area = if (o.isNull("area")) null else o.optDouble("area"),
                appraisal = o.optLong("appraisal"), minPrice = o.optLong("minPrice"), minRate = o.optInt("minRate"), failCount = o.optInt("failCount"),
                saleDate = o.str("saleDate"), saleTime = o.str("saleTime"), place = o.str("place"), decisionDate = o.str("decisionDate"),
                caseName = o.str("caseName"), receivedDate = o.str("receivedDate"), claim = o.optLong("claim"),
                rights = o.str("rights"), topPriority = o.str("topPriority"), specRemark = o.str("specRemark"), remark = o.str("remark"), landShare = o.str("landShare"),
                schedule = objects(o.optJSONArray("schedule")).map { s -> MyAuction.Step(s.str("date"), s.str("time"), s.str("kind"), s.str("result"), s.optLong("minPrice"), s.optLong("soldPrice")) },
                appraisalNotes = strings(o.optJSONArray("appraisalNotes")),
                firstSeen = o.str("firstSeen"), gone = o.optBoolean("gone"),
                // 최저가가 바뀌면 다른 글로 봄 → '✓ 올림' 표시가 새로 시작
                post = o.optJSONObject("post")?.let { Reports.one(it, "auction-$id-${o.optLong("minPrice")}", today) },
            )
        }
        return MyData(root.str("generatedAt"), today, root.str("court"), strings(root.optJSONArray("notes")), complexes, auctions)
    }

    private fun deal(d: JSONObject) = MyDeal(d.optString("type"), d.optString("date"), d.optDouble("area"), d.optInt("floor"), d.optLong("price"), d.optLong("deposit"), d.optLong("monthlyRent"), d.optBoolean("canceled"))

    const val URL_MY = "https://raw.githubusercontent.com/happyfamily1984/realtor-super-app/store/main/my.json"

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "my.json")

    suspend fun fetch(ctx: Context): MyData = withContext(Dispatchers.IO) {
        val conn = URL("$URL_MY?t=${System.currentTimeMillis() / 60000}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        try {
            if (conn.responseCode != 200) throw Exception("서버 응답 ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val data = parse(text)
            cacheFile(ctx).writeText(text)
            data
        } finally {
            conn.disconnect()
        }
    }

    fun cached(ctx: Context): MyData? = runCatching { parse(cacheFile(ctx).readText()) }.getOrNull()

    /**
     * 알림 보낼 경매: 처음 보는 물건(new)과 최저가·매각기일이 바뀐 물건(changed).
     * seen이 null이면(처음 실행) 지금 있는 물건은 기준점으로만 잡고 알리지 않음.
     */
    fun freshAuctions(data: MyData, seen: Set<String>?): List<Pair<MyAuction, Boolean>> {
        if (seen == null) return emptyList()
        val knownIds = seen.map { it.substringBefore('|') }.toSet()
        return data.live.filter { it.stamp !in seen }.map { it to (it.id !in knownIds) }
    }

    fun alertTitle(a: MyAuction, isNew: Boolean) = if (isNew) "⚖️ ${a.complexName} 경매 물건이 나왔어요" else "⚖️ ${a.complexName} 경매 일정·최저가가 바뀌었어요"

    fun alertBody(a: MyAuction): String = listOfNotNull(
        listOfNotNull(a.unit.ifBlank { null }, a.area?.let { "전용 ${DealFmt.area(it)}㎡" }).joinToString(" · ").ifBlank { null },
        "최저가 ${won(a.minPrice)} (감정가 ${won(a.appraisal)}의 ${a.minRate}%) · 유찰 ${a.failCount}회",
        "매각기일 ${a.saleDate} ${a.saleTime} · ${a.court} ${a.caseNo}",
    ).joinToString("\n")
}

/** 3시간마다 내 단지 자료를 받아, 메인 단지의 새 경매 물건을 알림으로 띄움 */
class AuctionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val data = try { My.fetch(applicationContext) } catch (e: Exception) { return Result.retry() }
        check(applicationContext, data)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "auction-check"
        const val CHANNEL_ID = "auction"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AuctionWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** 새 경매를 찾아 알림. 반환값: 알림 대상 (앱 화면에서 받아 왔을 때도 같이 씀) */
        fun check(ctx: Context, data: MyData): List<Pair<MyAuction, Boolean>> {
            val prefs = Prefs(ctx)
            val fresh = My.freshAuctions(data, prefs.auctionSeen)
            // 지나간 물건의 표시는 버리고 지금 있는 것만 기억 (목록에서 빠진 물건도 30일은 자료에 남아 있음)
            prefs.auctionSeen = data.auctions.map { it.stamp }.toSet()
            if (fresh.isEmpty() || !HotPostWorker.canNotify(ctx)) return fresh
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, ctx.getString(R.string.channel_auction), NotificationManager.IMPORTANCE_HIGH))
            fresh.take(5).forEach { (a, isNew) ->
                val open = PendingIntent.getActivity(
                    ctx, a.id.hashCode(),
                    Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUCTION, a.id)
                        .setData(Uri.parse("renf://auction/${a.id}")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val body = My.alertBody(a)
                val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notify).setColor(0xFF2563EB.toInt())
                    .setContentTitle(My.alertTitle(a, isNew)).setContentText(body.lineSequence().first())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(open).setAutoCancel(true).build()
                try { NotificationManagerCompat.from(ctx).notify(a.id.hashCode(), n) } catch (_: SecurityException) {}
            }
            return fresh
        }
    }
}
