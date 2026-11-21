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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 알림·화면 사이에서 주고받는 단지 위치 */
data class DealTarget(val lawdCd: String, val complexId: String, val type: String = "trade", val notificationId: String? = null) {
    fun toUri(): String = "renf://deal/$lawdCd/$complexId?type=$type" + (notificationId?.let { "&n=$it" } ?: "")

    companion object {
        /** 알림에 실어 보내는 "#/complex/시군구/단지?type=…&n=…" 또는 renf://deal/… 를 해석 */
        fun parse(hash: String?): DealTarget? {
            val m = Regex("(?:#/complex|renf://deal)/(\\d{5})/([0-9a-f]+)(?:\\?(.*))?").find(hash ?: return null) ?: return null
            val q = m.groupValues[3].split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
            return DealTarget(m.groupValues[1], m.groupValues[2], q["type"]?.takeIf { it in Molit.TYPES } ?: "trade", q["n"])
        }
    }
}

/** 실거래 기능 전체 (저장소·국토부 조회·알림). 앱에서 하나만 만들어 씀 */
class DealCore(ctx: Context, http: HttpGet = defaultHttp, clock: () -> Long = System::currentTimeMillis) {
    private val app = ctx.applicationContext
    private val prefs = Prefs(app)
    val store = DealStore(File(app.filesDir, "deals.json"))
    val regions = DealRegions(app.assets.open("regions.json").bufferedReader().use { it.readText() })
    val molit = MolitClient({ prefs.molitKey }, { prefs.dealProxyUrl }, "", http, app.cacheDir, clock)
    init { My.cached(app); Thread { molit.cleanCache() }.start() } // 단지 표기 이름 불러오기, 기한 지난 임시 저장 자료 정리
    val service = DealService(molit)
    val alerts = DealAlerts(store, molit, service, AndroidDealNotifier(app), clock)

    /** 단지별로 묶은 구독: (시군구, 단지) → 구독 목록 */
    fun subscriptionsByComplex(): List<List<JSONObject>> =
        store.subscriptions.objects().groupBy { it.getString("lawdCd") + ":" + it.getString("complexId") }.values.toList()

    fun subscribedTypes(lawdCd: String, complexId: String): Set<String> =
        store.subscriptions.objects().filter { it.getString("lawdCd") == lawdCd && it.getString("complexId") == complexId }.map { it.getString("type") }.toSet()

    /** 알림에 담긴 거래 (알림을 눌러 들어왔을 때 빨간 N으로 표시) */
    fun notificationDeals(id: String?): List<Deal> {
        val n = store.notifications.objects().firstOrNull { it.optString("id") == id } ?: return emptyList()
        val arr = n.optJSONArray("deals") ?: return emptyList()
        return (0 until arr.length()).map { Deal.fromJson(arr.getJSONObject(it)) }
    }

    /** 이 단지의 최근 7일 '새 실거래' 알림에 담긴 거래 키 */
    fun recentNewKeys(lawdCd: String, complexId: String, now: Long = System.currentTimeMillis()): Set<String> =
        store.notifications.objects()
            .filter { it.optString("kind") == "new" && it.optString("lawdCd") == lawdCd && it.optString("complexId") == complexId }
            .filter { runCatching { now - java.time.Instant.parse(it.optString("createdAt")).toEpochMilli() < 7 * 86400_000L }.getOrDefault(false) }
            .flatMap { n -> n.optJSONArray("deals")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .filter { !it.optBoolean("canceled") }.map { it.optString("key") }.toSet()

    companion object {
        @Volatile private var inst: DealCore? = null
        /** 테스트용: 가짜 서버를 쓰는 인스턴스로 바꿈 */
        fun replace(core: DealCore?) { inst = core }
        fun get(ctx: Context): DealCore = inst ?: synchronized(this) { inst ?: DealCore(ctx).also { inst = it } }
    }
}

class AndroidDealNotifier(private val ctx: Context) : DealNotifier {
    override fun show(id: Int, title: String, body: String, hash: String): Boolean {
        if (!HotPostWorker.canNotify(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, ctx.getString(R.string.channel_deal), NotificationManager.IMPORTANCE_HIGH))
        val target = DealTarget.parse(hash)
        // 알림을 누르면 그 단지의 상세 화면으로 (새 거래는 빨간 N 표시)
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DEAL, hash)
            .setData(Uri.parse(target?.toUri() ?: "renf://deal/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF2563EB.toInt())
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(ctx).notify(id, n)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "deals"
    }
}

/** 백그라운드에서 주기적으로 구독 단지의 새 실거래 확인 (재부팅 후에도 유지) */
class DealPollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val core = DealCore.get(applicationContext)
        if (core.store.subscriptions.length() == 0) return@withContext Result.success()
        val r = runCatching { core.alerts.poll() }.getOrNull() ?: return@withContext Result.retry()
        // 전부 실패(네트워크 등)면 잠시 후 다시
        if (r.groups > 0 && r.errors.size == r.groups) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "deal-poll"

        /** 구독이 있으면 주기 확인을 걸고, 없으면 끔 */
        fun schedule(ctx: Context, replace: Boolean = true) {
            val wm = WorkManager.getInstance(ctx)
            if (DealCore.get(ctx).store.subscriptions.length() == 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<DealPollWorker>(Prefs(ctx).dealPollMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
