package com.realestatenewsfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {
    private val json = """{"version":1,"updatedAt":1700000000000,"topics":["정책","청약·분양"],
      "sources":[{"id":"gnews","name":"구글뉴스","board":"부동산 종합","color":"#4285f4","kind":"news","status":{"ok":true,"count":2}},
                 {"id":"molit","name":"국토교통부","board":"보도자료","color":"#1f6f43","kind":"gov","status":{"ok":false,"count":0,"error":"HTTP 403"}},
                 {"id":"dcinside","name":"디시 부동산갤","board":"개념글","color":"#3b4890","kind":"community","status":{"ok":true,"count":1}}],
      "posts":[{"id":"gnews:1","source":"gnews","title":"과천 무순위 청약에 10만명","url":"https://news.google.com/rss/articles/A","views":null,"comments":null,"likes":null,
                "score":88,"pick":74,"viewsPerMin":0,"firstSeenAt":1700000000000,"rank":1,"publishedAt":1699990000000,"tags":["청약·분양"],"related":3,"outlet":"한국경제"},
               {"id":"dcinside:1","source":"dcinside","title":"전세 재계약 후기","url":"https://gall.dcinside.com/board/view/?id=immovables&no=5","views":1200,"comments":8,"likes":30,
                "score":70,"pick":40,"viewsPerMin":3,"firstSeenAt":1700000000000,"rank":1}]}"""

    @Test
    fun parsesCrawlerFeed() {
        val feed = Feed.parse(json)
        assertEquals(listOf("정책", "청약·분양"), feed.topics)
        assertEquals(3, feed.sources.size)
        assertEquals("gov", feed.sourceById["molit"]!!.kind)
        assertFalse(feed.sourceById["molit"]!!.ok)
        assertEquals("HTTP 403", feed.sourceById["molit"]!!.error)
        val p = feed.posts[0]
        assertEquals("한국경제", p.outlet)
        assertEquals(listOf("청약·분양"), p.tags)
        assertEquals(3, p.related)
        assertEquals(74, p.pick)
        assertEquals(1699990000000, p.time)
        assertNull(p.views)
        // 발행 시각이 없으면 처음 수집한 시각
        assertEquals(1700000000000, feed.posts[1].time)
        assertEquals(1, feed.only("community").posts.size)
        assertEquals(listOf("gnews"), feed.only("news").sources.map { it.id })
    }

    @Test
    fun postJsonRoundTrip() {
        val feed = Feed.parse(json)
        feed.posts.forEach { assertEquals(it, Post.fromJson(it.toJson())) }
    }

    @Test
    fun alertRule() {
        val p = Feed.parse(json).posts[0]
        assertTrue(matchesAlert(p, 80, emptyList(), emptySet()))
        assertFalse(matchesAlert(p, 95, emptyList(), emptySet()))
        assertTrue(matchesAlert(p, 95, listOf("청약"), emptySet()))
        assertFalse(matchesAlert(p, 80, listOf("청약"), setOf("gnews")))
    }

    @Test
    fun articleLinkPrefersResolvedUrlAndDropsTracking() {
        val g = "https://news.google.com/rss/articles/CBMiAAA"
        assertEquals("https://biz.newdaily.co.kr/site/data/html/2026/11/15/1.html", articleLink("https://biz.newdaily.co.kr/site/data/html/2026/11/15/1.html?utm_source=google&fbclid=x#top", g))
        assertEquals("https://www.mk.co.kr/news/view?idx=7&page=2", articleLink("https://www.mk.co.kr/news/view?idx=7&utm_medium=rss&page=2", g))
        assertEquals(g, articleLink("https://news.google.com/rss/articles/CBMiAAA?oc=5", g))
        assertEquals(g, articleLink(null, g))
    }

    @Test
    fun mobileUrls() {
        fun post(source: String, url: String) = Post("x", source, "t", url)
        assertEquals("https://m.dcinside.com/board/immovables/5", mobileUrl(post("dcinside", "https://gall.dcinside.com/board/view/?id=immovables&no=5")))
        assertEquals("https://m.ppomppu.co.kr/new/bbs_view.php?id=house&no=77", mobileUrl(post("ppomppu", "https://www.ppomppu.co.kr/zboard/view.php?id=house&no=77")))
        assertEquals("https://m.clien.net/service/board/cm_house/1", mobileUrl(post("clien", "https://www.clien.net/service/board/cm_house/1")))
        assertEquals("https://m.ppomppu.co.kr/new/bbs_view.php?id=house&no=260240", mobileUrl(post("ppomppu", "http://www.ppomppu.co.kr/zboard/view.php?id=house&no=260240")))
        assertEquals("https://m.cafe.naver.com/jaegebal/6534272", mobileUrl(post("navercafe", "https://cafe.naver.com/jaegebal/6534272")))
        assertEquals("https://www.hankyung.com/a/1", mobileUrl(post("hankyung", "https://www.hankyung.com/a/1")))
    }

    @Test
    fun threadsScreensAreRecognizedFromVisibleText() {
        assertEquals(ThreadsAuto.Screen.LOGIN, ThreadsAuto.screenOf(listOf("로그인 또는 가입하기", "developingrider", "buals_zip", "부동산 알려주는 스레드", "다른 프로필 사용하기")))
        assertEquals(ThreadsAuto.Screen.LOGOUT_DIALOG, ThreadsAuto.screenOf(listOf("설정", "알림", "계정에서 로그아웃하시겠어요?", "로그아웃", "취소")))
        assertEquals(ThreadsAuto.Screen.PROFILE, ThreadsAuto.screenOf(listOf("웃긴글 저장소", "utgingeul_archive", "프로필 편집", "인사이트 보기")))
        assertEquals(ThreadsAuto.Screen.SETTINGS, ThreadsAuto.screenOf(listOf("설정", "Meta 계정", "알림", "개인정보 보호", "로그아웃")))
        assertEquals(ThreadsAuto.Screen.OTHER, ThreadsAuto.screenOf(listOf("utgingeul_archive", "새로운 소식을 전하세요.", "lawyer__hjr")))
        assertEquals(ThreadsAuto.Screen.COMPOSE, ThreadsAuto.screenOf(listOf("답글 달기", "ps4_justdoit", "buals_zip", "답글 또 달기", "게시"), hasEditable = true))
        // 홈 화면: 버튼 이름에 '새 스레드', '답글 달기'가 있어도 입력칸이 없으면 글쓰기 화면이 아님
        assertEquals(ThreadsAuto.Screen.OTHER, ThreadsAuto.screenOf(listOf("utgingeul_archive", "새로운 소식을 전하세요.", "새 스레드", "답글 달기", "프로필")))
        assertTrue(ThreadsAuto.isAccount("buals_zip", "buals_zip") && ThreadsAuto.isAccount("@Buals_zip ", "buals_zip"))
        assertFalse(ThreadsAuto.isAccount("buals_zip_fan", "buals_zip"))
    }

    @Test
    fun uploadLogKeepsNewestAndSurvivesBadData() {
        val json = UploadLog.toJson(mapOf("a" to 100L, "b" to 300L, "c" to 200L))
        assertEquals(mapOf("b" to 300L, "c" to 200L, "a" to 100L), UploadLog.parse(json))
        assertEquals(listOf("b", "c", "a"), UploadLog.parse(json).keys.toList()) // 최근에 보낸 것부터
        assertTrue(UploadLog.parse(null).isEmpty())
        assertTrue(UploadLog.parse("깨진 값").isEmpty())
        // 너무 많이 쌓이면 오래된 것부터 버림
        val many = (1..1600).associate { "p$it" to it.toLong() }
        val kept = UploadLog.parse(UploadLog.toJson(many))
        assertEquals(1500, kept.size)
        assertTrue("p1600" in kept && "p100" !in kept)
    }
}
