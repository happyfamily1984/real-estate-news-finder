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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        val meta = CardMaker.Meta("금리·대출", "한국경제", 1794700000000, Watermark.Mark("부동산 알려주는 스레드", "@buals_zip"))
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
            StatCards.render(app, s, stats.sido, Watermark.Mark("부동산 알려주는 스레드", "@buals_zip")).forEachIndexed { j, b ->
                File("build/screens/stat-${i + 1}-${j + 1}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertEquals(2, StatCards.render(app, stats.series[0], stats.sido).size)
        assertEquals("아파트 매매가격" to "주간 변동률", StatCards.titleLines(stats.series[0]))
        compose.setContent { AppTheme(dark = false) { StatsScreen(stats, null, false, {}, {}) } }
        compose.onRoot().captureRoboImage("build/screens/stats.png")
    }

    // ----- 지역별 통계 · 실거래 -----
    private val mark = Watermark.Mark("부동산 알려주는 스레드", "@buals_zip")
    private fun save(name: String, b: Bitmap) { File("build/screens").mkdirs(); File("build/screens/$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    private fun regionStats(): Stats {
        val gu = listOf("강남구", "서초구", "송파구", "강동구", "마포구", "용산구", "성동구", "노원구", "도봉구", "강북구", "은평구", "양천구")
        fun vals(seed: Int, base: Double) = (0 until 12).map { k -> Math.round((base + Math.sin((k + seed) * 0.9) * 0.08) * 100) / 100.0 }
        fun reg(name: String, v: List<Double>) = """{"name":"$name","values":$v}"""
        val regions = listOf(reg("전국", vals(0, 0.05)), reg("서울", vals(1, 0.1)), reg("수도권", vals(2, 0.08)), reg("서울 동남권", vals(3, 0.16))) +
            gu.mapIndexed { i, g -> reg("서울 $g", vals(i + 4, 0.22 - i * 0.03)) }
        val dates = (0 until 12).map { "\"2026-${if (it < 5) "09" else if (it < 9) "10" else "11"}-${"%02d".format(1 + (it * 7) % 28)}\"" }
        val parents = (gu.map { "\"서울 $it\":[\"서울 동남권\",\"서울 강남\",\"서울\"]" } + "\"서울 동남권\":[\"서울 강남\",\"서울\"]").joinToString(",")
        return Stats.parse("""{"version":2,"updatedAt":1794700000000,"sido":["서울","경기"],"parents":{$parents},"series":[
          {"id":"reb-sale-wk","title":"아파트 매매가격 주간 변동률","short":"매매가","unit":"%","digits":2,"cycle":"주간","kind":"change","desc":"지난주 대비 아파트 매매가격지수 변동률","source":"한국부동산원","note":"한국부동산원 R-ONE 주간아파트가격동향","date":"2026-11-09","dates":$dates,"regions":$regions},
          {"id":"reb-buy-supply-wk","title":"매매수급지수","short":"매매수급","unit":"","digits":1,"cycle":"주간","kind":"level","base":100,"desc":"100보다 크면 사려는 사람이 많음","source":"한국부동산원","date":"2026-11-09","dates":$dates,
           "regions":[{"name":"전국","values":[99,99.1,99.2,99.3,99.4,99.5,99.6,99.7,99.8,99.6,99.6,99.5]},{"name":"서울","values":[105,105.5,106,106.5,107,107.5,108,108.5,109,109.3,109.4,109.2]},{"name":"서울 동남권","values":[108,108.5,109,109.5,110,110.5,111,111.5,112,112.3,112.4,112.5]}]}]}""")
    }

    @Test
    fun regionCardsAndPicker() {
        val st = regionStats()
        val cards = StatCards.renderRegion(app, st, "서울 강남구", st.series, mark)
        assertEquals(3, cards.size) // 매매가 추이 + 서울 시군구 순위 + 매매수급(동남권) 추이
        cards.forEachIndexed { i, b -> save("region-${i + 1}", b) }
        compose.setContent { AppTheme(dark = false) { StatsScreen(st, null, false, {}, {}, mode = 1) } }
        compose.onRoot().captureRoboImage("build/screens/stats-region.png")
    }

    /** 가짜 국토부 서버: 한 단지(전용 59·84㎡)의 2년치 매매와 전월세 */
    private fun fakeMolit() = HttpGet { url, _ ->
        val ym = Regex("DEAL_YMD=(\\d+)").find(url)!!.groupValues[1]
        val y = ym.take(4).toInt(); val m = ym.drop(4).toInt()
        val k = (y - 2024) * 12 + m
        val items = StringBuilder()
        if (url.contains("/molit/trade")) {
            for ((n, area) in listOf(84.93, 59.97, 84.99).withIndex()) {
                if ((k + n) % 3 == 0 && n == 2) continue
                val price = (((if (area > 80) 150000 else 118000) + k * 1100 + n * 900) / 100 * 100).coerceAtLeast(30000)
                items.append("<item><aptNm>래미안슈르</aptNm><umdNm>원문동</umdNm><jibun>4</jibun><buildYear>2008</buildYear><dealYear>$y</dealYear><dealMonth>$m</dealMonth><dealDay>${3 + n * 9}</dealDay><dealAmount>$price</dealAmount><excluUseAr>$area</excluUseAr><floor>${5 + (k + n) % 14}</floor><dealingGbn>중개거래</dealingGbn><cdealType> </cdealType></item>")
            }
            items.append("<item><aptNm>과천자이</aptNm><umdNm>별양동</umdNm><jibun>52</jibun><buildYear>2021</buildYear><dealYear>$y</dealYear><dealMonth>$m</dealMonth><dealDay>12</dealDay><dealAmount>${205000 + k * 500}</dealAmount><excluUseAr>84.9</excluUseAr><floor>9</floor><cdealType> </cdealType></item>")
        } else {
            items.append("<item><aptNm>래미안슈르</aptNm><umdNm>원문동</umdNm><jibun>4</jibun><buildYear>2008</buildYear><dealYear>$y</dealYear><dealMonth>$m</dealMonth><dealDay>8</dealDay><deposit>${82000 + k * 300}</deposit><monthlyRent>0</monthlyRent><excluUseAr>84.93</excluUseAr><floor>7</floor><contractType>신규</contractType></item>")
            items.append("<item><aptNm>래미안슈르</aptNm><umdNm>원문동</umdNm><jibun>4</jibun><buildYear>2008</buildYear><dealYear>$y</dealYear><dealMonth>$m</dealMonth><dealDay>21</dealDay><deposit>30000</deposit><monthlyRent>${180 + k}</monthlyRent><excluUseAr>59.97</excluUseAr><floor>11</floor><contractType>갱신</contractType></item>")
        }
        200 to "<response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header><body><items>$items</items><totalCount>4</totalCount></body></response>"
    }

    @Test
    fun dealCardsAndScreens() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = java.time.ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, KST).toInstant().toEpochMilli()
        val core = DealCore(app, fakeMolit()) { now }
        DealCore.replace(core)
        try {
            val complexes = core.service.getComplexes("41290")
            assertEquals(listOf("과천자이", "래미안슈르"), complexes.map { it.getString("aptNm") })
            val id = complexes[1].getString("id")
            val today = core.molit.today()
            val year = core.service.getDeals("41290", id, "trade", DealPeriod.Y1.fetchMonths).filter { it.date >= DealPeriod.Y1.since(today) }
            assertTrue(year.size > 20)

            // 기간 요약 카드: 추이 + 내역
            val summary = DealCards.render(app, DealCards.Input("래미안슈르", "과천시 원문동", "trade", DealPeriod.Y1, year, today = today), mark)
            assertEquals(2, summary.size)
            summary.forEachIndexed { i, b -> save("deal-summary-${i + 1}", b) }
            val rent = core.service.getDeals("41290", id, "monthly", 13)
            DealCards.render(app, DealCards.Input("래미안슈르", "과천시 원문동", "monthly", DealPeriod.Y1, rent, today = today), mark).forEachIndexed { i, b -> save("deal-monthly-${i + 1}", b) }
            // 새 실거래 카드: 새 거래 + 추이 + 내역
            val newKeys = year.take(2).map { it.key }.toSet()
            val fresh = DealCards.render(app, DealCards.Input("래미안슈르", "과천시 원문동", "trade", DealPeriod.Y1, year, newKeys, today), mark)
            assertEquals(3, fresh.size)
            fresh.forEachIndexed { i, b -> save("deal-new-${i + 1}", b) }

            // 알림 받기 → 최근 한 달 실거래 알림, 알림함에 기록
            core.alerts.subscribe("41290", id, "trade")
            val note = core.store.notifications.objects().last()
            val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
            assertTrue(nm.allNotifications.isNotEmpty())
            assertEquals(setOf("trade"), core.subscribedTypes("41290", id))

            // 화면: 실거래 첫 화면 → 단지 목록 → 단지 상세(알림에서 들어와 빨간 N) → 내보내기
            var screen by androidx.compose.runtime.mutableStateOf(0)
            val target = DealTarget("41290", id, "trade", note.getString("id"))
            compose.setContent {
                AppTheme(dark = false) {
                    when (screen) {
                        0 -> StatsScreen(null, null, false, {}, {}, mode = 2)
                        1 -> ComplexListScreen("41290", {}) { _, _, _ -> }
                        2 -> ComplexDetailScreen(target, {}) { _, _, _, _, _ -> }
                        4 -> BudgetScreen({}, {})
                        5 -> HistoryScreen(target, "래미안슈르", "원문동", {}, {})
                        else -> DealShareScreen("41290", id, "래미안슈르", "원문동", "trade", DealPeriod.Y1, core.notificationDeals(note.getString("id")), {}, {})
                    }
                }
            }
            fun shot(name: String, wait: String) {
                compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasText(wait, substring = true)).fetchSemanticsNodes().isNotEmpty() }
                compose.onRoot().captureRoboImage("build/screens/$name.png")
            }
            // 실거래 활용 카드: 내 돈으로 살 수 있는 집, 과거 가격 비교, 분양가 vs 실거래
            val half = core.molit.fetchMonths("trade", "41290", 7)
            val budget = DealTools.budget(half, 84, 200000)
            DealCards.budget(app, "과천시", 84, 200000, DealPeriod.M6, budget, today, mark).forEachIndexed { i, b -> save("tool-budget-${i + 1}", b) }
            assertEquals(1, budget.items.size)
            val pts = (listOf(0) + DealTools.HISTORY_YEARS).map { n ->
                val yms = DealTools.historyYms(today, n)
                DealTools.historyPoint(today, n, if (yms.isEmpty()) null else core.molit.fetchYms("trade", "41290", yms), id, "래미안슈르", "원문동", 84, 2008)
            }
            assertEquals(listOf(true, true, true, false), pts.map { it.ok })
            DealCards.history(app, "래미안슈르", "과천시 원문동", 84, pts, today, mark, note = "10년 사이 짜장면 한 그릇 4,800원 → 7,600원 (+58%)").forEachIndexed { i, b -> save("tool-history-${i + 1}", b) }
            val items = DealTools.parsePresale("""{"items":[{"id":"1","name":"과천 자이","date":"2021-12-01","lawd":["41290"],"addr":"","prices":{"84":[150000,160000]}}]}""")
            val matches = DealTools.presaleCompare(items + DealTools.parsePresale("""{"items":[{"id":"2","name":"래미안슈르","date":"2022-05-01","lawd":["41290"],"addr":"","prices":{"84":[120000,130000]}}]}"""), "41290", year + core.service.getDeals("41290", complexes[0].getString("id"), "trade", 13), 84, today.year)
            assertEquals(listOf("과천자이"), matches.map { it.aptNm }) // 래미안슈르는 2008년 준공이라 10년 이내가 아님
            DealCards.presale(app, "과천시", 84, DealPeriod.Y1, matches, today, mark).forEachIndexed { i, b -> save("tool-presale-${i + 1}", b) }

            // 지역별 최고 실거래 단지 카드
            val five = core.molit.fetchMonths("trade", "41290", 13)
            val topItems = DealTools.topDeals(five, 0, 10).map { it.copy(gu = "과천시") } + (1..8).map { k -> DealTools.TopItem(five.first().copy(aptNm = "가상단지$k", price = 120000L - k * 7000, complexId = "x$k"), "과천시") }
            DealCards.top(app, "경기 전체", 0, DealPeriod.Y5, topItems, today, mark).forEachIndexed { i, b -> save("tool-top-${i + 1}", b) }

            shot("deal-home", "알림 받는 단지")
            screen = 1; shot("deal-complexes", "과천자이")
            screen = 2; shot("deal-detail", "최근 1년 매매 실거래")
            screen = 6; shot("deal-share", "최근 래미안슈르 실거래 발생")
            Prefs(app).dealRecent = listOf("41290")
            Prefs(app).dealArea = "41290"
            screen = 4; shot("tool-budget-screen", "살 수 있는 84㎡ 아파트")
            screen = 5; shot("tool-history-screen", "실거래가 (과천시 원문동)")
        } finally {
            DealCore.replace(null)
        }
    }
}
