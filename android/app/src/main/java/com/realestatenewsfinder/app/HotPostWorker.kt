package com.realestatenewsfinder.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 백그라운드에서 주기적으로 클라우드 피드를 확인해 조건에 맞는 새 부동산 소식을 알림으로 띄웁니다. */
class HotPostWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.notifyEnabled) return Result.success()
        val feed = try {
            FeedRepository.fetch(applicationContext)
        } catch (e: Exception) {
            return Result.retry()
        }
        checkAndNotify(applicationContext, feed)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "hot-post-check"
        const val CHANNEL_ID = "hot"
        private const val MAX_SEEN = 3000
        private const val MAX_INDIVIDUAL = 4

        fun schedule(ctx: Context) {
            val prefs = Prefs(ctx)
            val wm = WorkManager.getInstance(ctx)
            if (!prefs.notifyEnabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<HotPostWorker>(prefs.intervalMin.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** 새 글을 찾아 알림. 반환값: 알림 보낸 글 */
        fun checkAndNotify(ctx: Context, feed: Feed): List<Post> {
            val prefs = Prefs(ctx)
            val seen = prefs.seenIds
            val ids = feed.posts.map { it.id }
            if (seen == null) {
                // 처음엔 지금 있는 글을 기준점으로만 잡고 알림은 보내지 않음
                prefs.seenIds = ids.toSet()
                return emptyList()
            }
            val fresh = feed.posts.filter { it.id !in seen }
            val matched = fresh
                .filter { matchesAlert(it, prefs.minScore, prefs.keywords, prefs.mutedSources) }
                .sortedByDescending { it.score }
            prefs.seenIds = (ids + seen).take(MAX_SEEN).toSet()
            notify(ctx, feed, matched)
            return matched
        }

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, ctx.getString(R.string.channel_hot), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        fun canNotify(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun notify(ctx: Context, feed: Feed, posts: List<Post>) {
            if (posts.isEmpty() || !canNotify(ctx)) return
            ensureChannel(ctx)
            val nm = NotificationManagerCompat.from(ctx)
            val group = "hot-posts"

            posts.take(MAX_INDIVIDUAL).forEach { p ->
                val src = p.outlet ?: feed.sourceById[p.source]?.name ?: p.source
                val open = PendingIntent.getActivity(
                    ctx, p.id.hashCode(),
                    // 알림을 누르면 원본 사이트가 아니라 이 앱의 글 미리보기 화면을 엶
                    Intent(ctx, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_POST, PostStore.toJson(p).toString())
                        .setData(Uri.parse("renf://post/${p.id.hashCode()}"))
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setColor(0xFF2563EB.toInt())
                    .setContentTitle("🏠 $src · 중요도 ${p.score}")
                    .setContentText(p.title)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(p.title))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setGroup(group)
                    .build()
                try {
                    nm.notify(p.id.hashCode(), n)
                } catch (_: SecurityException) {
                }
            }

            if (posts.size > 1) {
                val openApp = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
                val summary = NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setColor(0xFF2563EB.toInt())
                    .setContentTitle("부동산 새 소식 ${posts.size}개")
                    .setStyle(
                        NotificationCompat.InboxStyle().also { s -> posts.take(6).forEach { s.addLine(it.title) } },
                    )
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .setGroup(group)
                    .setGroupSummary(true)
                    .build()
                try {
                    nm.notify(1, summary)
                } catch (_: SecurityException) {
                }
            }
        }
    }
}
