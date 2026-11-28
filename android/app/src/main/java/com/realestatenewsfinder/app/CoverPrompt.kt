package com.realestatenewsfinder.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 첫 이미지(표지)를 외부 AI(ChatGPT·Gemini 등)로 만들기 위한 프롬프트.
 * 앱이 규칙으로 만듦(AI 키 필요 없음): 글의 주제·지역·핵심 숫자는 그대로 넣고,
 * 그림 꼴·색·구도·장면은 날짜·글·계정·'다른 프롬프트'에 따라 바뀌어 표지가 매번 달라짐.
 */
object CoverPrompt {
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")

    val STYLES = listOf(
        "부드러운 조명의 3D 아이소메트릭 일러스트",
        "잡지 표지 같은 고급스러운 사진풍",
        "깔끔한 플랫 벡터 인포그래픽",
        "맑은 수채화풍 도시 풍경",
        "굵은 글자가 주인공인 미니멀 타이포 포스터",
        "해 질 녘 도시 야경 사진풍",
        "종이를 오려 붙인 콜라주",
        "말랑한 클레이 3D 미니어처",
    )
    val PALETTES = listOf(
        "남색과 금색", "하늘색과 흰색", "짙은 초록과 크림색", "주황과 남색 대비",
        "연한 회색과 빨간 포인트", "보라와 민트",
    )
    val LAYOUTS = listOf(
        "큰 제목은 위쪽 3분의 1에, 그림은 아래쪽에",
        "왼쪽에 큰 제목을 왼쪽 정렬, 오른쪽에 그림",
        "가운데에 핵심 숫자를 아주 크게, 제목은 그 위에",
        "그림을 화면 가득 깔고, 아래쪽 반투명 띠 위에 제목",
    )

    /** 주제별 장면 (두 가지씩, 번갈아 씀) */
    private fun scenes(all: String): List<String> = when {
        "경매" in all -> listOf("법원 경매 나무 망치 옆에 놓인 작은 아파트 모형과 서류 봉투", "법원 건물 계단 앞에서 올려다본 아파트 단지, 앞쪽에 경매 망치")
        Regex("월세|전세|전월세|임대|임차").containsMatchIn(all) -> listOf("열쇠 꾸러미와 달력이 놓인 밝은 오피스텔 창가", "현관문 앞에 걸린 열쇠와 집 모양 열쇠고리, 뒤로 고층 건물")
        Regex("분양|청약").containsMatchIn(all) -> listOf("모델하우스 단지 모형과 청약 신청서", "공사 중인 고층 건물 위로 뜬 크레인과 새 아파트 조감도")
        Regex("자금|대출|얼마|예산|금리").containsMatchIn(all) -> listOf("계산기와 지폐·동전 더미 옆의 작은 집 모형", "저울 한쪽에 집, 다른 쪽에 동전 더미")
        Regex("오픈채팅|정보방|오픈톡").containsMatchIn(all) -> listOf("스마트폰 채팅 화면에서 튀어나오는 집과 그래프 아이콘들", "여러 사람의 말풍선이 도시 위로 모이는 장면")
        Regex("변동률|지수|통계|지역별|전국|시도").containsMatchIn(all) -> listOf("한국 지도 윤곽 위로 솟은 막대그래프", "여러 도시 스카이라인 위에 겹쳐진 선 그래프")
        else -> listOf("고층 아파트 단지와 위아래로 움직이는 화살표 그래프", "돋보기로 들여다본 아파트 단지와 가격표")
    }

    /** 지역이 드러나는 배경 (실제 건물을 똑같이 그리지는 않게) */
    private fun place(all: String): String = when {
        Regex("평촌|관양|안양|한가람|공작부영|아크로타워|더퍼스트").containsMatchIn(all) -> "안양 평촌역 일대 고층 아파트·오피스텔이 있는 도시 풍경 (실제 건물을 똑같이 그리지 말고 분위기만)"
        else -> "한국 수도권 도시 풍경 (특정 건물을 똑같이 그리지 말 것)"
    }

    private val REGION_WORD = Regex("^(서울|경기|인천|부산|대구|대전|광주|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주|[가-힣]+(특별시|광역시|도|시|군|구|동|읍|면))$")

    /** 표지 큰 제목: 단지 이름·주제가 주인공. 앞에 붙은 지역 이름(시·구·동)은 빼고, 26자를 넘으면 낱말 단위로 자름(끝에 'vs'·'와' 같은 잇는 말은 남기지 않음) */
    fun headline(name: String): String {
        var t = name.replace(EMOJI, "").replace(Regex("\\(.*?\\)"), "").replace("·", " ").replace("|", " ").replace(Regex("\\s+"), " ").trim()
        val words = t.split(" ")
        val lead = words.takeWhile { REGION_WORD.matches(it) }.size
        if (lead in 1 until words.size) t = words.drop(lead).joinToString(" ")
        if (t.length <= MAX_HEAD) return t
        val out = mutableListOf<String>()
        for (w in t.split(" ")) { if (out.sumOf { it.length + 1 } + w.length > MAX_HEAD && out.isNotEmpty()) break; out += w }
        while (out.size > 1 && out.last().lowercase() in setOf("vs", "와", "과", "및", "대비", "그리고")) out.removeAt(out.size - 1)
        return out.joinToString(" ").ifBlank { t.take(MAX_HEAD) }
    }

    const val MAX_HEAD = 26

    /** 숫자 + 단위. '216~270만'처럼 범위도 하나로 */
    private val NUM = Regex("[+\\-−]?\\d[\\d,.]*(~\\d[\\d,.]*)?\\s*(억(\\s*\\d[\\d,]*만)?|만원?|%|건|곳|배)")

    const val MAX_FIG = 34

    /** 글에 적힌 자료 기준일: '2026-11-22', '2026.11.22', '2026년 11월 22일', '(11/22)' 꼴. 없으면 null */
    fun asOf(text: String, today: LocalDate): LocalDate? {
        Regex("(20\\d{2})[-.](\\d{1,2})[-.](\\d{1,2})").findAll(text).mapNotNull { m -> runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() }.filter { !it.isAfter(today) }.maxOrNull()?.let { return it }
        Regex("(20\\d{2})년 (\\d{1,2})월 (\\d{1,2})일").findAll(text).mapNotNull { m -> runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() }.filter { !it.isAfter(today) }.maxOrNull()?.let { return it }
        Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])").findAll(text).mapNotNull { m -> runCatching { LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()).let { d -> if (d.isAfter(today)) d.minusYears(1) else d } }.getOrNull() }.maxOrNull()?.let { return it }
        return null
    }

    /** 글에서 표지에 넣을 핵심 숫자 줄 (최대 2개, 줄마다 22자 안팎). 목록 줄(·, -)과 %·금액이 든 줄을 먼저 씀 */
    fun figures(text: String): List<String> {
        data class Line(val text: String, val listed: Boolean)
        val lines = text.replace(EMOJI, "").lines().map { raw ->
            val t = raw.trim()
            Line(t.trimStart('·', '-', '•', '✔', '■', '▶', '◆', '▣', '→', ' ').trim(), t.isNotEmpty() && t[0] in "·-•✔")
        }.filter { it.text.isNotEmpty() && NUM.containsMatchIn(it.text) && !it.text.startsWith("자료") && !it.text.startsWith("#") && !it.text.startsWith("http") && !it.text.startsWith("※") }
        fun short(l: String): String {
            // "라벨: 처음 값 → … → 마지막 값 (+14%)" 꼴은 라벨 + '처음 → 마지막' + 괄호 속 %. 비교(실거래 → 호가)가 둘 다 남게
            val label = l.substringBefore(':', "").trim().takeIf { ':' in l && it.length <= 12 }
            val rest = if (label != null) l.substringAfter(':') else l
            val pct = Regex("\\(([^)]*%[^)]*)\\)").find(rest)?.groupValues?.get(1)?.let { Regex("[+\\-−]?\\d[\\d.]*(~\\d[\\d.]*)?%").find(it)?.value }
            val plain = rest.replace(Regex("\\(.*?\\)"), "")
            val steps = plain.split('→').map { seg -> NUM.find(seg)?.value?.trim() }
            val value = if (steps.size >= 2 && steps.first() != null && steps.last() != null) "${steps.first()} → ${steps.last()}" else NUM.find(plain)?.value?.trim() ?: plain.trim().take(10)
            if (label != null) return listOfNotNull(label, value, pct?.let { "($it)" }).joinToString(" ").take(MAX_FIG)
            if (steps.size >= 2 && steps.first() != null && steps.last() != null) return "$value${pct?.let { " ($it)" }.orEmpty()}"
            if (l.length <= 22) return l
            // 쉼표·마침표로 자르되 숫자 속 점·쉼표(6.3억, 3,000만)는 자르지 않음
            val clause = l.split(Regex("(?<!\\d)[,.]|[,.](?!\\d)")).firstOrNull { NUM.containsMatchIn(it) }?.trim().orEmpty()
            if (clause.length in 1..22) return clause
            val m = NUM.find(l) ?: return l.take(22)
            val before = l.substring(0, m.range.first).split(Regex("[\\s,:(]+")).filter { it.isNotBlank() }.takeLast(3).joinToString(" ")
            return "$before ${m.value.trim()}".trim().take(24)
        }
        // 변화를 말하는 줄(→, 올랐다, 차이…)과 %가 든 줄이 먼저. 점수가 같으면 뒤쪽(최근) 줄이 먼저
        val change = Regex("→|올|오른|내렸|내린|떨어|벌어|차이|보다|상승|하락|증가|감소|뛰")
        fun score(l: Line) = (if (change.containsMatchIn(l.text)) 3 else 0) + (if ("%" in l.text) 2 else 0) + (if ("억" in l.text || "만" in l.text) 1 else 0) + (if (l.listed) 1 else 0)
        val ranked = lines.withIndex().sortedWith(compareByDescending<IndexedValue<Line>> { score(it.value) }.thenByDescending { it.index }).map { it.value }
        return ranked.map { short(it.text).trimEnd('.', '!', '?', ' ') }.distinct().take(2)
    }

    /** 표지 프롬프트. seed가 바뀌면 그림 꼴·색·구도·장면이 바뀜 */
    /** title: 표지 큰 제목이 될 글 제목(단지 이름·주제). context: 지역 등 배경을 고를 때만 쓰는 글 이름 */
    /** asOf: 자료 기준일. 시세 정보는 기준일이 가장 중요해서 작게라도 꼭 넣음 */
    fun build(title: String, text: String, seed: Int, context: String = "", asOf: LocalDate? = null): String {
        val all = "$title\n$context\n$text"
        val s = Math.floorMod(seed, 1_000_003)
        val style = STYLES[s % STYLES.size]
        val palette = PALETTES[(s / STYLES.size) % PALETTES.size]
        val layout = LAYOUTS[(s / 7) % LAYOUTS.size]
        val scene = scenes(all)[(s / 3) % 2]
        val head = headline(title)
        val nums = figures(text)
        return buildString {
            appendLine("부동산 정보 계정에 올릴 표지 이미지 1장을 만들어 줘.")
            appendLine()
            appendLine("[크기] 세로 4:5 (1080×1350)")
            appendLine("[그림 꼴] $style")
            appendLine("[색] $palette 중심")
            appendLine("[장면] $scene. 배경은 ${place(all)}")
            appendLine("[구도] $layout")
            appendLine()
            appendLine("[이미지 안 글자] 아래 줄만, 띄어쓰기와 숫자까지 정확히 그대로 넣어 줘.")
            appendLine("- 큰 제목 (가장 크게, 이미지의 주인공): \"$head\"")
            nums.forEach { appendLine("- 작은 글씨: \"$it\"") }
            if (asOf != null) appendLine("- 기준일 (작게, 한쪽 구석에 꼭): \"${asOf.year}.${"%02d".format(asOf.monthValue)}.${"%02d".format(asOf.dayOfMonth)} 기준\"")
            appendLine("그 밖의 글자, 영어, 지어낸 숫자, 로고, 워터마크는 넣지 마. 지역 이름(시·구·동)도 글자로 넣지 마.")
            appendLine()
            appendLine("[지킬 것]")
            appendLine("- 오른쪽 아래 모서리(가로 40%, 세로 10%)는 비워 둬. 계정 표시가 들어가.")
            appendLine("- 휴대폰 화면에서 작게 봐도 제목이 바로 읽히게 크게.")
            if (asOf != null) appendLine("- 기준일은 작아도 빠뜨리지 마. 시세 정보는 언제 기준인지가 가장 중요해.")
            if (nums.any { "→" in it }) appendLine("- '→' 앞뒤 숫자는 둘 다 넣어 비교가 보이게 해 줘.")
            appendLine("- 믿음이 가는 정보 표지 느낌. 공포·과장 표현, 실존 회사 로고는 쓰지 마.")
        }.trim()
    }

    fun seed(key: String, account: Int, day: LocalDate, shift: Int) = key.hashCode() + day.toEpochDay().toInt() * 31 + account * 17 + shift * 101

    const val W = 1080
    const val H = 1350

    /** 첨부한 이미지를 4:5로 가운데 맞춰 자르고 1080×1350으로 줄임 (휴대폰 사진 방향 반영) */
    fun load(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val src = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > 2600) d.setTargetSampleSize(big / 2000)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        } else ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        fit(src)
    }.getOrNull()

    fun fit(src: Bitmap): Bitmap {
        val want = W.toFloat() / H
        val have = src.width.toFloat() / src.height
        val crop = if (have > want) { val w = (src.height * want).toInt(); Rect((src.width - w) / 2, 0, (src.width + w) / 2, src.height) }
            else { val h = (src.width / want).toInt(); Rect(0, (src.height - h) / 2, src.width, (src.height + h) / 2) }
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, crop, Rect(0, 0, W, H), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** 첨부 이미지에 계정 표시를 얹음 (원본은 그대로 두고 새 그림으로) */
    fun withMark(img: Bitmap, mark: Watermark.Mark): Bitmap {
        val out = img.copy(Bitmap.Config.ARGB_8888, true)
        Watermark.corner(Canvas(out), mark, out.width, out.height)
        return out
    }

    /** 프롬프트를 다른 앱(ChatGPT·Gemini 등)으로 보내기 */
    fun share(ctx: Context, prompt: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prompt)
        runCatching { ctx.startActivity(Intent.createChooser(send, "프롬프트 보낼 앱 고르기")) }
            .onFailure { Toast.makeText(ctx, "보낼 앱을 열지 못했어요. 복사해서 붙여넣기 하세요.", Toast.LENGTH_SHORT).show() }
    }
}

/**
 * 첫 이미지 만들기 칸: 프롬프트(누르면 복사) → 외부 AI에서 만든 이미지 첨부.
 * 첨부하면 그 이미지가 1번이 되고, 계정 표시가 오른쪽 아래에 얹힘.
 */
@Composable
fun CoverBox(prompt: String, attached: Bitmap?, onAttach: (Bitmap) -> Unit, onClear: () -> Unit, onNext: () -> Unit, replaces: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var loading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            val b = withContext(Dispatchers.IO) { CoverPrompt.load(ctx, uri) }
            loading = false
            if (b != null) onAttach(b) else Toast.makeText(ctx, "이미지를 읽지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("첫 이미지 직접 만들기", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
        Text("아래 글을 누르면 복사돼요. ChatGPT·Gemini 같은 AI에 붙여넣어 만든 뒤 '이미지 첨부'로 넣으세요.", fontSize = 12.sp, lineHeight = 17.sp, color = muted)
        Spacer(Modifier.height(8.dp))
        Text(
            prompt, fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { ThreadsKit.copy(ctx, "프롬프트", prompt); if (Build.VERSION.SDK_INT >= 33) Toast.makeText(ctx, "프롬프트를 복사했어요", Toast.LENGTH_SHORT).show() }.padding(12.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onNext) { Text("다른 프롬프트", fontSize = 13.sp) }
            TextButton(onClick = { CoverPrompt.share(ctx, prompt) }) { Text("AI 앱으로 보내기", fontSize = 13.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !loading, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(if (loading) "읽는 중…" else if (attached == null) "이미지 첨부" else "다른 이미지로")
            }
            if (attached != null) OutlinedButton(onClick = onClear) { Text("첨부 빼기") }
        }
        Text(
            if (attached != null) "첨부한 이미지가 1번이에요 (4:5로 맞추고 계정 표시를 얹었어요)." + if (replaces) " 앱이 만든 표지는 뺐어요." else ""
            else "첨부하면 1번 이미지가 돼요." + if (replaces) " 앱이 만든 표지는 빠져요." else " 앱이 만든 이미지는 그 뒤에 그대로 붙어요.",
            fontSize = 12.sp, lineHeight = 17.sp, color = muted, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
