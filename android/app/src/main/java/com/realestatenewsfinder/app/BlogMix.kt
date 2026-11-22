package com.realestatenewsfinder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 블로그 글이 "숫자만 바뀐 같은 글"로 보이지 않게 하는 장치.
 * - 틀 돌려쓰기: 소제목 문구·기호·제목 꼴·의견 위치가 다른 틀 4가지를 날짜·글·계정에 따라 고름
 * - 계정별 다른 틀: 두 계정은 같은 날 같은 글이라도 항상 다른 틀
 * - 중개사 한마디: 직접 쓴 2~3줄을 본문 단락으로 넣음
 * - 평가: 숫자가 든 단락 뒤에 AI가 쓴 한 줄 풀이를 넣음 (단락에 없는 숫자가 들어간 평가는 버림)
 */
object BlogMix {
    data class Style(
        val mark: String,
        /** 숫자 단락 / 맺음말 / 참고 소제목 */
        val nums: String, val close: String, val refs: String,
        val opinion: String,
        /** 내 의견을 첫 문단 바로 뒤에 둘지(아니면 참고 앞) */
        val opinionFirst: Boolean,
        val evalMark: String,
        val disclaimer: String,
        /** 참고보다 자료 출처를 먼저 쓸지 */
        val sourceFirst: Boolean,
    )

    val STYLES = listOf(
        Style("■", "숫자로 보는 핵심", "마무리", "참고", "중개사 한마디", true, "→ ",
            "숫자는 공개된 통계·실거래 자료를 그대로 옮긴 것이고, 사거나 팔라는 권유가 아닙니다.", false),
        Style("▶", "핵심 숫자 정리", "정리하며", "알아둘 점", "현장에서 본 생각", false, "▷ ",
            "공개 자료의 숫자를 정리한 글이며 매수·매도를 권하는 글이 아닙니다.", true),
        Style("◆", "자세히 살펴보기", "한 줄 정리", "읽기 전에 확인", "제 생각은 이렇습니다", true, "풀이: ",
            "실거래·통계 원자료를 옮겨 적은 것으로, 투자 판단은 직접 확인한 뒤에 하시기 바랍니다.", false),
        Style("▣", "자료로 확인하기", "끝으로", "참고 사항", "중개 현장 메모", false, "한마디로, ",
            "위 숫자는 공개 자료 기준이고, 거래를 권유하려는 뜻은 없습니다.", true),
    )
    private val MARKS = STYLES.map { it.mark }
    private val REFS = STYLES.map { it.refs } + "참고 자료"

    /** 틀 번호. 두 계정은 2칸 떨어져 있어 항상 다름. shift는 '다른 틀로' 누른 횟수 */
    fun styleIndex(key: String, account: Int, day: LocalDate, shift: Int = 0): Int =
        (Math.floorMod(key.hashCode() + day.toEpochDay().toInt() + shift, STYLES.size) + (if (account == 1) 2 else 0)) % STYLES.size

    fun style(i: Int) = STYLES[Math.floorMod(i, STYLES.size)]

    private fun isHeading(block: String) = MARKS.any { block.startsWith("$it ") } && !block.contains('\n')
    private fun blocks(body: String) = body.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    private fun isTail(b: String) = b.startsWith("#") || b.startsWith("자료:") || b.startsWith("원문 보기") || b.startsWith("참고 자료")

    /** 써 둔 글(보고서·내 단지·AI 글)의 소제목 기호를 그 틀의 기호로 바꿈 */
    fun restyle(body: String, style: Int): String {
        val m = style(style).mark
        return body.lines().joinToString("\n") { l -> MARKS.firstOrNull { l.startsWith("$it ") }?.let { m + l.removePrefix(it) } ?: l }
    }

    /** 참고·출처·태그가 시작되는 자리 (없으면 끝) */
    private fun tailStart(bs: List<String>): Int {
        val refs = bs.indexOfLast { b -> isHeading(b) && REFS.any { b.endsWith(" $it") } }
        val tail = bs.indexOfFirst { isTail(it) }
        return listOf(refs, tail).filter { it >= 0 }.minOrNull() ?: bs.size
    }

    /** 직접 쓴 의견을 본문 단락으로 넣음. 비어 있으면 그대로 */
    fun withOpinion(body: String, opinion: String, style: Int): String {
        val op = opinion.trim()
        if (op.isEmpty()) return body
        val s = style(style)
        val bs = blocks(body).toMutableList()
        if (bs.isEmpty()) return op
        val at = if (s.opinionFirst) 1.coerceAtMost(tailStart(bs).coerceAtLeast(1)) else tailStart(bs)
        bs.addAll(at.coerceIn(0, bs.size), listOf("${s.mark} ${s.opinion}", op))
        return bs.joinToString("\n\n")
    }

    // ---------- 평가 ----------
    const val MAX_EVALS = 5

    /** 이미 평가(→ 줄 등)가 들어 있는 글인지. 보고서처럼 사람이 평가를 써 둔 글에는 AI 평가를 더하지 않음 */
    fun hasEvals(body: String): Boolean = blocks(body).any { b -> STYLES.any { b.startsWith(it.evalMark) } }

    /** 평가를 붙일 단락의 위치: 숫자가 든 본문 단락 (소제목·참고·출처·태그·이미 붙은 평가 제외) */
    fun evalTargets(body: String): List<Int> {
        val bs = blocks(body)
        val end = tailStart(bs)
        val evalMarks = STYLES.map { it.evalMark }
        fun isEval(b: String?) = b != null && evalMarks.any { b.startsWith(it) }
        // 이미 평가가 달린 단락(보고서처럼 써 둔 글)에는 또 붙이지 않음
        return bs.indices.filter { i -> i < end && !isHeading(bs[i]) && bs[i].length >= 15 && bs[i].any(Char::isDigit) && !isEval(bs[i]) && !isEval(bs.getOrNull(i + 1)) }.take(MAX_EVALS)
    }

    /** AI에게 줄 입력: "[1] 단락" 꼴 */
    fun evalInput(body: String): String {
        val bs = blocks(body)
        return evalTargets(body).mapIndexed { n, i -> "[${n + 1}] ${bs[i]}" }.joinToString("\n\n")
    }

    const val EVAL_SYSTEM = "너는 현장에서 일하는 공인중개사다. 입력은 블로그에 올릴 부동산 글의 단락들이고, 단락마다 [번호]가 붙어 있다.\n" +
        "단락마다 그 숫자가 읽는 사람에게 무슨 뜻인지 평가를 한 문장(40~90자)으로 쓴다.\n\n" +
        "[규칙 — 어기면 안 됨]\n" +
        "- 그 단락에 있는 숫자만 쓴다. 새 숫자를 만들거나 계산하지 않는다. 숫자를 안 써도 된다.\n" +
        "- 숫자를 다시 나열하지 말고 해석한다: 높은 편인지 낮은 편인지, 차이가 큰지 작은지, 흐름이 이어지는지 바뀌었는지, 무엇을 더 확인해야 하는지.\n" +
        "- 사라·팔아라·기다려라 같은 권유와 앞으로 오른다·내린다 같은 전망은 쓰지 않는다.\n" +
        "- 단락만으로 판단할 근거가 부족하면 그 번호는 건너뛴다.\n" +
        "- 단락마다 문장 꼴을 다르게 쓴다. 읽기 편한 존댓말. 이모지·굵게 표시·화살표는 쓰지 않는다.\n\n" +
        "[출력 형식] 한 줄에 하나씩, 머리말 없이.\n[1] 평가 문장\n[2] 평가 문장"

    /** AI 응답 → 번호별 평가. 단락에 없는 숫자가 든 평가와 권유는 버림 */
    fun parseEvals(raw: String, body: String): Map<Int, String> {
        val bs = blocks(body)
        val targets = evalTargets(body)
        return Regex("^\\s*\\[(\\d+)]\\s*(.+)$", RegexOption.MULTILINE).findAll(raw.replace("**", "")).mapNotNull { m ->
            val n = m.groupValues[1].toInt()
            val t = m.groupValues[2].trim().removePrefix("평가:").trim()
            val block = targets.getOrNull(n - 1)?.let { bs[it] } ?: return@mapNotNull null
            if (t.length < 8 || Summarizer.unsupportedNumbers(t, block, lenient = true).isNotEmpty()) null else n to t.take(160)
        }.toMap()
    }

    /** 평가를 해당 단락 바로 뒤에 넣음 */
    fun withEvals(body: String, evals: Map<Int, String>, style: Int): String {
        if (evals.isEmpty()) return body
        val bs = blocks(body)
        val at = evalTargets(body).withIndex().associate { (n, i) -> i to evals[n + 1] }
        val mark = style(style).evalMark
        return bs.indices.joinToString("\n\n") { i -> at[i]?.let { bs[i] + "\n\n" + mark + it } ?: bs[i] }
    }
}

/**
 * 블로그로 보낸 기록 (글 → 계정·시각). 같은 자료를 다른 계정 블로그에 또 올리려 하면 한 번 알려 줌.
 * 같은 글이 두 블로그에 있으면 한쪽이 비슷한 문서로 묶여 검색에서 빠질 수 있어서.
 */
object BlogLog {
    private const val KEY = "blogSent"
    private const val MAX = 300
    const val DAYS = 30

    data class Sent(val key: String, val account: Int, val at: Long)

    fun parse(json: String?): List<Sent> {
        val a = runCatching { JSONArray(json.orEmpty()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Sent(it.optString("k"), it.optInt("a"), it.optLong("t")) }.filter { it.key.isNotEmpty() }
    }

    fun toJson(list: List<Sent>): String = JSONArray().also { a -> list.sortedByDescending { it.at }.take(MAX).forEach { a.put(JSONObject().put("k", it.key).put("a", it.account).put("t", it.at)) } }.toString()

    /** 최근 DAYS일 안에 다른 계정으로 같은 글을 보낸 기록 */
    fun otherAccount(list: List<Sent>, key: String, account: Int, now: Long): Sent? =
        list.filter { it.key == key && it.account != account && now - it.at < DAYS * 86_400_000L }.maxByOrNull { it.at }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun otherAccount(ctx: Context, key: String, account: Int): Sent? = otherAccount(parse(sp(ctx).getString(KEY, null)), key, account, System.currentTimeMillis())
    fun mark(ctx: Context, key: String, account: Int) {
        val list = parse(sp(ctx).getString(KEY, null)).filterNot { it.key == key && it.account == account } + Sent(key, account, System.currentTimeMillis())
        sp(ctx).edit().putString(KEY, toJson(list)).apply()
    }
}
