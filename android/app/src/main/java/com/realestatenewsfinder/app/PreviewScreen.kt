package com.realestatenewsfinder.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 글 주소를 모바일 버전 주소로 변환 (커뮤니티만. 언론사·정부 사이트는 같은 주소가 모바일 화면) */
fun mobileUrl(post: Post): String {
    val u = post.url
    fun m(re: String) = Regex(re).find(u)?.groupValues
    return when (post.source) {
        "dcinside" -> m("[?&]id=([^&]+).*[?&]no=(\\d+)")?.let { "https://m.dcinside.com/board/${it[1]}/${it[2]}" }
        "clien" -> m("www\\.clien\\.net(/.*)")?.let { "https://m.clien.net${it[1]}" }
        "ppomppu" -> m("[?&]id=([^&]+).*[?&]no=(\\d+)")?.let { "https://m.ppomppu.co.kr/new/bbs_view.php?id=${it[1]}&no=${it[2]}" }
        "navercafe" -> m("cafe\\.naver\\.com/([^/?]+)/(\\d+)")?.let { "https://m.cafe.naver.com/${it[1]}/${it[2]}" }
        else -> null
    } ?: u
}

/** 글 미리보기: 모바일 페이지 스캔 이미지 + 아래 "해당 글로 이동" */
@Composable
fun PreviewScreen(post: Post, source: Source?, onExport: () -> Unit, onOpenSite: () -> Unit, onBack: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(attempt) { mutableStateOf<ScanState>(ScanState.Loading(0, "페이지 여는 중")) }
    DisposableEffect(Unit) { onDispose { (state as? ScanState.Done)?.result?.bitmap?.recycle() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                Column(Modifier.weight(1f)) {
                    Text(post.outlet ?: source?.name ?: post.source, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = source?.let { Color(it.color) } ?: Color.Gray)
                    Text(post.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onExport) { Text("스레드용 만들기", fontWeight = FontWeight.Bold) }
            }
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                GradientButton("원문 보기", Icons.AutoMirrored.Filled.OpenInNew, onOpenSite)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            PageScanner(post, attempt) { state = it }
            ScanStatus(state, onRetry = { attempt++ }) { ScanImage(it.bitmap, it.truncated) }
        }
    }
}

@Composable
fun GradientButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, shape = CircleShape, contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().height(52.dp).background(Brush.horizontalGradient(listOf(Color(0xFF4F8BFF), Brand)), CircleShape),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (icon != null) { Spacer(Modifier.width(6.dp)); Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

/** 스캔 진행/실패 화면, 완료되면 content 표시 */
@Composable
fun ScanStatus(state: ScanState, onRetry: () -> Unit, content: @Composable (ScanResult) -> Unit) {
    when (state) {
        is ScanState.Loading -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("글 불러오는 중… ${state.progress}%", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(state.step, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ScanState.Failed -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.message, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("다시 스캔") }
        }
        is ScanState.Done -> content(state.result)
    }
}

/** 세로 스크롤 + 두 손가락 확대 */
@Composable
private fun ScanImage(bitmap: Bitmap, truncated: Boolean) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 3f)
                    val maxX = size.width * (scale - 1) / 2
                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                    if (scale == 1f) offsetX = 0f
                }
            }
            .verticalScroll(rememberScrollState()),
    ) {
        Image(
            image, "글 스캔 이미지",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp)),
        )
        if (truncated) {
            Text(
                "글이 길어서 앞부분만 스캔했어요. 전체는 아래 \"원문 보기\"에서 보세요.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
