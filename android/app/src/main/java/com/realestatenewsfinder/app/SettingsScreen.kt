package com.realestatenewsfinder.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 설정 화면 (카드형 섹션) */
@Composable
fun SettingsScreen(feed: Feed?, onBack: (() -> Unit)? = null, onSetup: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var enabled by remember { mutableStateOf(prefs.notifyEnabled && HotPostWorker.canNotify(ctx)) }
    var minScore by remember { mutableFloatStateOf(prefs.minScore.toFloat()) }
    var keywords by remember { mutableStateOf(prefs.keywordsText) }
    var muted by remember { mutableStateOf(prefs.mutedSources) }
    var interval by remember { mutableIntStateOf(prefs.intervalMin) }
    var feedUrl by remember { mutableStateOf(prefs.feedUrl) }
    var casual by remember { mutableStateOf(prefs.casualTone) }
    var handle by remember { mutableStateOf(prefs.cardHandle) }
    var geminiKey by remember { mutableStateOf(prefs.geminiKey) }
    var geminiStatus by remember { mutableStateOf<String?>(null) }
    var groqKey by remember { mutableStateOf(prefs.groqKey) }
    var groqStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    fun apply() {
        prefs.notifyEnabled = enabled
        prefs.minScore = minScore.toInt()
        prefs.keywordsText = keywords
        prefs.mutedSources = muted
        prefs.intervalMin = interval
        HotPostWorker.schedule(ctx)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = granted
        message = if (granted) null else "알림 권한이 거부됐어요. 휴대폰 설정 > 앱 > 부동산뉴스파인더에서 알림을 허용해주세요."
        apply()
    }

    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(bg)) {
        // 상단 배경: 은은한 보라 그라데이션 + 큰 원
        Box(
            Modifier.fillMaxWidth().height(200.dp)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), bg))),
        )
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-70).dp).size(240.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), CircleShape),
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
            // 헤더
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp).height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", modifier = Modifier.size(26.dp)) }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text("설정", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).padding(start = 6.dp))
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 화제글 알림
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("새 소식 알림", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "새 글이 수집되면,\n아래 조건에 맞는 글만 알려드려요.",
                                fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PurpleSwitch(enabled) { on ->
                            if (on && Build.VERSION.SDK_INT >= 33 && !HotPostWorker.canNotify(ctx)) {
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                enabled = on
                                apply()
                            }
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }

                // 화제도 기준
                SettingsCard {
                    SectionTitle(Icons.Filled.BarChart, "중요도 ${minScore.toInt()}점 이상")
                    Spacer(Modifier.height(4.dp))
                    ScoreSlider(minScore, { minScore = it }, ::apply)
                    Spacer(Modifier.height(6.dp))
                    Text("중요도 = 최신성 + 여러 매체가 함께 다룬 정도 + 사람들이 궁금해하는 내용(대상·시행일·금액)", fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 키워드 알림
                SettingsCard {
                    SectionTitle(Icons.Filled.Keyboard, "키워드 알림")
                    Spacer(Modifier.height(12.dp))
                    OutlinedPillField(keywords, { keywords = it; apply() }, "예: 청약, 토지거래허가, 분당", 56.dp, 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("쉼표로 구분 · 제목에 들어가면 점수와 상관없이 알림", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 알림 받을 커뮤니티
                SettingsCard {
                    SectionTitle(Icons.Filled.CheckCircle, "알림 받을 출처")
                    Spacer(Modifier.height(6.dp))
                    val sources = feed?.sources.orEmpty()
                    if (sources.isEmpty()) {
                        Text("피드를 받은 뒤에 선택할 수 있어요", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    sources.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                muted = if (s.id in muted) muted - s.id else muted + s.id
                                apply()
                            }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PurpleCheck(s.id !in muted)
                            Spacer(Modifier.width(14.dp))
                            Text("${s.name} · ${s.board}", fontSize = 16.sp)
                        }
                    }
                }

                // 요약 AI 1순위
                SettingsCard {
                    AccentTitle("스레드 글 요약 (Gemini 무료 키)")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "키를 넣으면 기사·보도자료를 읽고 스레드 글과 카드 내용을 만들어줘요. 무료 한도를 다 쓰면 자동으로 Groq으로 바뀌고, 1시간 뒤 다시 Gemini를 써요. 키가 없으면 제목과 기사 앞부분만 들어가요.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedPillField(geminiKey, { geminiKey = it; prefs.geminiKey = it; geminiStatus = null }, "API 키 붙여넣기 (AIza…)", 50.dp, 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(shape = CircleShape, onClick = {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey")))
                        }) { Text("키 발급 페이지", fontWeight = FontWeight.Bold) }
                        Button(shape = CircleShape, enabled = geminiKey.isNotBlank(), onClick = {
                            geminiStatus = "확인 중…"
                            scope.launch {
                                prefs.geminiCooldownUntil = 0
                                val r = Gemini.ask(ctx, "한 문장으로만 답해라.", "'연결 확인'이라고만 답해줘.")
                                geminiStatus = if (r != null) "✅ 연결됨 (${prefs.geminiModel})" else "⚠ ${Gemini.lastError ?: "실패"}"
                            }
                        }) { Text("연결 테스트", fontWeight = FontWeight.Bold) }
                    }
                    geminiStatus?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, lineHeight = 18.sp) }
                    if (Gemini.inCooldown(prefs)) {
                        Spacer(Modifier.height(6.dp))
                        Text("지금은 Gemini 무료 한도를 다 써서 Groq을 쓰고 있어요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                // 요약 AI 2순위
                SettingsCard {
                    AccentTitle("예비 요약 1 (Groq 무료 키)")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Gemini 한도가 떨어지면 Groq(무료 AI)으로 넘어가요.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedPillField(groqKey, { groqKey = it; prefs.groqKey = it; groqStatus = null }, "API 키 붙여넣기 (gsk_…)", 50.dp, 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(shape = CircleShape, onClick = {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.groq.com/keys")))
                        }) { Text("키 발급 페이지", fontWeight = FontWeight.Bold) }
                        Button(shape = CircleShape, enabled = groqKey.isNotBlank(), onClick = {
                            groqStatus = "확인 중…"
                            scope.launch {
                                prefs.groqCooldownUntil = 0
                                val r = Groq.ask(ctx, "한 문장으로만 답해라.", "'연결 확인'이라고만 답해줘.")
                                groqStatus = if (r != null) "✅ 연결됨 (${prefs.groqModel})" else "⚠ ${Groq.lastError ?: "실패"}"
                            }
                        }) { Text("연결 테스트", fontWeight = FontWeight.Bold) }
                    }
                    groqStatus?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, lineHeight = 18.sp) }
                }

                // 요약 AI 3·4순위 (Cerebras, OpenRouter)
                ExtraAi.ALL.forEachIndexed { i, ai ->
                    var key by remember { mutableStateOf(prefs.aiKey(ai.id)) }
                    var status by remember { mutableStateOf<String?>(null) }
                    SettingsCard {
                        AccentTitle("예비 요약 ${i + 2} (${ai.label} 무료 키)")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "앞 순서 AI가 모두 한도를 다 썼을 때 써요. 넣어두면 요약이 멈추는 일이 줄어요. 순서: Gemini → Groq → Cerebras → OpenRouter",
                            fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedPillField(key, { key = it; prefs.setAiKey(ai.id, it); status = null }, ai.keyHint, 50.dp, 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(shape = CircleShape, onClick = {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ai.keyUrl)))
                            }) { Text("키 발급 페이지", fontWeight = FontWeight.Bold) }
                            Button(shape = CircleShape, enabled = key.isNotBlank(), onClick = {
                                status = "확인 중…"
                                scope.launch {
                                    prefs.setAiCooldown(ai.id, 0)
                                    val r = ai.ask(ctx, "한 문장으로만 답해라.", "'연결 확인'이라고만 답해줘.")
                                    status = if (r != null) "✅ 연결됨 (${prefs.aiModel(ai.id)})" else "⚠ ${ai.lastError ?: "실패"}"
                                }
                            }) { Text("연결 테스트", fontWeight = FontWeight.Bold) }
                        }
                        status?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, lineHeight = 18.sp) }
                    }
                }

                // 스레드 글 스타일
                SettingsCard {
                    AccentTitle("스레드 글 스타일")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntervalPill("존댓말", !casual) { casual = false; prefs.casualTone = false }
                        IntervalPill("반말", casual) { casual = true; prefs.casualTone = true }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("카드 이미지에 넣을 내 계정 이름 (비워두면 표시 안 함)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedPillField(handle, { handle = it; prefs.cardHandle = it }, "예: @my_threads", 50.dp, 15.sp)
                }

                // 확인 주기
                SettingsCard {
                    AccentTitle("확인 주기")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { m -> IntervalPill("${m}분", interval == m) { interval = m; apply() } }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "안드로이드 배터리 정책상 최소 15분이며, 절전 모드에서는 더 늦어질 수 있어요.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        onClick = {
                            val top = feed?.posts?.maxByOrNull { it.score }
                            if (!HotPostWorker.canNotify(ctx)) {
                                message = "먼저 새 소식 알림을 켜주세요."
                            } else if (feed != null && top != null) {
                                HotPostWorker.notify(ctx, feed, listOf(top))
                            }
                        },
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("테스트 알림 보내기", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                if (onSetup != null) {
                    SettingsCard {
                        AccentTitle("처음 설정 다시 보기")
                        Spacer(Modifier.height(8.dp))
                        Text("알림 · 배터리 · 스레드 자동 입력(접근성) 설정을 순서대로 안내해요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onSetup, shape = CircleShape) { Text("설정 안내 열기", fontWeight = FontWeight.Bold) }
                    }
                }

                // 고급
                SettingsCard {
                    AccentTitle("고급: 클라우드 피드 주소")
                    Spacer(Modifier.height(12.dp))
                    OutlinedPillField(feedUrl, { feedUrl = it; prefs.feedUrl = it }, "", 50.dp, 13.sp)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x222563EB), spotColor = Color(0x222563EB))
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun AccentTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(20.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun PurpleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun PurpleCheck(checked: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
            .background(if (checked) primary else Color.Transparent)
            .border(2.dp, if (checked) primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun IntervalPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.width(84.dp).height(44.dp)
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF4F8BFF), primary)), CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun OutlinedPillField(value: String, onChange: (String) -> Unit, placeholder: String, height: androidx.compose.ui.unit.Dp, size: androidx.compose.ui.unit.TextUnit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.fillMaxWidth().height(height)
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = muted.copy(alpha = 0.8f), fontSize = size)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(fontSize = size, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 0~100점 슬라이더 + 아래 눈금 라벨 (현재 값이 손잡이 아래에 보라색으로 표시) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreSlider(value: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Column {
        Slider(
            value = value, onValueChange = { onChange(Math.round(it).toFloat()) }, onValueChangeFinished = onDone,
            valueRange = 0f..100f, steps = 19, // 5점 단위
            modifier = Modifier.onSizeChanged { widthPx = it.width },
            colors = SliderDefaults.colors(
                thumbColor = primary, activeTrackColor = primary, inactiveTrackColor = primary.copy(alpha = 0.15f),
                activeTickColor = Color.White, inactiveTickColor = primary.copy(alpha = 0.5f),
            ),
        )
        Box(Modifier.fillMaxWidth().height(20.dp)) {
            Text("0점", fontSize = 13.sp, color = muted, modifier = Modifier.align(Alignment.CenterStart))
            Text("100점", fontSize = 13.sp, color = muted, modifier = Modifier.align(Alignment.CenterEnd))
            if (value in 8f..92f && widthPx > 0) {
                val x = with(density) { (widthPx * value / 100f).toDp() } - 18.dp
                Text("${value.toInt()}점", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = primary, modifier = Modifier.offset(x = x))
            }
        }
    }
}
