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
    /** 작성자 닉네임·프로필 위치 (커뮤니티 글 모자이크용) */
    val authorRects: List<Rect>,
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

/** 사이트별 작성자(글쓴이·댓글 작성자) 닉네임/프로필 위치. 여기에 없어도 흔한 이름(nick, writer, author…)은 자동으로 찾음 */
val AUTHOR_SELECTORS = mapOf(
    "dcinside" to "ul.ginfo2 > li:first-child, .gallview-tit-box .nick, .gall-writer, .comment-box .nick, .all-comment-lst .nick, a.nick, .ginfo-area .nick, .sp-nick, .nick-icon",
    "clien" to ".post_contact .contact_name, .contact_name, .nickname, .post_info .nickname, .comment_info .nickname, .nickimg",
    "ppomppu" to ".bbs.view .info .name, .info a[href*='member'], h4 + .info b, .hi, .writer, .view_info .name, .baseList-name",
    "navercafe" to ".nick_box, .profile_area, .user_wrap .nick, .ArticleWriterProfile, .thumb_area, a.nick, .nickname, .profile_info, .user_area .name",
)

/**
 * 본문만 남기기: 본문 요소를 찾아 페이지 전체를 '제목 + 본문'으로 바꿈 (메뉴·광고·댓글·추천글·작성자 줄 제거).
 * 사진은 화면 폭에 맞추고 글자는 읽기 좋은 크기로. 본문을 못 찾으면 아무것도 하지 않고 false.
 */
private fun isolateJs(title: String, sel: String) = """
(function(){try{
  var list=document.querySelectorAll(${JSONObject.quote(sel)}), c=null;
  for(var i=0;i<list.length;i++){var e=list[i]; if((e.innerText||'').trim().length+e.querySelectorAll('img,video').length*40>=40){c=e;break;}}
  if(!c) return false;
  c.querySelectorAll('script,style,ins,iframe:not([src*="youtube"]):not([src*="youtu.be"]),[class*="adsbygoogle"],[id^="div-gpt-ad"],[class*="coupang"],a[href*="coupang"]').forEach(function(x){x.remove();});
  c.querySelectorAll('img').forEach(function(i){var d=i.getAttribute('data-src')||i.getAttribute('data-original')||i.getAttribute('data-lazy-src'); if(d) i.src=d; i.removeAttribute('width'); i.removeAttribute('height'); i.removeAttribute('style'); i.loading='eager';});
  var t=document.createElement('div'); t.textContent=${JSONObject.quote(title)};
  var html='<div id="cf"><h1 class="cf_title">'+t.innerHTML+'</h1><div class="cf_body">'+c.innerHTML+'</div></div>';
  document.querySelectorAll('meta[name=viewport]').forEach(function(m){m.remove();});
  document.head.insertAdjacentHTML('beforeend','<meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0!important;padding:0!important;background:#fff!important;width:auto!important;min-width:0!important;height:auto!important;overflow:visible!important}#cf{padding:18px 16px 22px;font-size:17px;line-height:1.7;color:#111;word-break:break-word;font-family:sans-serif;background:#fff}#cf .cf_title{font-size:22px;font-weight:800;line-height:1.35;margin:0 0 16px;padding-bottom:14px;border-bottom:1px solid #e5e7eb}#cf *{max-width:100%!important;box-sizing:border-box;float:none!important;position:static!important}#cf .cf_body, #cf .cf_body *{font-size:17px!important;line-height:1.7!important;color:#111!important;background:transparent!important}#cf img,#cf video{width:auto!important;max-width:100%!important;height:auto!important;display:block;margin:10px auto}#cf table{width:100%!important;table-layout:fixed}</style>');
  document.body.innerHTML=html; document.body.className=''; document.body.removeAttribute('style'); window.scrollTo(0,0);
  return true;
}catch(x){return false;}})()
"""

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

private fun analyzeJs(title: String, bodySel: String, authorSel: String? = null) = """
(function(){
  var d=document.documentElement, vw=d.clientWidth||window.innerWidth, sx=window.scrollX, sy=window.scrollY;
  function R(e){var r=e.getBoundingClientRect();return [r.left+sx,r.top+sy,r.width,r.height];}
  var authors=[], site=${JSONObject.quote(authorSel ?: "")}, findAuthors=${if (authorSel != null) "true" else "false"};
  if(findAuthors){
    if(site){try{document.querySelectorAll(site).forEach(function(e){var r=e.getBoundingClientRect();
      if(r.width>0&&r.height>0&&r.height<90&&r.width<vw*0.8)authors.push(R(e));});}catch(x){}}
    document.querySelectorAll('[class*="profile"] img,[class*="avatar"],img[class*="profile"],img[class*="level"],img[class*="icon_nick"]').forEach(function(e){
      var r=e.getBoundingClientRect();if(r.width>0&&r.width<90&&r.height>0&&r.height<90)authors.push(R(e));});
    document.querySelectorAll('[class*="nick"],[class*="Nick"],[class*="writer"],[class*="Writer"],[class*="author"],[class*="Author"],[class*="user_name"],[class*="userName"],[class*="username"],[class*="user-name"],[class*="profile_name"],[class*="member_name"]').forEach(function(e){var r=e.getBoundingClientRect(),t=(e.innerText||'').trim();
      if(r.width>0&&r.height>0&&r.height<70&&r.width<vw*0.7&&t.length>0&&t.length<40)authors.push(R(e));});
  }
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
    authors:authors.slice(0,300),imgs:imgs.slice(0,200),content:best?R(best.e):null,title:titleR,text:text.slice(0,7000)});
})()
"""

/**
 * 화면에 보이지 않는 WebView로 모바일 페이지를 열어 전체를 한 장으로 캡처하고 본문 글자를 읽음.
 * (WebView.enableSlowWholeDocumentDraw()는 MainActivity에서 미리 호출)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PageScanner(post: Post, attempt: Int, community: Boolean = false, onState: (ScanState) -> Unit) {
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
        // 커뮤니티 글: 본문만 추려 '제목 + 본문' 화면으로 다시 구성 (작성자 줄·메뉴·광고·댓글이 빠짐)
        var isolated = false
        if (community) BODY_SELECTORS[post.source]?.let { sel ->
            onState(ScanState.Loading(87, "본문만 추리는 중"))
            delay(900) // 늦게 그려지는 본문(네이버 카페 등) 기다림
            if (eval(wv, isolateJs(post.title, sel)) == "true") { isolated = true; delay(700) }
        }
        eval(wv, HIDE_ADS_JS)
        onState(ScanState.Loading(90, "글 전체 펼치는 중"))
        delay(600)
        val bodySel = if (isolated) "#cf" else listOfNotNull(BODY_SELECTORS[post.source], ARTICLE_SELECTORS).joinToString(", ")
        // 본문만 추린 화면에는 작성자 정보가 없으므로 모자이크 위치도 찾지 않음
        val authorSel: String? = if (community && !isolated) AUTHOR_SELECTORS[post.source].orEmpty() else null
        fun parse(raw: String?) = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
        val first = parse(eval(wv, analyzeJs(post.title, bodySel, authorSel)))
        val cssW = first?.optDouble("w")?.takeIf { it > 0 } ?: (widthPx / density.density).toDouble()
        val fullPx = ((first?.optDouble("h") ?: 0.0) * widthPx / cssW).toInt().coerceAtLeast(screenHeightPx)
        webHeightPx = fullPx.coerceAtMost(MAX_SCAN_HEIGHT_PX)
        onState(ScanState.Loading(95, "이미지 불러오는 중"))
        delay(1800)
        // 늘어난 화면 기준으로 위치 다시 측정
        val info = parse(eval(wv, analyzeJs(post.title, bodySel, authorSel))) ?: first
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
            val authors = info?.optJSONArray("authors")?.let { arr -> (0 until arr.length()).mapNotNull { rect(arr.optJSONArray(it)) } }.orEmpty()
            val images = info?.optJSONArray("imgs")?.let { arr -> (0 until arr.length()).mapNotNull { rect(arr.optJSONArray(it)) } }.orEmpty()
            var content = rect(info?.optJSONArray("content"))
            val title = rect(info?.optJSONArray("title"))
            if (content != null && title != null && title.top < content.top) content = Rect(content.left, title.top, content.right, content.bottom)
            onState(ScanState.Done(ScanResult(bmp, fullPx > MAX_SCAN_HEIGHT_PX, authors, content, title, images, info?.optString("text").orEmpty(), wv.url)))
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
