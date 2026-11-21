package com.realestatenewsfinder.app

import android.content.Context

/** AI가 만든 블로그 글: 제목, 본문(소제목 포함), 태그 + 카드 이미지용 내용 */
data class BlogPost(val title: String, val body: String, val tags: List<String>, val points: List<String>)

/** AI가 만든 스레드 글 + 카드 이미지용 내용 */
data class Summary(val thread: String, val headline: String, val points: List<String>)

/**
 * 스레드용 요약: 기사·보도자료·커뮤니티 글 본문을 읽고, 사람들이 부동산 글에서 궁금해하는 것
 * (나한테 해당되나 · 언제부터 · 얼마 · 뭘 해야 하나)에 답하는 글과 카드 내용을 만듦.
 * Gemini → Groq → OpenRouter 순서. 키가 없거나 모두 실패하면 null.
 */
object Summarizer {
    const val MAX_THREAD_CHARS = 480 // 스레드 글자 수 제한(500자) 안쪽

    private val ANGLES = mapOf(
        "정책" to "누가 대상인지, 언제부터 시행인지, 지금과 무엇이 달라지는지",
        "청약·분양" to "어디 단지인지, 청약 일정, 분양가, 신청 자격과 물량",
        "시세" to "어느 지역·단지인지, 얼마에서 얼마로 변했는지, 변동 폭과 기간",
        "금리·대출" to "금리나 한도가 얼마나 바뀌는지, 누구에게 적용되는지, 언제부터인지",
        "재건축" to "어느 구역·단지인지, 지금 어느 단계인지, 다음 일정과 규모",
        "전월세" to "세입자·집주인에게 무엇이 달라지는지, 금액, 확인하거나 조심할 점",
        "지원·혜택" to "누가 받을 수 있는지, 얼마를 받는지, 신청 기간과 방법",
        "세금" to "누가 내는지(또는 줄어드는지), 금액·세율, 적용 시점과 요건",
    )

    /** 실험실 글 형식: 기본(한마디 + 요약) 말고 조회수 템플릿을 따라 쓰는 형식들 */
    val LAB_FORMATS = linkedMapOf("basic" to "기본", "qa" to "문답형", "debate" to "찬반 질문형", "checklist" to "체크리스트형")

    /** 실험실 형식별 글 쓰는 법과 예시. basic이면 null */
    private fun labForm(format: String, casual: Boolean): Pair<String, String>? {
        fun e(c: String, p: String) = if (casual) c else p
        return when (format) {
            "qa" -> ("- 구성(문답형): 아래 순서 그대로 쓴다.\n" +
                "  1) 첫 줄: '[제도·대출·세금 이름] 이거 모르면 진짜 손해' 꼴의 한 줄. 이름은 본문에 나온 그대로.\n" +
                "  2) 'Q. ' 로 시작하는 질문 한 줄: 읽는 사람이 가장 궁금해할 것 하나.\n" +
                "  3) 'A. ' 로 시작하는 답 한 줄: 결론부터.\n" +
                "  4) '- ' 로 시작하는 줄 2~3개: 대상·조건·금액·시기. 본문에 있는 것만. 놓치기 쉬운 점이 본문에 있으면 마지막 줄을 '- 주의: ' 로 쓴다.\n" +
                "  5) 마지막 두 줄: '${e("헷갈리는 상황 있으면 댓글 남겨줘.", "헷갈리는 상황 있으면 댓글 남겨주세요.")}' / '${e("하나씩 답해줄게.", "하나씩 답해드릴게요.")}'\n" +
                "- 본문이 제도·대출·세금·지원 내용이 아니면 이 형식을 억지로 맞추지 말고, 한마디 한 줄과 요약 2~3줄로 쓴다.\n") to
                ("OO대출 이거 모르면 진짜 손해\n\nQ. 누가 받을 수 있어?\nA. ${e("연소득 N원 이하 무주택자면 돼", "연소득 N원 이하 무주택자면 돼요")}\n\n- 한도는 최대 N원\n- N월 N일 신청분부터 적용\n- 주의: 이미 집이 있으면 안 됨\n\n" +
                    e("헷갈리는 상황 있으면 댓글 남겨줘.\n하나씩 답해줄게.", "헷갈리는 상황 있으면 댓글 남겨주세요.\n하나씩 답해드릴게요."))
            "debate" -> ("- 구성(찬반 질문형): 본문에 서로 다른 두 입장이 있을 때만 쓴다.\n" +
                "  1) 첫 줄: 상황을 한 줄로.\n" +
                "  2) 두 줄: '[한쪽]은 [주장],' / '[다른 쪽]은 [주장].' 본문에 나온 입장 그대로. 어느 쪽이 옳다고 판단하지 않는다.\n" +
                "  3) 마지막 두 줄: '이거 [A]가 맞아, [B]가 맞아?' 꼴의 질문 / '${e("비슷한 경험 있는 사람 댓글로 알려줘.", "비슷한 경험 있으신 분 댓글로 알려주세요.")}'\n" +
                "- 본문에 두 입장이 없으면 이 형식을 억지로 맞추지 말고, 한마디 한 줄과 요약 2~3줄로 쓴다.\n") to
                ("OO 문제로 집주인이랑 세입자가 부딪혔대\n\n집주인은 OO라고 하고,\n세입자는 OO라고 함.\n\n" + e("이거 집주인이 맞아, 세입자가 맞아?\n비슷한 경험 있는 사람 댓글로 알려줘.", "이거 집주인이 맞을까요, 세입자가 맞을까요?\n비슷한 경험 있으신 분 댓글로 알려주세요."))
            "checklist" -> ("- 구성(체크리스트형): 본문에 사기·피해 수법이나 확인할 점이 나올 때만 쓴다.\n" +
                "  1) 첫 줄: 어떤 피해·수법 얘기인지 한 줄 (예: '요즘 OO 수법 N가지'). 개수는 실제로 쓴 줄 수와 같게.\n" +
                "  2) '1. ' '2. ' 로 시작하는 줄 2~4개: '[수법]: [한 줄 설명]'. 본문에 나온 것만.\n" +
                "  3) 본문에 확인할 점·예방법이 있으면: '${e("계약 전에 이건 꼭 확인해.", "계약 전에 이건 꼭 확인하세요.")}' 한 줄 뒤에 '✅ ' 로 시작하는 줄 2~3개. 본문에 없으면 이 부분은 통째로 뺀다.\n" +
                "  4) 마지막 줄: '${e("짝꿍이랑 친구한테도 공유해줘.", "가족·친구에게도 공유해 주세요.")}'\n" +
                "- 본문에 없는 수법·확인 사항을 지어내지 않는다. 그런 내용이 없으면 한마디 한 줄과 요약 2~3줄로 쓴다.\n") to
                ("요즘 OO 사기 수법 N가지\n\n1. OO: OO하는 방식\n2. OO: OO하는 방식\n\n" + e("계약 전에 이건 꼭 확인해.", "계약 전에 이건 꼭 확인하세요.") + "\n✅ OO 확인\n✅ OO 확인\n\n" + e("짝꿍이랑 친구한테도 공유해줘.", "가족·친구에게도 공유해 주세요."))
            else -> null
        }
    }

    fun system(kind: String, tags: List<String>, casual: Boolean, today: String = "", format: String = "basic"): String {
        val what = when (kind) {
            "gov" -> "정부·공공기관 보도자료"
            "community" -> "부동산 커뮤니티 게시글"
            else -> "부동산 뉴스 기사"
        }
        val angle = tags.mapNotNull { ANGLES[it] }.take(2).joinToString(" / ").ifEmpty { "누구에게 해당되는지, 언제부터인지, 금액·비율이 얼마인지" }
        val tone = if (casual)
            "스레드에서 흔히 쓰는 편한 반말. 친구한테 방금 본 뉴스를 말해주듯 쓴다. 문장 끝은 '~했대', '~래', '~임', '~함', '~더라', '~인 듯'처럼. "
        else
            "스레드에 맞는 가벼운 존댓말. 아는 사람에게 방금 본 뉴스를 알려주듯 쓴다. 문장 끝은 '~했대요', '~래요', '~예요', '~더라고요'처럼. "
        // 형식 예시: 첫 줄은 한마디(숫자 없음), 그 아래 핵심 사실 2~3줄
        val ex = if (casual) listOf(
            "대출로 집 사기, 문턱이 한 칸 더 높아졌네", "OO 대출 한도가 다음 달부터 줄어든대", "연소득 N원 기준으로 N원 정도 줄어듦", "N월 N일 신청분부터 적용이래",
        ) else listOf(
            "대출로 집 사기, 문턱이 한 칸 더 높아졌네요", "OO 대출 한도가 다음 달부터 줄어든대요", "연소득 N원 기준으로 N원 정도 줄어들어요", "N월 N일 신청분부터 적용이래요",
        )
        val community = if (kind == "community")
            "- 이 글은 개인의 경험·의견이다. 사실처럼 단정하지 말고 '~라는 글', '~라는 의견'으로 전한다. 작성자 닉네임은 쓰지 않는다.\n" else ""
        return "너는 $what 을(를) 읽고 스레드(Threads)에 올릴 짧은 글로 바꿔 쓰는 사람이다. 읽는 사람은 내 집 마련·전월세·대출·세금이 걱정인 일반인이다.\n" +
            "입력은 제목과 본문이다. 본문에는 메뉴·광고·다른 기사 제목이 섞여 있을 수 있으니, 제목과 관련된 내용만 쓴다.\n" +
            (if (today.isNotEmpty()) "오늘은 $today 이다.\n" else "") + "\n" +
            "[주제] 글의 주제는 입력의 '제목'이 말하는 바로 그것이다. 주제를 바꾸지 않는다. 본문에 나오는 다른 숫자나 곁가지 내용을 주제로 삼지 않는다.\n" +
            "[가장 먼저 할 일] 제목이 말하는 사실(무슨 일이 있었나)을 본문에서 확인하고, 그 이유와 뒷받침하는 숫자를 본문에서 찾는다. 글의 내용은 본문에 있는 것으로만 채운다.\n" +
            "이 글에서 특히 살릴 것: $angle. 본문에 없으면 억지로 넣지 않는다.\n" +
            "입력에 '공고 주요 정보'가 있으면(청약·분양·임대 공고) 그 안의 위치, 세대수, 분양가·임대조건, 청약 일정, 입주 시기를 빠짐없이 살려 쓴다. 누가 신청할 수 있는지가 본문에 있으면 함께 쓴다.\n\n" +
            "[사실 규칙 — 어기면 안 됨]\n" +
            "- 숫자(금액, 비율, 경쟁률, 가구 수, 날짜, 연도)는 본문에 있는 것만, 본문에 적힌 그대로 쓴다. 계산하거나 단위를 바꾸지 않는다.\n" +
            "- 본문에 없는 연도·날짜를 쓰지 않는다. '올해', '지난해', '현재' 같은 표현을 특정 연도로 바꾸지 않는다.\n" +
            "- 숫자가 무엇에 대한 것인지(어느 지역, 어느 기간, 무엇과 비교)를 본문과 똑같이 연결한다. 서로 다른 숫자를 섞지 않는다.\n" +
            "- 본문에 없는 전망, 추측, 권유(사라, 팔아라, 넣어라, 미리 확인해라)를 쓰지 않는다. 본문에 전망이 있으면 누가 말했는지 함께 쓴다.\n" +
            community + "\n" +
            "[스레드 글 쓰는 법]\n" +
            "- 말투: ${tone}기사체('~했다', '~로 나타났다', '~것으로 집계됐다')와 보고서체는 쓰지 않는다.\n" +
            (labForm(format, casual)?.let { (how, _) ->
                how +
                    "- 숫자는 본문에 적힌 그대로, 꼭 필요한 것만. 줄 사이에는 빈 줄을 적당히 두고 전체 420자 이내.\n" +
                    "- 쓰지 않는 것: 화살표(→ ↓ ↑), 한자(比 등), 표, 해시태그, 따옴표, '출처' 표기, 같은 말 반복. 이모지는 위에서 정한 ✅ 만.\n\n"
            } ?: basicForm()) +
            "[출력 형식] 아래 두 구역만 출력한다. 머리말·설명 없이.\n" +
            "[스레드]\n" + (if (labForm(format, casual) != null) "(위 구성대로 쓴 글)" else "(첫 줄 한마디, 빈 줄, 요약 2~3줄)") + "\n" +
            "[카드]\n" +
            "- 항목: 내용  (이런 줄 3~5개. 첫 줄은 제목이 말하는 핵심 사실. 항목에는 어느 지역·무엇에 대한 숫자인지 드러나게 쓴다(예: '강남 일반분양', '서울 1순위 신청자'). 항목 10자 이내, 내용 28자 이내. 본문에 있는 것만)\n\n" +
            "[형식 예시 — 형식과 말투만 참고. OO, N은 자리 표시이며 예시의 내용은 절대 가져오지 않는다]\n" +
            "[스레드]\n" + (labForm(format, casual)?.second ?: ex.joinToString("\n\n")) + "\n" +
            "[카드]\n- 적용 지역: OO\n- 한도 변화: 연소득 N원 기준 N원 감소\n- 시행일: N월 N일 신청분부터\n- 예외: 그 전 계약분은 종전 기준"
    }

    /** 기본 형식: 맨 위 한마디 + 핵심 2~3줄 */
    private fun basicForm(): String =
            "- 첫 줄(한마디): 이 소식을 한 문장으로 찌르는 촌철살인 한마디. 본문의 사실에서 나온 해석이어야 하고, 읽자마자 무슨 소식인지 감이 와야 한다. " +
            "비유·대비·반전은 써도 된다. 숫자는 넣지 않는다. 본문에 없는 사실, 전망, 권유, 비아냥, 낚시성 과장은 쓰지 않는다. 25자 안팎.\n" +
            "- 이어서 2~3줄(요약): 가장 중요한 사실만 고른다. 한 줄에 완결된 짧은 문장 하나, 줄마다 사실 하나. 숫자는 꼭 필요한 것만, 본문에 적힌 그대로. " +
            "무엇에 대한 숫자인지(어느 지역·기간·무엇과 비교)를 문장 안에 풀어 쓴다(예: '3분기 매매 거래가 2분기보다 29.8% 줄었대'). 곁가지 숫자를 줄줄이 나열하지 않는다.\n" +
            "- 한마디와 요약으로 끝낸다. 맺음말, 정리, 권유를 덧붙이지 않는다.\n" +
            "- 줄 사이에는 빈 줄 하나. 한 줄 45자 이내, 전체 220자 이내.\n" +
            "- 쓰지 않는 것: 화살표(→ ↓ ↑), 한자(比 등), 콜론(:)으로 나열, 괄호 속 숫자 나열, 표·목록 형식, 해시태그, 이모지, 따옴표, 번호, 글머리 기호, '출처' 표기, 같은 말 반복.\n\n"

    private val NUMBER = Regex("\\d[\\d,]*(?:\\.\\d+)?")
    private fun numbers(text: String): List<String> = NUMBER.findAll(text).map { it.value.replace(",", "").trimEnd('.') }.toList()

    /** 요약에 나온 숫자 중 원문(제목·본문)에 없는 것 → 지어낸 숫자 */
    fun unsupportedNumbers(summaryText: String, source: String, lenient: Boolean = false): List<String> {
        val known = numbers(source).toSet()
        return numbers(summaryText).filter { it !in known && !(lenient && it.toIntOrNull()?.let { n -> n in 1..9 } == true) }.distinct()
    }

    /** 원문에 없는 숫자가 들어간 줄을 뺌 */
    fun dropLinesWith(s: Summary, bad: List<String>): Summary {
        fun ok(line: String) = numbers(line).none { it in bad }
        val thread = s.thread.lines().filter { ok(it) }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return Summary(thread, if (ok(s.headline)) s.headline else "", s.points.filter { ok(it) })
    }

    fun hasKey(ctx: Context) = Prefs(ctx).let { p -> p.geminiKey.isNotBlank() || p.groqKey.isNotBlank() || ExtraAi.ALL.any { p.aiKey(it.id).isNotBlank() } }

    /** 마지막 실패 사유 (키를 넣은 AI 중 맨 앞 것) */
    fun lastError(): String? = Gemini.lastError ?: Groq.lastError ?: ExtraAi.ALL.firstNotNullOfOrNull { it.lastError }

    /** Gemini → Groq → OpenRouter 순서로, 답이 나올 때까지 */
    suspend fun ask(ctx: Context, system: String, input: String): String? {
        Gemini.ask(ctx, system, input)?.let { return it }
        Groq.ask(ctx, system, input)?.let { return it }
        for (ai in ExtraAi.ALL) ai.ask(ctx, system, input)?.let { return it }
        return null
    }

    fun buildInput(post: Post, sourceName: String, body: String): String {
        val text = body.replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
        val lead = post.summary?.takeIf { it.isNotBlank() && !text.contains(it.take(30)) }?.let { "기사 앞부분: $it\n\n" }.orEmpty()
        // 공고 상세 페이지에서 뽑은 주요 정보(위치·세대수·분양가·일정)는 본문보다 앞에 둠 → 요약에 빠지지 않게
        val facts = if (post.details.isEmpty()) "" else "공고 주요 정보:\n" + post.details.joinToString("\n") + (post.note?.let { "\n안내: $it" } ?: "") + "\n\n"
        // 보도자료 첨부 문서의 글자가 있으면 본문 뒤에 붙임 (본문은 "첨부파일 참고" 한 줄뿐인 경우가 많음). 전체 길이는 비슷하게 유지
        val attach = post.attach?.takeIf { it.isNotBlank() }
        if (attach != null) return "매체: $sourceName\n제목: ${post.title}\n\n${facts}${lead}본문:\n${text.take(2000)}\n\n첨부파일 내용(${post.attachName ?: "첨부 문서"}) — 보도자료의 실제 내용이니 이것을 중심으로 요약:\n${attach.take(5000)}"
        return "매체: $sourceName\n제목: ${post.title}\n\n${facts}${lead}본문:\n${text.take(6000)}"
    }

    private fun cleanLine(l: String) = l.trim().trim('"', '“', '”', '*').trim()

    /** AI 응답을 스레드 글과 카드 내용으로 나눔. 형식이 어긋나도 최대한 살림 */
    fun parse(raw: String, fallbackTitle: String, keepMarks: Boolean = false): Summary? {
        val text = raw.replace("\r", "").replace("**", "").trim()
        val cardAt = Regex("\\[\\s*카드\\s*]").find(text)
        val threadPart = (if (cardAt != null) text.substring(0, cardAt.range.first) else text).replace(Regex("\\[\\s*스레드\\s*]"), "")
        var thread = threadPart.lines().map { l -> cleanLine(l).let { if (keepMarks) it else it.replace(Regex("^([-•·]|\\d+[.)])\\s*"), "") } }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (thread.isBlank()) return null
        if (thread.length > MAX_THREAD_CHARS) {
            val cut = thread.take(MAX_THREAD_CHARS)
            val end = cut.lastIndexOf('\n')
            thread = (if (end > MAX_THREAD_CHARS / 2) cut.take(end) else cut).trim()
        }
        var headline = ""
        val points = mutableListOf<String>()
        if (cardAt != null) {
            text.substring(cardAt.range.last + 1).lines().map { cleanLine(it) }.filter { it.isNotEmpty() }.forEach { l ->
                val m = Regex("^제목\\s*[:：]\\s*(.+)$").find(l)
                if (m != null && headline.isEmpty()) headline = m.groupValues[1].trim()
                else l.replace(Regex("^([-•·]|\\d+[.)])\\s*"), "").trim().takeIf { it.isNotEmpty() && !it.startsWith("[") }?.let { points += it }
            }
        }
        // 카드 구역이 없으면 스레드 글의 줄을 카드 내용으로 사용
        if (points.isEmpty()) points += thread.lines().filter { it.isNotBlank() }.drop(1).take(5)
        if (headline.isEmpty()) headline = fallbackTitle // 표지 제목은 기사 제목 그대로
        return Summary(thread, headline, points.take(6))
    }

    /** AI 없이 만드는 기본 내용: 제목 + 피드에 있던 기사 앞부분. 공고(청약·LH)는 상세 페이지에서 뽑은 주요 정보를 그대로 씀 */
    fun basic(post: Post): Summary {
        if (post.details.isNotEmpty()) {
            val lines = post.details.take(7)
            return Summary((listOf(post.title) + lines).joinToString("\n\n"), post.title, post.details.take(6))
        }
        val sentences = post.summary.orEmpty().split(Regex("(?<=[.다요])\\s+")).map { it.trim() }.filter { it.length in 8..90 }.take(3)
        return Summary((listOf(post.title, "") + sentences).joinToString("\n").trim(), post.title, sentences)
    }

    /** 마지막 요약에서 원문에 없는 숫자 때문에 뺀 줄이 있으면 그 숫자들 (화면 안내용) */
    @Volatile var lastDropped: List<String> = emptyList()

    suspend fun summarize(ctx: Context, post: Post, kind: String, sourceName: String, body: String, format: String = "basic"): Summary? {
        if (!hasKey(ctx)) return null
        lastDropped = emptyList()
        val today = java.text.SimpleDateFormat("yyyy년 M월 d일", java.util.Locale.KOREA).format(java.util.Date())
        val lab = format != "basic"
        val system = system(kind, post.tags, Prefs(ctx).casualNow, today, format)
        val input = buildInput(post, sourceName, body)
        fun all(s: Summary) = s.thread + "\n" + s.headline + "\n" + s.points.joinToString("\n")
        var sum = parse(ask(ctx, system, input) ?: return null, post.title, lab) ?: return null
        var bad = unsupportedNumbers(all(sum), input, lab)
        if (bad.isNotEmpty()) {
            // 지어낸 숫자가 있으면 한 번 더 요청
            val retry = ask(ctx, system, "$input\n\n[다시 쓰기] 방금 쓴 글에 본문에 없는 숫자(${bad.joinToString(", ")})가 있었다. 본문에 있는 숫자만 써서 처음부터 다시 써라.")
                ?.let { parse(it, post.title, lab) }
            if (retry != null) {
                val bad2 = unsupportedNumbers(all(retry), input, lab)
                if (bad2.size <= bad.size) { sum = retry; bad = bad2 }
            }
        }
        if (bad.isNotEmpty()) {
            val cleaned = dropLinesWith(sum, bad)
            if (cleaned.thread.isBlank()) return null
            lastDropped = bad
            sum = cleaned.copy(headline = cleaned.headline.ifBlank { post.title })
        }
        return sum
    }

    // ---------- 블로그 글 ----------
    const val MAX_BLOG_CHARS = 1600

    /**
     * 네이버 블로그용 글 쓰는 법. 검색에 잘 걸리는 글의 공통 구성을 따름:
     * 핵심 낱말이 앞에 오는 제목 → 결론부터 쓰는 첫 문단 → 질문형 소제목 3개와 짧은 문단 → 한눈에 정리 → 마무리 → 태그.
     * 기사 문장을 그대로 베끼지 않고 풀어 쓰며(비슷한 문서로 묶이지 않게), 같은 낱말을 억지로 반복하지 않음.
     */
    fun blogSystem(kind: String, tags: List<String>, today: String = "", style: Int = 0): String {
        // 틀마다 소제목 꼴·정리 소제목·정리 위치를 달리해 날마다·계정마다 글 구성이 겹치지 않게 함
        val heads = when (Math.floorMod(style, 4)) {
            1 -> "소제목 3개. 소제목은 '■ ' 로 시작하고 핵심 낱말로 끝나는 짧은 말로 쓴다(예: '■ 대상과 조건', '■ 적용 시기', '■ 달라지는 금액')"
            2 -> "소제목 2~4개(내용에 맞게). 소제목은 '■ ' 로 시작하고 '~하는 이유', '~확인할 것', '~달라지는 점' 처럼 풀어 쓴다"
            3 -> "소제목 3개. 소제목은 '■ ' 로 시작하고 첫째·둘째·셋째 순서가 드러나게 쓴다(예: '■ 먼저, 무엇이 바뀌나', '■ 다음, 누가 해당되나', '■ 끝으로, 언제부터인가')"
            else -> "소제목 3개. 소제목은 '■ ' 로 시작하고 읽는 사람이 검색할 만한 질문 꼴로 쓴다(예: '■ 누가 대상일까?', '■ 언제부터 적용될까?', '■ 얼마나 달라질까?')"
        }
        val recapName = listOf("한눈에 정리", "핵심만 다시 보기", "세 줄 요약", "요점 정리")[Math.floorMod(style, 4)]
        val recapFirst = Math.floorMod(style, 4) == 2
        val recap = "'■ $recapName' 소제목 아래 '- ' 로 시작하는 줄 3~4개로 핵심만 정리한다"
        val what = when (kind) {
            "gov" -> "정부·공공기관 보도자료"
            "community" -> "부동산 커뮤니티 게시글"
            else -> "부동산 뉴스 기사"
        }
        val angle = tags.mapNotNull { ANGLES[it] }.take(2).joinToString(" / ").ifEmpty { "누구에게 해당되는지, 언제부터인지, 금액·비율이 얼마인지" }
        val community = if (kind == "community")
            "- 이 글은 개인의 경험·의견이다. 사실처럼 단정하지 말고 '~라는 글', '~라는 의견'으로 전한다. 작성자 닉네임은 쓰지 않는다.\n" else ""
        return "너는 $what 을(를) 읽고 네이버 블로그에 올릴 부동산 정보 글로 바꿔 쓰는 사람이다. 읽는 사람은 검색으로 들어온, 내 집 마련·전월세·대출·세금이 걱정인 일반인이다.\n" +
            "입력은 제목과 본문이다. 본문에는 메뉴·광고·다른 기사 제목이 섞여 있을 수 있으니, 제목과 관련된 내용만 쓴다.\n" +
            (if (today.isNotEmpty()) "오늘은 $today 이다.\n" else "") + "\n" +
            "[주제] 글의 주제는 입력의 '제목'이 말하는 바로 그것이다. 주제를 바꾸지 않는다.\n" +
            "이 글에서 특히 살릴 것: $angle. 본문에 없으면 억지로 넣지 않는다.\n" +
            "입력에 '공고 주요 정보'가 있으면 그 안의 위치, 세대수, 분양가·임대조건, 일정, 입주 시기를 빠짐없이 살려 쓴다.\n\n" +
            "[사실 규칙 — 어기면 안 됨]\n" +
            "- 숫자(금액, 비율, 경쟁률, 가구 수, 날짜, 연도)는 본문에 있는 것만, 본문에 적힌 그대로 쓴다. 계산하거나 단위를 바꾸지 않는다.\n" +
            "- 본문에 없는 연도·날짜를 쓰지 않는다. '올해', '지난해', '현재' 같은 표현을 특정 연도로 바꾸지 않는다.\n" +
            "- 숫자가 무엇에 대한 것인지(어느 지역, 어느 기간, 무엇과 비교)를 본문과 똑같이 연결한다.\n" +
            "- 본문에 없는 전망, 추측, 권유(사라, 팔아라, 넣어라), 내 경험담을 지어 쓰지 않는다. 본문에 전망이 있으면 누가 말했는지 함께 쓴다.\n" +
            "- 본문에 없는 내용으로 분량을 채우지 않는다. 본문이 짧으면 글도 짧게 쓴다.\n" +
            community + "\n" +
            "[블로그 글 쓰는 법]\n" +
            "- 말투: 읽기 편한 존댓말('~습니다', '~해요'를 섞어서). 기사 문장을 그대로 옮기지 말고 내 말로 풀어 쓴다. 기사체('~로 나타났다', '~것으로 집계됐다')는 쓰지 않는다.\n" +
            "- 제목: 사람들이 검색할 핵심 낱말(지역·단지·제도·대출 이름)을 맨 앞에 두고, 무엇이 달라지는지·얼마인지·언제부터인지가 드러나게 쓴다. 20~35자. 본문에 없는 과장('충격', '역대급', '무조건')은 쓰지 않는다.\n" +
            "- 첫 문단(2~3문장): 결론부터. 무슨 일인지 한 문장으로 말하고, 누구에게 해당되는 이야기인지 덧붙인다. 인사말·날씨 얘기·자기소개는 쓰지 않는다.\n" +
            (if (recapFirst) "- 첫 문단 바로 다음에 $recap.\n" else "") +
            "- 본문: $heads. " +
            "소제목마다 2~4문장. 한 문단은 2~3문장으로 끊고 문단 사이에 빈 줄을 둔다. 조건·금액이 여러 개면 '- ' 로 시작하는 줄로 나열한다.\n" +
            "- 평가: 숫자가 나오는 소제목 단락은 마지막 문장을 그 숫자가 읽는 사람에게 무슨 뜻인지 풀이하는 평가로 끝낸다(높은 편인지 낮은 편인지, 차이가 큰지, 무엇을 더 확인해야 하는지). 평가에는 본문에 있는 숫자만 쓰고 전망·권유는 쓰지 않는다.\n" +
            (if (recapFirst) "" else "- 그다음 $recap.\n") +
            "- 마무리(1~2문장): 본문에 있는 사실로 정리하고, 일정이나 확인할 곳이 본문에 있으면 알려준다. 사라·기다려라 같은 권유는 하지 않는다.\n" +
            "- 핵심 낱말은 제목, 첫 문단, 마무리에 자연스럽게 한 번씩 들어가면 충분하다. 같은 낱말을 억지로 반복하지 않는다.\n" +
            "- 길이: 본문 전체 700~1,100자. 쓰지 않는 것: 표, 이모지, 굵게 표시(**), 화살표, 한자, '출처' 표기, 링크.\n\n" +
            "[출력 형식] 아래 네 구역만 출력한다. 머리말·설명 없이.\n" +
            "[제목]\n(제목 한 줄)\n" +
            "[본문]\n(첫 문단, 소제목과 문단, $recapName, 마무리)\n" +
            "[태그]\n(본문에 나온 지역·단지·제도 이름 위주로 5~8개. 쉼표로 구분, # 없이)\n" +
            "[카드]\n- 항목: 내용  (이런 줄 3~5개. 항목 10자 이내, 내용 28자 이내. 본문에 있는 것만)"
    }

    /** AI 응답을 제목·본문·태그·카드 내용으로 나눔 */
    fun parseBlog(raw: String, fallbackTitle: String): BlogPost? {
        val text = raw.replace("\r", "").replace("**", "").trim()
        val marks = Regex("\\[\\s*(제목|본문|태그|카드)\\s*]").findAll(text).toList()
        fun part(name: String): String {
            val m = marks.firstOrNull { it.groupValues[1] == name } ?: return ""
            val end = marks.firstOrNull { it.range.first > m.range.first }?.range?.first ?: text.length
            return text.substring(m.range.last + 1, end).trim()
        }
        var body = (if (marks.any { it.groupValues[1] == "본문" }) part("본문") else if (marks.isEmpty()) text else "")
            .lines().joinToString("\n") { cleanLine(it).replace(Regex("^#{1,4}\\s*"), "■ ") }.replace(Regex("\n{3,}"), "\n\n").trim()
        if (body.isBlank()) return null
        if (body.length > MAX_BLOG_CHARS) {
            val cut = body.take(MAX_BLOG_CHARS)
            val end = cut.lastIndexOf("\n\n")
            body = (if (end > MAX_BLOG_CHARS / 2) cut.take(end) else cut).trim()
        }
        val title = part("제목").lines().firstOrNull { it.isNotBlank() }?.let { cleanLine(it) }?.take(60).orEmpty().ifBlank { fallbackTitle }
        val tags = part("태그").split(Regex("[,\\n#]+")).map { it.trim().replace(" ", "") }.filter { it.length in 2..15 }.distinct().take(8)
        val points = part("카드").lines().map { cleanLine(it).replace(Regex("^([-•·]|\\d+[.)])\\s*"), "").trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }.take(6)
        return BlogPost(title, body, tags, points)
    }

    /** AI 없이 만드는 블로그 기본 글: 제목 + 피드에 있던 앞부분 */
    fun basicBlog(post: Post): BlogPost {
        val b = basic(post)
        return BlogPost(post.title, b.thread.lines().drop(1).joinToString("\n").trim().ifBlank { post.title }, listOf("부동산") + post.tags.map { it.replace("·", "") }, b.points)
    }

    suspend fun summarizeBlog(ctx: Context, post: Post, kind: String, sourceName: String, body: String, style: Int = 0): BlogPost? {
        if (!hasKey(ctx)) return null
        lastDropped = emptyList()
        val today = java.text.SimpleDateFormat("yyyy년 M월 d일", java.util.Locale.KOREA).format(java.util.Date())
        val system = blogSystem(kind, post.tags, today, style)
        val input = buildInput(post, sourceName, body)
        fun all(b: BlogPost) = b.title + "\n" + b.body + "\n" + b.points.joinToString("\n")
        var blog = parseBlog(ask(ctx, system, input) ?: return null, post.title) ?: return null
        var bad = unsupportedNumbers(all(blog), input, lenient = true)
        if (bad.isNotEmpty()) {
            val retry = ask(ctx, system, "$input\n\n[다시 쓰기] 방금 쓴 글에 본문에 없는 숫자(${bad.joinToString(", ")})가 있었다. 본문에 있는 숫자만 써서 처음부터 다시 써라.")?.let { parseBlog(it, post.title) }
            if (retry != null) {
                val bad2 = unsupportedNumbers(all(retry), input, lenient = true)
                if (bad2.size <= bad.size) { blog = retry; bad = bad2 }
            }
        }
        if (bad.isNotEmpty()) {
            // 원문에 없는 숫자가 든 줄은 뺌
            fun ok(line: String) = numbers(line).none { it in bad }
            val cleaned = blog.body.lines().filter { ok(it) }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
            if (cleaned.isBlank()) return null
            lastDropped = bad
            blog = blog.copy(title = if (ok(blog.title)) blog.title else post.title, body = cleaned, points = blog.points.filter { ok(it) })
        }
        return blog
    }

    /** 숫자로 만든 블로그 글의 단락마다 한 줄 평가를 받아 옴 (번호 → 평가). 키가 없거나 실패하면 null */
    suspend fun evaluate(ctx: Context, body: String): Map<Int, String>? {
        if (!hasKey(ctx)) return null
        val input = BlogMix.evalInput(body)
        if (input.isBlank()) return emptyMap()
        return BlogMix.parseEvals(ask(ctx, BlogMix.EVAL_SYSTEM, input) ?: return null, body)
    }
}
