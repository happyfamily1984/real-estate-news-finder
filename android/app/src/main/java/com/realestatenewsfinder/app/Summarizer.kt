package com.realestatenewsfinder.app

import android.content.Context

/** AI가 만든 스레드 글 + 카드 이미지용 내용 */
data class Summary(val thread: String, val headline: String, val points: List<String>)

/**
 * 스레드용 요약: 기사·보도자료·커뮤니티 글 본문을 읽고, 사람들이 부동산 글에서 궁금해하는 것
 * (나한테 해당되나 · 언제부터 · 얼마 · 뭘 해야 하나)에 답하는 글과 카드 내용을 만듦.
 * Gemini → Groq → Cerebras → OpenRouter 순서. 키가 없거나 모두 실패하면 null.
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

    fun system(kind: String, tags: List<String>, casual: Boolean): String {
        val what = when (kind) {
            "gov" -> "정부·공공기관 보도자료"
            "community" -> "부동산 커뮤니티 게시글"
            else -> "부동산 뉴스 기사"
        }
        val angle = tags.mapNotNull { ANGLES[it] }.take(2).joinToString(" / ").ifEmpty { "누구에게 해당되는지, 언제부터인지, 금액·비율이 얼마인지" }
        val tone = if (casual) "친구에게 알려주듯 자연스러운 반말(~함, ~임, ~했어)" else "친절하고 간결한 존댓말 해요체(~예요, ~해요)"
        val community = if (kind == "community")
            "이 글은 개인의 경험·의견이다. 사실처럼 단정하지 말고 '~라는 글', '~라는 의견'으로 전해라. 작성자 닉네임은 쓰지 않는다. " else ""
        return "너는 $what 을(를) 스레드(Threads)에 올릴 글로 바꾸는 부동산 정보 에디터다. 읽는 사람은 내 집 마련·전월세·대출·세금이 걱정인 일반인이다. " +
            "그들이 궁금한 것은 '나한테 해당되나, 언제부터인가, 얼마인가, 지금 뭘 해야 하나'다. 이 글에서는 특히 다음에 답해라: $angle.\n" +
            "입력은 제목과 본문이며, 본문에는 메뉴·광고·다른 기사 제목이 섞여 있을 수 있으니 제목과 관련된 내용만 써라.\n\n" +
            "가장 중요한 규칙:\n" +
            "- 숫자(금액, 금리, 비율, 날짜, 지역·단지 이름, 가구 수)는 본문에 있는 그대로 정확히 옮긴다. 본문에 없는 숫자는 절대 만들지 않는다. 단위(억·만원·%·%p)를 바꾸거나 계산하지 않는다.\n" +
            "- 본문에 없는 전망, 추측, 투자 조언(사라, 팔아라, 오른다, 내린다)은 쓰지 않는다. 본문에 전망이 있으면 누가 말했는지 함께 쓴다.\n" +
            "- 본문에 답이 없는 항목(예: 시행일)은 지어내지 말고 빼라.\n" +
            community + "\n" +
            "출력 형식 (이 두 구역만, 머리말·설명 없이):\n" +
            "[스레드]\n" +
            "첫 줄: 읽는 사람이 '내 얘기네' 하고 멈추게 하는 한 문장(30자 안팎). 핵심 숫자나 달라지는 점을 넣는다. 낚시성 과장 금지.\n" +
            "(빈 줄)\n" +
            "그 아래 3~5줄: 한 줄에 짧은 문장 하나. 줄 사이에 빈 줄 하나씩. 대상 → 내용(숫자) → 시점 순서로.\n" +
            "마지막 줄: 읽는 사람이 확인하거나 챙길 것 한 가지(본문에 근거가 있을 때만), 없으면 핵심 한 줄 정리.\n" +
            "말투는 $tone. 한 줄 45자 이내. 전체 380자 이내. 해시태그·이모지·따옴표·번호·글머리 기호·'출처' 표기 없음.\n" +
            "[카드]\n" +
            "제목: 카드 이미지에 크게 들어갈 한 줄(22자 이내, 핵심 숫자 포함)\n" +
            "- 항목: 내용  ← 이런 줄 3~5개. 항목은 '대상', '시행일', '한도', '금리', '분양가', '일정', '지역', '변동', '신청', '주의' 같은 2~5자 낱말, 내용은 28자 이내. 본문에 있는 것만."
    }

    fun hasKey(ctx: Context) = Prefs(ctx).let { p -> p.geminiKey.isNotBlank() || p.groqKey.isNotBlank() || ExtraAi.ALL.any { p.aiKey(it.id).isNotBlank() } }

    /** 마지막 실패 사유 (키를 넣은 AI 중 맨 앞 것) */
    fun lastError(): String? = Gemini.lastError ?: Groq.lastError ?: ExtraAi.ALL.firstNotNullOfOrNull { it.lastError }

    /** Gemini → Groq → Cerebras → OpenRouter 순서로, 답이 나올 때까지 */
    suspend fun ask(ctx: Context, system: String, input: String): String? {
        Gemini.ask(ctx, system, input)?.let { return it }
        Groq.ask(ctx, system, input)?.let { return it }
        for (ai in ExtraAi.ALL) ai.ask(ctx, system, input)?.let { return it }
        return null
    }

    fun buildInput(post: Post, sourceName: String, body: String): String {
        val text = body.replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
        val lead = post.summary?.takeIf { it.isNotBlank() && !text.contains(it.take(30)) }?.let { "기사 앞부분: $it\n\n" }.orEmpty()
        return "매체: $sourceName\n제목: ${post.title}\n\n${lead}본문:\n${text.take(6000)}"
    }

    private fun cleanLine(l: String) = l.trim().trim('"', '“', '”', '*').trim()

    /** AI 응답을 스레드 글과 카드 내용으로 나눔. 형식이 어긋나도 최대한 살림 */
    fun parse(raw: String, fallbackTitle: String): Summary? {
        val text = raw.replace("\r", "").replace("**", "").trim()
        val cardAt = Regex("\\[\\s*카드\\s*]").find(text)
        val threadPart = (if (cardAt != null) text.substring(0, cardAt.range.first) else text).replace(Regex("\\[\\s*스레드\\s*]"), "")
        var thread = threadPart.lines().map { cleanLine(it).replace(Regex("^([-•·]|\\d+[.)])\\s*"), "") }
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
        if (headline.isEmpty()) headline = thread.lines().first().take(40).ifBlank { fallbackTitle }
        return Summary(thread, headline, points.take(6))
    }

    /** AI 없이 만드는 기본 내용: 제목 + 피드에 있던 기사 앞부분 */
    fun basic(post: Post): Summary {
        val sentences = post.summary.orEmpty().split(Regex("(?<=[.다요])\\s+")).map { it.trim() }.filter { it.length in 8..90 }.take(3)
        return Summary((listOf(post.title, "") + sentences).joinToString("\n").trim(), post.title, sentences)
    }

    suspend fun summarize(ctx: Context, post: Post, kind: String, sourceName: String, body: String): Summary? {
        if (!hasKey(ctx)) return null
        val system = system(kind, post.tags, Prefs(ctx).casualTone)
        val input = buildInput(post, sourceName, body)
        val raw = ask(ctx, system, input) ?: return null
        return parse(raw, post.title)
    }
}
