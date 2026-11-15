package com.realestatenewsfinder.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 스캔 결과. 좌표는 모두 bitmap 픽셀 기준 */
data class ScanResult(
    val bitmap: Bitmap,
    val truncated: Boolean,
    val contentRect: Rect?,
    /** 기사 제목 위치 (스레드용 첫 이미지: 제목~요약 부분 캡처에 사용) */
    val titleRect: Rect?,
    val imageRects: List<Rect>,
    /** 본문 글자 (요약 입력용) */
    val text: String,
    /** 실제로 열린 주소 (구글 뉴스 주소는 언론사 기사 주소로 바뀜) */
    val finalUrl: String? = null,
)

sealed interface ScanState {
    data class Loading(val progress: Int, val step: String) : ScanState
    data class Done(val result: ScanResult) : ScanState
    data class Failed(val message: String) : ScanState
}

private const val MAX_SCAN_HEIGHT_PX = 12000 // 메모리 보호: 너무 긴 글은 앞부분만

/** 본문 영역 선택자: 커뮤니티는 사이트별, 그 외는 언론사·정부 사이트에서 흔히 쓰는 것. 앞에서부터 먼저 맞는 것을 사용 */
private const val ARTICLE_SELECTORS = "[itemprop='articleBody'], #articleBody, #article-view-content-div, #newsct_article, #articeBody, #article_body, #articleBodyContents, " +
    ".article_body, .article-body, .articleBody, .news_body, .news_cnt_detail_wrap, .article_view, .article-view, .view_con, .view_cont, .art_txt, .article_txt, .news_view, .story-news, .article_cont, .view_txt"
val BODY_SELECTORS = mapOf(
    "dcinside" to ".thum-txtin, .thum-txt, .gallview-thum, .write_div",
    "clien" to ".post_article, .post_content, .post_view",
    "ppomppu" to "#KH_Content, .bbs.view .cont, .board-contents, td.board-contents",
    "navercafe" to ".se-main-container, #postContent, .post_cont, .ArticleContentBox, .content_area",
)

/** 캡처 전에 광고와 댓글 영역을 숨김 */
private const val HIDE_ADS_JS = """
(function(){try{
  document.querySelectorAll('ins.adsbygoogle,iframe:not([src*="youtube"]):not([src*="youtu.be"]),[id^="google_ads"],[id^="div-gpt-ad"],[class*="adsbygoogle"],[class*="coupang"],[id*="coupang"],a[href*="coupang.com"],a[href*="link.coupang"]').forEach(function(e){e.style.setProperty('display','none','important');});
  document.querySelectorAll('#comment,#comments,#cmtPosition,#cmt_list,.comment_wrap,.comment-wrap,.comment_box,.comment-box,.all-comment,.all-comment-lst,.view_comment,.comment_view,.post_comment,.comment_list,.comment-list,.cmt_list,.cmt_area,.reply_list,.reply-list,.reply-wrap,.replylist,.comment_container,.comments,[id^="comment_"],[class*="comment_list"],[class*="commentList"]').forEach(function(e){e.style.setProperty('display','none','important');});
  // 공유·글자크기·구글 뉴스 구독 버튼 등 기사 머리 부분의 잡다한 버튼 숨김
  document.querySelectorAll('[class*="share"],[class*="Share"],[class*="sns"],[class*="SNS"],[class*="font_size"],[class*="fontsize"],[class*="print"],[class*="scrap"],[class*="bookmark"],[class*="kakao"],[class*="google"],[class*="Google"],a[href*="news.google.com"],a[href*="google.com/preferences"],a[href*="profile.google.com"]').forEach(function(e){
    var r=e.getBoundingClientRect(); if(r.height<140) e.style.setProperty('display','none','important');});
}catch(x){}})()
"""

private fun analyzeJs(title: String, bodySel: String) = """
(function(){
  var d=document.documentElement, vw=d.clientWidth||window.innerWidth, sx=window.scrollX, sy=window.scrollY;
  function R(e){var r=e.getBoundingClientRect();return [r.left+sx,r.top+sy,r.width,r.height];}
  var imgs=[];
  document.querySelectorAll('img,video,canvas').forEach(function(e){var r=e.getBoundingClientRect();if(r.width>vw*0.3&&r.height>60)imgs.push(R(e));});
  var cands=[];
  document.querySelectorAll('article,[class*="content"],[class*="Content"],[class*="view"],[class*="View"],[class*="article"],[class*="post"],[class*="read"],[class*="body"],[class*="txt"],[id*="content"],[id*="view"]').forEach(function(e){
    var r=e.getBoundingClientRect(); if(r.width<vw*0.6||r.height<80)return;
    var s=(e.innerText||'').length+e.querySelectorAll('img,video,iframe').length*300;
    cands.push({e:e,s:s,a:r.width*r.height});});
  var max=0; cands.forEach(function(c){if(c.s>max)max=c.s;});
  var best=null; cands.forEach(function(c){if(c.s>=max*0.8&&(!best||c.a<best.a))best=c;});
  var bsel=${JSONObject.quote(bodySel)};
  try{var bs=document.querySelectorAll(bsel);for(var bi=0;bi<bs.length;bi++){var br=bs[bi].getBoundingClientRect();
    if(br.width>vw*0.5&&br.height>40&&(bs[bi].innerText||'').trim().length>80){best={e:bs[bi]};break;}}}catch(x){}
  var titleR=null, key=${JSONObject.quote(title.take(12))};
  if(key.length>2){var ts=document.querySelectorAll('h1,h2,h3,h4,[class*="title"],[class*="subject"],[class*="tit"]');
    for(var i=0;i<ts.length;i++){if((ts[i].innerText||'').indexOf(key)>=0){var tr=ts[i].getBoundingClientRect();if(tr.height>0&&tr.height<300){titleR=R(ts[i]);break;}}}}
  var og=document.querySelector('meta[property="og:description"],meta[name="description"]');
  var text=best?(best.e.innerText||''):'';
  if(text.trim().length<200&&og)text=(og.getAttribute('content')||'')+'\n'+text;
  return JSON.stringify({w:Math.max(d.scrollWidth,document.body.scrollWidth),h:Math.max(d.scrollHeight,document.body.scrollHeight),
    imgs:imgs.slice(0,200),content:best?R(best.e):null,title:titleR,text:text.slice(0,7000)});
})()
"""

/**
 * 화면에 보이지 않는 WebView로 모바일 페이지를 열어 전체를 한 장으로 캡처하고 본문 글자를 읽음.
 * (WebView.enableSlowWholeDocumentDraw()는 MainActivity에서 미리 호출)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PageScanner(post: Post, attempt: Int, onState: (ScanState) -> Unit) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val widthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.roundToPx() }
    val scope = rememberCoroutineScope()
    var webHeightPx by remember(attempt) { mutableIntStateOf(screenHeightPx) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var busy by remember(attempt) { mutableStateOf(false) }

    DisposableEffect(attempt) { onDispose { webView?.destroy(); webView = null } }

    suspend fun eval(wv: WebView, js: String): String? = suspendCoroutine { c -> wv.evaluateJavascript(js) { c.resume(it) } }

    suspend fun capture(wv: WebView) {
        if (busy) return
        busy = true
        eval(wv, HIDE_ADS_JS)
        onState(ScanState.Loading(90, "글 전체 펼치는 중"))
        delay(600)
        val bodySel = listOfNotNull(BODY_SELECTORS[post.source], ARTICLE_SELECTORS).joinToString(", ")
        fun parse(raw: String?) = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
        val first = parse(eval(wv, analyzeJs(post.title, bodySel)))
        val cssW = first?.optDouble("w")?.takeIf { it > 0 } ?: (widthPx / density.density).toDouble()
        val fullPx = ((first?.optDouble("h") ?: 0.0) * widthPx / cssW).toInt().coerceAtLeast(screenHeightPx)
        webHeightPx = fullPx.coerceAtMost(MAX_SCAN_HEIGHT_PX)
        onState(ScanState.Loading(95, "이미지 불러오는 중"))
        delay(1800)
        // 늘어난 화면 기준으로 위치 다시 측정
        val info = parse(eval(wv, analyzeJs(post.title, bodySel))) ?: first
        onState(ScanState.Loading(99, "캡처 중"))
        try {
            val bmp = Bitmap.createBitmap(wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            wv.draw(canvas)
            val scale = bmp.width / (info?.optDouble("w")?.takeIf { it > 0 } ?: cssW)
            fun rect(a: JSONArray?): Rect? = a?.takeIf { it.length() == 4 }?.let {
                Rect((it.getDouble(0) * scale).toInt(), (it.getDouble(1) * scale).toInt(),
                    ((it.getDouble(0) + it.getDouble(2)) * scale).toInt(), ((it.getDouble(1) + it.getDouble(3)) * scale).toInt())
            }
            val images = info?.optJSONArray("imgs")?.let { arr -> (0 until arr.length()).mapNotNull { rect(arr.optJSONArray(it)) } }.orEmpty()
            var content = rect(info?.optJSONArray("content"))
            val title = rect(info?.optJSONArray("title"))
            if (content != null && title != null && title.top < content.top) content = Rect(content.left, title.top, content.right, content.bottom)
            onState(ScanState.Done(ScanResult(bmp, fullPx > MAX_SCAN_HEIGHT_PX, content, title, images, info?.optString("text").orEmpty(), wv.url)))
        } catch (e: OutOfMemoryError) {
            onState(ScanState.Failed("글이 너무 길어서 캡처하지 못했어요"))
        } catch (e: Exception) {
            onState(ScanState.Failed(e.message ?: "캡처 실패"))
        }
    }

    key(attempt) {
        Box(Modifier.size(1.dp).clipToBounds()) {
            AndroidView(
                modifier = Modifier.requiredSize(with(density) { widthPx.toDp() }, with(density) { webHeightPx.toDp() }),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                // 앱 실행 링크(intent:, market: 등)는 무시하고 웹 주소만 따라감
                                val scheme = request.url.scheme.orEmpty()
                                return scheme != "http" && scheme != "https"
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                // 구글 뉴스 주소는 실제 기사로 넘어가는 중간 페이지라 기다림
                                if (url.contains("news.google.com")) return
                                if (view.progress >= 100) scope.launch { capture(view) }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, p: Int) {
                                if (!busy) onState(ScanState.Loading(p * 85 / 100, "페이지 여는 중"))
                            }
                        }
                        loadUrl(mobileUrl(post))
                        webView = this
                    }
                },
            )
        }
        LaunchedEffect(Unit) {
            delay(25_000) // 너무 오래 걸리면 지금까지 그려진 대로 캡처
            webView?.let { capture(it) }
        }
    }
}
