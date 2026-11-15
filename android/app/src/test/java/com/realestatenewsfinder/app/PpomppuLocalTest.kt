package com.realestatenewsfinder.app

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpomppuLocalTest {
    private val html = """<table id="revolution_main_table"><tbody>
<tr class="baseList baseNotice"><td class="baseList-space title"><a class="baseList-title" href="view.php?id=house&no=1">공지</a></td><td class="baseList-space baseList-rec"></td><td class="baseList-space baseList-views">9</td></tr>
<tr class="baseList bbs_new1"><td class="baseList-space title"><div class="baseList-cover"><a class="baseList-title" href="view.php?id=house&page=1&divpage=20&no=12345"><span>전세 재계약 5% 인상 질문</span></a><span class="baseList-c">7</span></div></td><td class="baseList-space baseList-rec">3 - 0</td><td class="baseList-space baseList-views">1,204</td></tr>
<tr class="baseList bbs_new1"><td class="baseList-space title"><a class="baseList-title" href="view.php?id=house&no=12346"><img src="t.jpg"></a><a class="baseList-title" href="view.php?id=house&no=12346">분양권 양도세 얼마나 나올까요</a></td><td class="baseList-space baseList-rec"></td><td class="baseList-space baseList-views">88</td></tr>
</tbody></table>"""

    @Test
    fun parsesListWithoutNotice() {
        val posts = PpomppuLocal.parse(Jsoup.parse(html), 1000L)
        assertEquals(2, posts.size)
        val p = posts[0]
        assertEquals("ppomppu:12345", p.id)
        assertEquals("전세 재계약 5% 인상 질문", p.title)
        assertEquals("https://www.ppomppu.co.kr/zboard/view.php?id=house&no=12345", p.url)
        assertEquals(1204, p.views); assertEquals(7, p.comments); assertEquals(3, p.likes)
        assertEquals(listOf("전월세"), p.tags)
        assertTrue(p.score > posts[1].score)
        assertEquals("분양권 양도세 얼마나 나올까요", posts[1].title)
        assertEquals(0, posts[1].comments)
    }
}
