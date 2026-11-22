package com.realestatenewsfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerTest {
    private val raw = """
        [스레드]
        7월부터 수도권 주담대 한도가 줄어요

        스트레스 DSR 3단계가 7월 1일 시행돼요.

        - 수도권 주담대에 가산금리 1.5%p가 적용돼요.

        **6월 30일까지** 계약한 건은 기존 규정이 적용돼요.

        [카드]
        제목: 7월부터 주담대 한도 축소
        - 시행일: 7월 1일
        - 대상: 수도권 주택담보대출
        * 가산금리: 1.5%p
        1. 예외: 6월 30일까지 계약분
    """.trimIndent()

    @Test
    fun parsesThreadAndCard() {
        val s = Summarizer.parse(raw, "원래 제목")!!
        assertTrue(s.thread.startsWith("7월부터 수도권 주담대 한도가 줄어요\n\n스트레스 DSR"))
        assertTrue(s.thread.contains("수도권 주담대에 가산금리 1.5%p가 적용돼요."))
        assertTrue(!s.thread.contains("[") && !s.thread.contains("*") && !s.thread.contains("- "))
        assertEquals("7월부터 주담대 한도 축소", s.headline)
        // 카드 구역에 '제목:'이 없으면 기사 제목을 그대로 씀
        assertEquals("원래 제목", Summarizer.parse(raw.replace("제목: 7월부터 주담대 한도 축소\n", ""), "원래 제목")!!.headline)
        assertEquals(listOf("시행일: 7월 1일", "대상: 수도권 주택담보대출", "가산금리: 1.5%p", "예외: 6월 30일까지 계약분"), s.points)
    }

    @Test
    fun fallsBackWhenCardSectionMissing() {
        val s = Summarizer.parse("첫 줄 요약\n\n둘째 줄\n\n셋째 줄", "원래 제목")!!
        assertEquals("원래 제목", s.headline)
        assertEquals(listOf("둘째 줄", "셋째 줄"), s.points)
        assertNull(Summarizer.parse("  ", "원래 제목"))
    }

    @Test
    fun longThreadIsCutAtLineEnd() {
        val long = (1..40).joinToString("\n\n") { "이것은 ${it}번째 문장이에요. 길이를 채우는 내용." }
        val s = Summarizer.parse(long, "t")!!
        assertTrue(s.thread.length <= Summarizer.MAX_THREAD_CHARS)
        assertTrue(s.thread.endsWith("내용."))
    }

    @Test
    fun promptKeepsFactsAndThreadsTone() {
        val p = Summarizer.system("news", listOf("금리·대출"), casual = true, today = "2026년 11월 15일")
        assertTrue(p.contains("본문에 있는 것만"))
        assertTrue(p.contains("주제를 바꾸지 않는다"))
        assertTrue(p.contains("본문에 없는 연도·날짜를 쓰지 않는다"))
        assertTrue(p.contains("권유"))
        assertTrue(p.contains("오늘은 2026년 11월 15일"))
        assertTrue(p.contains("금리나 한도가 얼마나 바뀌는지"))
        assertTrue(p.contains("~했대"))
        // 맨 위는 한마디, 그 아래는 핵심만 2~3줄
        assertTrue(p.contains("촌철살인 한마디") && p.contains("이어서 2~3줄(요약)") && p.contains("전체 220자 이내"))
        // 화살표는 '쓰지 않는 것' 안내에만 나옴 (형식으로 오해하지 않게)
        assertEquals(1, Regex("→").findAll(p).count())
        assertTrue(Summarizer.system("community", emptyList(), casual = false).contains("개인의 경험·의견"))
    }

    @Test
    fun findsAndDropsInventedNumbers() {
        val source = "제목: 서울 1순위 청약자, 4년 만에 최저\n본문: 일반공급은 21% 늘었지만 1순위 청약신청자는 48% 감소했다. 강남3구 일반분양은 73가구로 전년 대비 88% 급감했다. 경쟁률은 870대 1이다."
        val s = Summary(
            "강남3구 일반분양 73가구, 경쟁률 870대1\n\n강남3구 일반분양 73가구 2024년 현재\n\n오티에르 반포 43가구\n\n신청자는 48% 줄었음",
            "강남3구 분양 73가구", listOf("일반분양: 73가구", "기준: 2024년", "경쟁률: 870대 1"),
        )
        val bad = Summarizer.unsupportedNumbers(s.thread + s.points.joinToString("\n"), source)
        assertEquals(listOf("2024", "43"), bad)
        val cleaned = Summarizer.dropLinesWith(s, bad)
        assertEquals("강남3구 일반분양 73가구, 경쟁률 870대1\n\n신청자는 48% 줄었음", cleaned.thread)
        assertEquals(listOf("일반분양: 73가구", "경쟁률: 870대 1"), cleaned.points)
        assertEquals(emptyList<String>(), Summarizer.unsupportedNumbers("일반공급 21% 증가, 1순위는 48% 감소", source))
    }

    @Test
    fun basicSummaryWithoutAi() {
        val s = Summarizer.basic(Post("a", "mk", "제목이에요", "u", summary = "국토부는 15일 공급 대책을 발표했다. 수도권에 3만 가구를 공급한다."))
        assertEquals("제목이에요", s.headline)
        assertEquals(2, s.points.size)
    }

    @Test
    fun noticeUsesCrawledDetails() {
        val p = Post("a", "applyhome", "[청약] 호반써밋 초량 (부산·민영) 11/23~11/25 접수", "u",
            details = listOf("공급위치: 부산광역시 동구 초량3동", "공급규모: 236세대 (일반 99 · 특별 137)", "59㎡ 분양가: 5억4,820만~5억6,320만원", "청약일정: 특별공급 11/23 · 1순위 11/24 · 2순위 11/25"))
        val s = Summarizer.basic(p)
        assertEquals(p.details, s.points)
        assertTrue(s.thread.contains("59㎡ 분양가: 5억4,820만~5억6,320만원"))
        val input = Summarizer.buildInput(p, "청약홈", "본문 글자")
        assertTrue(input.contains("공고 주요 정보:") && input.contains("공급규모: 236세대"))
        // 상세 정보의 숫자는 원문에 있는 숫자로 인정됨
        assertEquals(emptyList<String>(), Summarizer.unsupportedNumbers("59㎡ 분양가가 5억4,820만원부터래", input))
        assertEquals(p, Post.fromJson(p.toJson()))
    }

    @Test
    fun attachmentTextGoesIntoInput() {
        val post = Post(id = "molit:1", source = "molit", title = "(설명) 3기 신도시 교통대책", url = "https://www.molit.go.kr/x",
            attach = "□ 3기 신도시 광역교통 개선대책 28개 사업 중 19개 착공\n□ 2027년까지 나머지 9개 착공", attachName = "261116(설명).hwpx")
        val input = Summarizer.buildInput(post, "국토교통부", "자세한 내용은 첨부파일을 참고하시기 바랍니다.")
        assertTrue(input, input.contains("본문:\n자세한 내용은 첨부파일을 참고하시기 바랍니다."))
        assertTrue(input, input.contains("첨부파일 내용(261116(설명).hwpx)"))
        assertTrue(input, input.contains("28개 사업 중 19개 착공"))
        // 첨부에만 있는 숫자도 '본문에 있는 숫자'로 인정됨
        assertTrue(Summarizer.unsupportedNumbers("28개 사업 중 19개 착공", input).isEmpty())
        // 첨부가 없으면 예전과 같음
        assertTrue(!Summarizer.buildInput(post.copy(attach = null), "국토교통부", "본문").contains("첨부파일 내용"))
        // 저장했다가 다시 읽어도 유지
        assertEquals(post.attach, PostStore.fromJson(PostStore.toJson(post)).attach)
    }

    // ----- (실험실) 글 형식 -----
    @Test
    fun labFormats() {
        assertEquals(listOf("basic", "qa", "debate", "checklist"), Summarizer.LAB_FORMATS.keys.toList())
        val basic = Summarizer.system("news", emptyList(), casual = true)
        assertEquals(basic, Summarizer.system("news", emptyList(), casual = true, format = "없는형식"))
        val qa = Summarizer.system("gov", emptyList(), casual = true, format = "qa")
        assertTrue(qa.contains("문답형") && qa.contains("Q. ") && qa.contains("하나씩 답해줄게.") && !qa.contains("촌철살인"))
        assertTrue(Summarizer.system("community", emptyList(), casual = false, format = "debate").contains("어느 쪽이 옳다고 판단하지 않는다"))
        assertTrue(Summarizer.system("news", emptyList(), casual = true, format = "checklist").contains("✅"))
        // 번호·글머리 기호를 지우지 않음
        val raw = "[스레드]\n요즘 전세 사기 수법 2가지\n\n1. 깡통전세: 보증금이 집값보다 큼\n2. 이중계약: 같은 집을 두 번 계약\n\n- 주의: 등기부 확인\n[카드]\n- 수법: 깡통전세"
        assertTrue(Summarizer.parse(raw, "t", keepMarks = true)!!.thread.contains("1. 깡통전세") )
        assertTrue(!Summarizer.parse(raw, "t")!!.thread.contains("1. 깡통전세"))
        // 번호로 쓴 한 자리 숫자는 지어낸 숫자로 보지 않음
        assertEquals(emptyList<String>(), Summarizer.unsupportedNumbers("수법 2가지\n1. 깡통전세", "깡통전세와 이중계약", lenient = true))
        assertEquals(listOf("30"), Summarizer.unsupportedNumbers("1. 30% 올랐대", "올랐다", lenient = true))
    }

    // ----- 블로그 글 -----
    @Test
    fun blogFormat() {
        val sys = Summarizer.blogSystem("news", listOf("금리·대출"), "2026년 11월 20일")
        assertTrue(sys.contains("네이버 블로그") && sys.contains("■ ") && sys.contains("한눈에 정리") && sys.contains("[태그]") && sys.contains("금리나 한도"))
        assertTrue(Summarizer.blogSystem("community", emptyList()).contains("개인의 경험·의견"))
        val raw = "[제목]\n**디딤돌대출 한도 축소, 12월부터 달라지는 점**\n[본문]\n디딤돌대출 한도가 줄어듭니다.\n\n## 누가 대상일까?\n무주택자가 대상입니다.\n\n■ 한눈에 정리\n- 12월 시행\n[태그]\n디딤돌대출, 대출 한도, #무주택자\n[카드]\n- 시행: 12월"
        val b = Summarizer.parseBlog(raw, "원래 제목")!!
        assertEquals("디딤돌대출 한도 축소, 12월부터 달라지는 점", b.title)
        assertTrue(b.body.startsWith("디딤돌대출 한도가 줄어듭니다.") && b.body.contains("■ 누가 대상일까?") && b.body.contains("- 12월 시행") && !b.body.contains("[태그]"))
        assertEquals(listOf("디딤돌대출", "대출한도", "무주택자"), b.tags)
        assertEquals(listOf("시행: 12월"), b.points)
        assertEquals("원래 제목", Summarizer.parseBlog("그냥 본문만 왔을 때", "원래 제목")!!.title)
        assertNull(Summarizer.parseBlog("[제목]\n제목만", "t"))
    }

    @Test
    fun blogTextFromData() {
        val day = java.time.LocalDate.of(2026, 11, 20)
        assertEquals("래미안슈르 자금 계산 (2026년 11월 기준)", BlogText.title("래미안슈르 · 자금 계산", day))
        val body = BlogText.body("과천시 최고 실거래 단지", "과천에서 제일 비싸게 팔린 곳이에요.\n\n1. 가 25억\n2. 나 20억\n\n※ 1년 이전 거래는 빠짐\n\n어디가 제일 끌리세요?")
        assertTrue(body.startsWith("과천에서 제일 비싸게 팔린 곳이에요.\n\n■ 숫자로 보는 핵심\n\n1. 가 25억"))
        assertTrue(body.contains("■ 마무리\n\n어디가 제일 끌리세요?") && body.contains("- 1년 이전 거래는 빠짐") && body.contains("권유가 아닙니다"))
        assertTrue(body.trim().lines().last().startsWith("#과천시 #최고 #실거래") && body.contains("#부동산"))
        assertEquals(Platform.BLOG, Platform.of("blog")); assertEquals(Platform.THREADS, Platform.of(null))
    }

    // ----- 블로그 글 틀 · 중개사 한마디 · 평가 · 계정 -----
    @Test
    fun blogMix() {
        val day = java.time.LocalDate.of(2026, 11, 21)
        // 두 계정은 어느 날 어느 글이든 다른 틀, 날이 바뀌면 틀도 바뀜
        for (d in 0L..6L) for (k in listOf("stat-a", "top|과천", "x")) assertTrue(BlogMix.styleIndex(k, 0, day.plusDays(d)) != BlogMix.styleIndex(k, 1, day.plusDays(d)))
        assertTrue(BlogMix.styleIndex("k", 0, day) != BlogMix.styleIndex("k", 0, day.plusDays(1)))
        assertTrue(BlogMix.styleIndex("k", 0, day) != BlogMix.styleIndex("k", 0, day, shift = 1))
        // 틀마다 제목·소제목이 다름
        val text = "과천에서 제일 비싸게 팔린 곳이에요.\n\n1. 가 25억\n2. 나 20억\n\n※ 1년 이전 거래는 빠짐\n\n어디가 제일 끌리세요?"
        val bodies = (0..3).map { BlogText.body("과천시 최고 실거래 단지", text, it) }
        assertEquals(4, bodies.distinct().size); assertEquals(4, (0..3).map { BlogText.title("과천시 최고 실거래 단지", day, it) }.distinct().size)
        assertTrue(bodies[1].contains("▶ 핵심 숫자 정리\n\n1. 가 25억") && bodies[1].indexOf("자료:") < bodies[1].indexOf("▶ 알아둘 점"))
        // 써 둔 글은 소제목 기호만 바꿈
        assertEquals("첫 문단\n\n◆ 소제목\n\n내용", BlogMix.restyle("첫 문단\n\n■ 소제목\n\n내용", 2))
        // 중개사 한마디: 0번 틀은 첫 문단 뒤, 1번 틀은 참고 앞. 비어 있으면 그대로
        assertEquals(bodies[0], BlogMix.withOpinion(bodies[0], "  ", 0))
        assertTrue(BlogMix.withOpinion(bodies[0], "전세 문의가 많아요.", 0).startsWith("과천에서 제일 비싸게 팔린 곳이에요.\n\n■ 중개사 한마디\n\n전세 문의가 많아요.\n\n■ 숫자로 보는 핵심"))
        assertTrue(BlogMix.withOpinion(bodies[1], "전세 문의가 많아요.", 1).contains("어디가 제일 끌리세요?\n\n▶ 현장에서 본 생각\n\n전세 문의가 많아요.\n\n자료:"))
        assertEquals("본문\n\n■ 중개사 한마디\n\n의견", BlogMix.withOpinion("본문", "의견", 0))
        // 평가: 숫자가 든 본문 단락에만, 단락에 없는 숫자가 든 평가는 버림
        val b = bodies[0]
        val input = BlogMix.evalInput(b)
        assertTrue(input.startsWith("[1] 1. 가 25억\n2. 나 20억") && !input.contains("[2]") && !input.contains("1년 이전"))
        val ev = BlogMix.parseEvals("[1] 1위와 2위 차이가 꽤 큰 편입니다.\n[2] 없는 단락", b)
        assertEquals(mapOf(1 to "1위와 2위 차이가 꽤 큰 편입니다."), ev)
        assertTrue(BlogMix.parseEvals("[1] 30억까지 오른 셈이라 차이가 큽니다.", b).isEmpty())
        val out = BlogMix.withEvals(b, ev, 0)
        assertTrue(out.contains("2. 나 20억\n\n→ 1위와 2위 차이가 꽤 큰 편입니다.\n\n■ 마무리"))
        assertTrue(BlogMix.evalTargets(out).isEmpty()) // 평가가 달린 단락에는 또 붙이지 않음
        assertTrue(BlogMix.evalTargets("첫 문단입니다 2026년 기준으로 썼어요.\n\n참고 자료\n- 국토교통부 (2026-11-21 받음)").size == 1) // 참고 자료에는 붙이지 않음
        // AI 블로그 지시문도 틀마다 다르고, 평가를 쓰라고 함
        assertEquals(4, (0..3).map { Summarizer.blogSystem("news", emptyList(), "", it) }.distinct().size)
        assertTrue(Summarizer.blogSystem("news", emptyList()).contains("평가"))
        // 다른 계정으로 올린 기록
        val now = 1_800_000_000_000L
        val log = BlogLog.parse(BlogLog.toJson(listOf(BlogLog.Sent("a", 0, now - 86_400_000L), BlogLog.Sent("old", 0, now - 40L * 86_400_000L))))
        assertEquals(0, BlogLog.otherAccount(log, "a", 1, now)!!.account)
        assertNull(BlogLog.otherAccount(log, "a", 0, now)); assertNull(BlogLog.otherAccount(log, "old", 1, now)); assertNull(BlogLog.otherAccount(log, "b", 1, now))
    }

    // ----- 첫 이미지 프롬프트 -----
    @Test
    fun coverPrompt() {
        val text = "힐스테이트 평촌역 더퍼스트 월세, 3년 치 실거래 182건 뜯어봤어.\n\n· 전용 79㎡대: 188만 → 190만 → 217만 (최근 1년 +14%)\n· 지금 네이버 월세 매물은 5건뿐, 매매는 57건\n\n자료: 국토부 실거래가(11/21 받음)"
        val p = CoverPrompt.build("힐스테이트 평촌역 더퍼스트 월세 분석", text, 7)
        // 내용: 제목·핵심 숫자·지역·주제 장면이 들어감
        assertTrue(p.contains("이미지의 주인공): \"힐스테이트 평촌역 더퍼스트 월세 분석\"") && p.contains("평촌역 일대") && p.contains("4:5"))
        assertTrue(p.contains("\"전용 79㎡대 188만 → 217만 (+14%)\"") && p.contains("\"지금 네이버 월세 매물은 5건뿐\"") && !p.contains("11/21"))
        assertTrue(Regex("열쇠").containsMatchIn(p)) // 월세 글은 열쇠·창가 장면
        assertTrue(CoverPrompt.build("법원 경매 물건", "최저가 3.83억", 1).contains("경매"))
        // 꼴: seed가 바뀌면 다른 프롬프트, 같으면 같은 프롬프트
        val many = (0 until 40).map { CoverPrompt.build("아파트 매매가격 주간 변동률", "전국 +0.07%", it) }.toSet()
        assertTrue(many.size >= 20)
        assertEquals(CoverPrompt.build("a", "1건", 3), CoverPrompt.build("a", "1건", 3))
        val day = java.time.LocalDate.of(2026, 11, 22)
        assertTrue(CoverPrompt.seed("k", 0, day, 0) != CoverPrompt.seed("k", 1, day, 0) && CoverPrompt.seed("k", 0, day, 0) != CoverPrompt.seed("k", 0, day, 1))
        // 제목은 18자 안팎으로 낱말 단위 자르기, 괄호·이모지 뺌
        assertEquals("아파트 매매가격 주간 변동률", CoverPrompt.headline("📈 아파트 매매가격 · 주간 변동률 (2026년 11월)"))
        assertTrue(CoverPrompt.headline("안양시 동안구 관양동 힐스테이트 평촌역 더퍼스트 실거래가 정리").length <= CoverPrompt.MAX_HEAD)
        // 그때와 지금: 지역 이름은 제목에서 빠지고 단지 이름이 큰 제목, 숫자는 변화 줄과 가장 최근 값
        val h = CoverPrompt.build("공작부영아파트 · 그때와 지금", "안양시 동안구 관양동 아파트 2006년 vs 2026년 가격 비교\n\n공작부영아파트 60㎡\n2006년: 2억 8,020만원\n2016년: 3억 3,752만원\n2026년: 8억 576만원\n\n20년 사이에 5.3억이 벌어졌어요.", 2, context = "안양시 동안구 관양동 공작부영아파트 60㎡ 실거래가 그때와 지금")
        assertTrue(h.contains("\"공작부영아파트 그때와 지금\"") && h.contains("\"20년 사이에 5.3억이 벌어졌어요\"") && h.contains("\"2026년 8억 576만\"") && h.contains("평촌역 일대"))
        assertEquals("공작부영아파트 실거래가 그때와 지금", CoverPrompt.headline("안양시 동안구 관양동 공작부영아파트 실거래가 그때와 지금"))
        // 실거래 vs 호가: 제목은 자르지 않고, 비교는 두 값이 다 남고, 기준일이 들어감
        val acro = "평촌아크로타워 월세, 실거래랑 지금 호가 맞춰봤어.\n\n보증금 맞춰서 보면 (실거래 → 호가)\n· 34㎡ 원룸: 90만 → 93만 (+3%)\n· 67㎡ 투룸: 137만 → 160만 (+17%)\n· 83㎡ 쓰리룸: 190만 → 216~270만 (+14~42%)\n\n자료: 국토부 실거래가·네이버 부동산 (11/22)"
        assertEquals("평촌아크로타워 월세 실거래 vs 호가", CoverPrompt.headline("평촌아크로타워 월세 실거래 vs 호가"))
        assertEquals(listOf("83㎡ 쓰리룸 190만 → 216~270만 (+14~42%)", "67㎡ 투룸 137만 → 160만 (+17%)"), CoverPrompt.figures(acro))
        val ap = CoverPrompt.build("평촌아크로타워 월세 실거래 vs 호가", acro, 5, asOf = java.time.LocalDate.of(2026, 11, 22))
        assertTrue(ap.contains("\"평촌아크로타워 월세 실거래 vs 호가\"") && ap.contains("\"2026.11.22 기준\"") && ap.contains("둘 다 넣어"))
        val today = java.time.LocalDate.of(2026, 11, 22)
        assertEquals(java.time.LocalDate.of(2026, 11, 22), CoverPrompt.asOf(acro, today))
        assertEquals(java.time.LocalDate.of(2026, 11, 18), CoverPrompt.asOf("2023-12-01~2026-11-18 계약분", today))
        assertEquals(java.time.LocalDate.of(2026, 11, 21), CoverPrompt.asOf("2026년 11월 21일 기준", today))
        assertNull(CoverPrompt.asOf("날짜 없음 3건", today))
        assertEquals("평촌아크로타워 오피스텔 전월세 실거래가", CoverPrompt.headline("평촌아크로타워 오피스텔 전월세 실거래가 vs 네이버 호가"))
        assertTrue(CoverPrompt.figures("자료: 2026년\n#태그 3건\n그냥 문장").isEmpty())
    }
}
