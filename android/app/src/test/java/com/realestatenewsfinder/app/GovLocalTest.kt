package com.realestatenewsfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GovLocalTest {
    private val now = 1794700000000L
    private fun date(msAgo: Long) = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).format(java.util.Date(now - msAgo))
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><title>금융위원회</title>
<item><title><![CDATA[스트레스 DSR 3단계 7월 1일부터 시행… 대출 한도 축소]]></title><link>https://www.korea.kr/briefing/pressReleaseView.do?newsId=156700001&amp;call_from=rsslink</link>
<description><![CDATA[<p>금융위원회는 7월 1일부터 스트레스 DSR 3단계를 시행한다고 밝혔다.&nbsp;</p>]]></description><pubDate>${date(3600_000)}</pubDate></item>
<item><title><![CDATA[핀테크 지원센터 개소식 개최]]></title><link>https://www.korea.kr/briefing/pressReleaseView.do?newsId=156700002</link><description>관련 없음</description><pubDate>${date(3600_000)}</pubDate></item>
<item><title><![CDATA[전세사기 피해 지원 대책]]></title><link>https://www.korea.kr/briefing/pressReleaseView.do?newsId=156600000</link><description>오래됨</description><pubDate>${date(100 * 3600_000L)}</pubDate></item>
</channel></rss>"""

    @Test
    fun parsesOnlyRecentRealEstateItems() {
        val posts = GovLocal.parse(xml, GovLocal.SOURCES.first { it.id == "fsc" }, now)
        assertEquals(1, posts.size)
        val p = posts[0]
        assertEquals("스트레스 DSR 3단계 7월 1일부터 시행… 대출 한도 축소", p.title)
        assertEquals("https://www.korea.kr/briefing/pressReleaseView.do?newsId=156700001", p.url)
        assertEquals("금융위원회는 7월 1일부터 스트레스 DSR 3단계를 시행한다고 밝혔다.", p.summary)
        assertEquals(now - 3600_000, p.publishedAt)
        assertTrue(p.tags.contains("금리·대출"))
        assertTrue("score ${p.score} pick ${p.pick}", p.score >= 70 && p.pick >= 70)
    }

    @Test
    fun molitKeepsEverything() {
        assertEquals(2, GovLocal.parse(xml, GovLocal.SOURCES.first { it.id == "molit" }, now).size)
    }

    @Test
    fun topicsMatchCrawlerRules() {
        assertEquals(listOf("청약·분양"), Topics.tagsOf("과천 무순위 청약에 10만명"))
        assertTrue(Topics.threadPick("청년 월세 지원 신청 대상·조건 총정리") >= 70)
        assertEquals(0, Topics.threadPick("건설협회 창립 기념 행사 개최"))
    }
}
