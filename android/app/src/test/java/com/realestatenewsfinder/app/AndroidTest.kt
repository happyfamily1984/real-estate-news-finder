package com.realestatenewsfinder.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AndroidTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun feed() = Feed.parse(javaClass.classLoader!!.getResource("demo-feed.json")!!.readText())

    @Test
    fun firstCheckPrimesThenNotifiesOnlyNewMatchingPosts() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val prefs = Prefs(app).apply { minScore = 85; keywordsText = "청약"; mutedSources = setOf("mk") }
        val full = feed()
        val old = full.copy(posts = full.posts.drop(10))

        assertEquals(emptyList<Post>(), HotPostWorker.checkAndNotify(app, old))
        assertEquals(0, nm.allNotifications.size)

        val sent = HotPostWorker.checkAndNotify(app, full)
        val newOnes = full.posts.take(10)
        val expected = newOnes.filter { it.source != "mk" && (it.score >= 85 || "청약" in it.title) }
        assertEquals(expected.map { it.id }.sorted(), sent.map { it.id }.sorted())
        assertTrue(nm.allNotifications.isNotEmpty())

        // 같은 피드를 다시 확인하면 중복 알림 없음
        assertEquals(emptyList<Post>(), HotPostWorker.checkAndNotify(app, full))
        assertTrue(prefs.seenIds!!.containsAll(full.posts.map { it.id }))
    }

    @Test
    fun feedScreenLight() {
        compose.setContent { AppTheme(dark = false) { HomeScreen(feed().only("news"), null, false, emptySet(), {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/screens/feed-light.png")
    }

    @Test
    fun feedScreenDark() {
        compose.setContent { AppTheme(dark = true) { HomeScreen(feed().only("community"), null, false, emptySet(), {}, {}, {}, {}, community = true) } }
        compose.onRoot().captureRoboImage("build/screens/feed-dark.png")
    }

    @Test
    fun settingsScreen() {
        compose.setContent { AppTheme(dark = false) { SettingsScreen(feed()) } }
        compose.onRoot().captureRoboImage("build/screens/settings.png")
    }

    @Test
    fun cardImages() {
        val meta = CardMaker.Meta("금리·대출", "한국경제", 1794700000000, "@my_threads")
        val summary = Summary(
            "", "7월부터 수도권 주담대 한도 최대 5000만원 축소",
            listOf("시행일: 7월 1일부터", "대상: 수도권 주택담보대출 신규 신청", "가산금리: 1.5%p (지방은 0.75%p 유지)", "한도: 연소득 1억원 기준 약 5000만원 감소", "예외: 6월 30일까지 계약·신청 완료분", "주의: 잔금대출도 같은 규정 적용"),
        )
        val cards = CardMaker.render("스트레스 DSR 3단계 7월 시행… 수도권 주담대 한도 얼마나 줄어드나", summary, meta)
        assertEquals(2, cards.size) // 표지 + 핵심 정리 한 장(6줄까지)
        File("build/screens").mkdirs()
        cards.forEachIndexed { i, b ->
            assertEquals(CardMaker.W, b.width); assertEquals(CardMaker.H, b.height)
            File("build/screens/card-${i + 1}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // 요약이 없으면 표지 한 장
        assertEquals(1, CardMaker.render("제목만 있는 글", null, meta).size)
    }

    @Test
    fun statCardsAndScreen() {
        val stats = Stats.parse(javaClass.classLoader!!.getResource("demo-stats.json")!!.readText())
        File("build/screens").mkdirs()
        stats.series.take(3).forEachIndexed { i, s ->
            StatCards.render(s, stats.sido, "@my_threads").forEachIndexed { j, b ->
                File("build/screens/stat-${i + 1}-${j + 1}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertEquals(2, StatCards.render(stats.series[0], stats.sido).size)
        compose.setContent { AppTheme(dark = false) { StatsScreen(stats, null, false, {}, {}) } }
        compose.onRoot().captureRoboImage("build/screens/stats.png")
    }
}
