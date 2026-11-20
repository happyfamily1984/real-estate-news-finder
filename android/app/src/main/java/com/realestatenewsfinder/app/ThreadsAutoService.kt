package com.realestatenewsfinder.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** 스레드 자동 입력 작업 (내보내기 화면에서 '스레드에 올리기'를 누르면 30초 동안 유효, 한 번만 입력) */
object ThreadsAuto {
    class Job(val text: String, val startedAt: Long = System.currentTimeMillis(), var composeClicked: Boolean = false)

    /**
     * 계정 확인·전환 작업: 스레드가 target 계정이면 아무것도 하지 않고, 다른 계정이면 로그아웃 후 target으로 로그인.
     * files·text가 있으면 끝난 뒤 그 글을 스레드로 보냄.
     */
    class Switch(
        val target: String, val files: List<java.io.File>? = null, val text: String? = null,
        val startedAt: Long = System.currentTimeMillis(), var lastActionAt: Long = 0, var steps: Int = 0,
        var loginClicked: Boolean = false, var sawOther: Boolean = false, var misses: Int = 0,
        /** 마지막으로 무언가를 실제로 누른 시각 (0이면 아직 없음) */
        var lastOkAt: Long = 0,
        /** 로그아웃 확인을 누른 뒤 계정 목록 화면이 뜨기를 기다리는 중 */
        var loggingOut: Boolean = false,
        /** 직전 확인 때 누르려던 대상과 그 위치 (화면이 움직이는 중인지 판단) */
        var steadySig: String = "",
        /** 지금 누르려는 대상 / 마지막으로 누른 대상 (같은 것을 연달아 누르지 않게) */
        var curKey: String = "", var lastKey: String = "",
        /** 빈 화면이거나 '…하는 중' 화면 (스레드가 다시 켜지는 중) */
        var loading: Boolean = false,
        /** 로그인(계정 목록) 화면을 처음 본 시각 */
        var loginSeenAt: Long = 0,
        /** 탭이 없는 화면에서 뒤로 나간 횟수 */
        var backs: Int = 0,
    )

    @Volatile var pending: Job? = null
    @Volatile var switching: Switch? = null

    fun start(text: String) { pending = Job(text) }

    /** 스레드 앱을 열고 계정 확인·전환을 시작. 스레드를 열 수 없으면 false */
    fun startSwitch(ctx: Context, target: String, files: List<java.io.File>? = null, text: String? = null): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ThreadsKit.THREADS_PACKAGE) ?: return false
        switching = Switch(target.trim().removePrefix("@"), files, text)
        log = ""
        ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun isEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, ThreadsAutoService::class.java).flattenToString()
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** 화면에 보이는 글자들로 지금 어느 화면인지 판단 (테스트할 수 있게 분리) */
    enum class Screen { LOGIN, LOGOUT_DIALOG, PROFILE, SETTINGS, COMPOSE, OTHER }

    /** hasEditable: 화면에 글자 입력칸이 있는지 (글쓰기·답글 화면 판단용. 홈 화면에는 입력칸이 없음) */
    fun screenOf(labels: List<String>, hasEditable: Boolean = false): Screen {
        fun has(vararg keys: String) = labels.any { l -> keys.any { l.contains(it, ignoreCase = true) } }
        return when {
            has("로그인 또는 가입하기", "다른 프로필 사용하기", "Log in or sign up", "Use another profile") -> Screen.LOGIN
            has("로그아웃하시겠어요", "Log out of your account", "Log out of") -> Screen.LOGOUT_DIALOG
            has("프로필 편집", "Edit profile") -> Screen.PROFILE
            has("Meta 계정", "Meta account") || (has("개인정보 보호", "Privacy") && has("콘텐츠 기본 설정", "Content preferences")) -> Screen.SETTINGS
            // 글쓰기·답글 화면에 잘못 들어온 경우 (뒤로 나가야 함).
            // 홈 화면의 버튼 이름(새 스레드, 답글 등)과 헷갈리지 않도록 입력칸이 있을 때만 글쓰기 화면으로 봄
            hasEditable && (has("답글 또 달기", "스레드에 추가", "Add to thread") || labels.any { it.trim() == "게시" || it.trim().equals("Post", true) }) -> Screen.COMPOSE
            else -> Screen.OTHER
        }
    }

    fun isAccount(label: String, target: String) = label.trim().removePrefix("@").equals(target, ignoreCase = true)

    /** 마지막 계정 전환 과정 기록 (안 될 때 원인을 찾기 위한 것. 설정 화면에서 볼 수 있음) */
    @Volatile var log: String = ""
    private var lastNote = ""
    fun note(line: String) {
        if (line == lastNote) return // 같은 줄이 반복되면 한 번만 남김
        lastNote = line
        log = (log + line + "\n").takeLast(3000)
    }
}

/** 네이버 블로그 글쓰기 화면에 제목·본문 넣기 ('블로그에 올리기'를 누르면 40초 동안 유효, 한 번만 입력) */
object BlogAuto {
    const val PACKAGE = "com.nhn.android.blog"
    class Job(
        val title: String, val body: String, val startedAt: Long = System.currentTimeMillis(),
        var titleDone: Boolean = false, var titleTries: Int = 0, var titleClicked: Boolean = false,
        var bodyTries: Int = 0, var bodyClicked: Boolean = false, var lastAt: Long = 0, var logged: Boolean = false,
    )
    @Volatile var pending: Job? = null
    fun start(title: String, body: String) { pending = Job(title.trim(), body); ThreadsAuto.log = ""; ThreadsAuto.note("블로그 자동 입력 시작") }

    /** 입력칸이 제목 칸인지: 안내 글자(또는 비어 있을 때 보이는 글자)가 '제목' */
    fun isTitleField(hint: String, text: String, showingHint: Boolean) = hint.trim() == "제목" || (showingHint && text.trim() == "제목") || hint.contains("제목을")
}

/**
 * 스레드 앱 화면에서만 동작하는 접근성 서비스.
 * - 계정 확인·전환: 올릴 계정이 아니면 로그아웃하고 저장된 계정 목록에서 올릴 계정을 선택 (설정에서 켠 경우)
 * - 글쓰기 화면이 아니면 '새 스레드/만들기' 버튼을 눌러 글쓰기 화면을 엶
 * - 본문 입력칸에 본문을 한 번 붙여넣음
 * - 게시 버튼은 누르지 않음 (사용자가 확인 후 직접 게시)
 */
class ThreadsAutoService : AccessibilityService() {
    private val composeLabels = listOf("새 스레드", "새로운 스레드", "만들기", "작성", "New thread", "Create", "Compose")

    // 화면이 멈춰 있으면 접근성 알림이 오지 않아 다음 동작으로 못 넘어감 → 계정 전환 중에는 0.25초마다 직접 화면을 확인
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            ThreadsAuto.switching?.let { runCatching { runSwitch(it) } }
            BlogAuto.pending?.let { runCatching { runBlog(it) } }
            handler.postDelayed(this, 250)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 250)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ThreadsAuto.switching?.let { runSwitch(it); return }
        BlogAuto.pending?.let { runCatching { runBlog(it) }; return }
        val job = ThreadsAuto.pending ?: return
        val elapsed = System.currentTimeMillis() - job.startedAt
        if (elapsed > 30_000) { ThreadsAuto.pending = null; return }
        val root = rootInActiveWindow ?: return
        if (root.packageName != ThreadsKit.THREADS_PACKAGE) return

        val input = find(root) { it.isEditable }
        if (input != null) {
            // 딱 한 번만 넣음. 계속 다시 넣으면 사용자가 고친 내용·줄바꿈이 덮어써져서 수정이 안 먹는 것처럼 보임
            ThreadsAuto.pending = null
            val current = if (input.isShowingHintText) "" else input.text?.toString().orEmpty()
            if (current.contains(job.text.take(15))) return // 스레드가 이미 본문을 받아 넣은 경우
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // 붙여넣기로 입력 (본문은 올리기 직전에 클립보드에 복사해 둠).
            // 글자를 강제로 바꿔 넣는 방식(SET_TEXT)은 스레드 입력창 내부 상태와 어긋나 이후 줄바꿈·수정이 꼬일 수 있음
            val ok = input.performAction(AccessibilityNodeInfo.ACTION_PASTE) || run {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (current.isBlank()) job.text else current + "\n" + job.text)
                }
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            if (ok) finish()
            return
        }
        // 글쓰기 화면이 아직 아니면 (공유로 바로 안 열렸을 때) 새 글 버튼을 한 번 누름
        if (!job.composeClicked && elapsed > 2500) {
            val btn = find(root) { n ->
                val label = (n.contentDescription ?: n.text)?.toString().orEmpty()
                label.isNotEmpty() && composeLabels.any { label.equals(it, true) || label.startsWith(it, true) }
            }
            var target = btn
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) job.composeClicked = true
        }
    }

    // ---------- 네이버 블로그: 제목 · 본문 넣기 ----------

    private fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
    }

    private fun hintOf(n: AccessibilityNodeInfo) = (if (android.os.Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null).orEmpty()

    /**
     * 한 번에 한 동작씩: ① 제목 칸에 제목 넣기 ② 본문 칸에 본문 넣기 (본문 칸이 없으면 '텍스트' 버튼을 한 번 눌러 만듦).
     * '위치 추가'나 '등록'은 누르지 않음. 못 넣으면 그만두고 붙여넣기 안내 (본문은 복사돼 있음).
     */
    private fun runBlog(job: BlogAuto.Job) {
        val now = System.currentTimeMillis()
        fun end(message: String) { BlogAuto.pending = null; ThreadsAuto.note("끝: $message"); Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        if (now - job.startedAt > 40_000) { end("블로그에 글을 넣지 못했어요. 본문은 복사돼 있으니 붙여넣기 해주세요."); return }
        if (now - job.lastAt < 700) return
        val root = rootInActiveWindow ?: return
        if (root.packageName != BlogAuto.PACKAGE) return
        val nodes = all(root)
        val edits = nodes.filter { it.isEditable }
        if (edits.isEmpty() && nodes.none { label(it).trim() == "제목" }) return // 아직 글쓰기 화면이 아님
        job.lastAt = now
        if (!job.logged) {
            job.logged = true
            ThreadsAuto.note("  입력칸 ${edits.size}개: " + edits.take(6).joinToString(" | ") { "힌트[${hintOf(it).take(10)}] 글[${it.text?.toString().orEmpty().take(10)}]${if (it.isShowingHintText) "h" else ""} ${it.className?.toString()?.substringAfterLast('.')}" })
            ThreadsAuto.note("  보이는 글자: " + nodes.map { label(it) }.filter { it.isNotEmpty() }.distinct().take(25).joinToString(" | ") { it.take(12) })
        }
        fun isTitle(n: AccessibilityNodeInfo) = BlogAuto.isTitleField(hintOf(n), n.text?.toString().orEmpty(), n.isShowingHintText) || n.text?.toString() == job.title
        if (!job.titleDone) {
            if (job.title.isBlank()) { job.titleDone = true; return }
            val t = edits.firstOrNull { isTitle(it) }
            if (t != null) {
                val ok = setText(t, job.title)
                ThreadsAuto.note("제목 넣기 → ${if (ok) "됨" else "실패"}")
                if (ok || ++job.titleTries >= 3) job.titleDone = true
            } else if (!job.titleClicked) {
                // 제목 칸이 입력칸으로 안 보이면 '제목' 글자 자리를 한 번 눌러 입력칸을 엶
                val lbl = nodes.firstOrNull { label(it).trim() == "제목" }
                job.titleClicked = true
                ThreadsAuto.note("제목 자리 누르기 → ${if (lbl != null && press(lbl)) "누름" else "없음"}")
            } else if (++job.titleTries >= 4) { job.titleDone = true; ThreadsAuto.note("제목 칸을 찾지 못함") }
            return
        }
        val body = edits.firstOrNull { !isTitle(it) }
        if (body != null) {
            val current = if (body.isShowingHintText) "" else body.text?.toString().orEmpty()
            if (current.contains(job.body.take(15))) { end("제목과 본문을 넣었어요. 확인 후 등록을 눌러주세요."); return }
            val ok = setText(body, if (current.isBlank()) job.body else current + "\n" + job.body) || body.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            ThreadsAuto.note("본문 넣기 → ${if (ok) "됨" else "실패"}")
            if (ok) end("제목과 본문을 넣었어요. 확인 후 등록을 눌러주세요.")
            else if (++job.bodyTries >= 3) end("본문 칸에 넣지 못했어요. 본문은 복사돼 있으니 붙여넣기 해주세요.")
            return
        }
        if (!job.bodyClicked) {
            // 사진만 있고 글 칸이 없으면 아래 도구 줄의 '텍스트' 버튼을 한 번 눌러 글 칸을 만듦
            val btn = nodes.firstOrNull { n -> label(n).trim().let { it == "텍스트" || it == "텍스트 추가" || it == "글" || it.equals("Text", true) } }
            job.bodyClicked = true
            ThreadsAuto.note("텍스트 버튼 → ${if (btn != null && click(btn)) "누름" else "없음"}")
        } else if (++job.bodyTries >= 5) end("본문 칸을 찾지 못했어요. 본문은 복사돼 있으니 붙여넣기 해주세요.")
    }

    // ---------- 계정 확인 · 전환 ----------

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString().orEmpty()

    private fun all(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && out.size < 1500) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun click(n: AccessibilityNodeInfo?): Boolean {
        var t = n
        while (t != null && !t.isClickable) t = t.parent
        return t?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun bounds(n: AccessibilityNodeInfo) = android.graphics.Rect().also { n.getBoundsInScreen(it) }

    private fun endSwitch(message: String) {
        ThreadsAuto.switching = null
        ThreadsAuto.note("끝: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** 계정이 맞는 것을 확인했거나 전환을 마쳤을 때: 보낼 글이 있으면 이어서 스레드로 보냄 */
    private fun switchDone(job: ThreadsAuto.Switch, message: String) {
        ThreadsAuto.switching = null
        ThreadsAuto.note("끝: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val files = job.files; val text = job.text
        if (files != null && text != null) {
            ThreadsAuto.start(text)
            runCatching { ThreadsKit.shareToThreads(this, files, text) }
        }
    }

    /** 화면 아래 시스템 내비게이션 바(뒤로·홈·최근 앱 버튼) 높이. 이 영역은 건드리지 않음 */
    private fun navInset(): Int = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom
        } else {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
    }.getOrDefault(0)

    /**
     * 누를 대상이 화면 안에 있고, 직전 확인 때와 같은 자리에 그대로 있을 때만 true.
     * 목록이 넘어가는 중이거나 창이 뜨는 중에 누르면 엉뚱한 곳이 눌리기 때문에, 자리가 멈춘 뒤에 누름.
     */
    private fun steady(job: ThreadsAuto.Switch, key: String, n: AccessibilityNodeInfo?, win: android.graphics.Rect): Boolean {
        if (n == null) { job.steadySig = ""; return false }
        job.curKey = key
        // 방금 누른 것과 같은 대상은 화면이 바뀔 시간을 주고(2.5초) 그래도 그대로일 때만 다시 누름
        if (key == job.lastKey && System.currentTimeMillis() - job.lastOkAt < 2500) return false
        val b = bounds(n)
        val sig = "$key:${b.flattenToString()}"
        val inside = b.width() > 0 && b.height() > 0 && b.top >= win.top && b.bottom <= win.bottom && b.left >= win.left && b.right <= win.right
        val ok = inside && job.steadySig == sig
        job.steadySig = sig
        return ok
    }

    /** 화면의 한 점을 손가락으로 누르듯 누름 (버튼이 접근성 누르기를 받지 않을 때) */
    private fun tapAt(x: Float, y: Float): Boolean = runCatching {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 70)).build()
        dispatchGesture(gesture, null, null)
    }.getOrDefault(false)

    /** 접근성 누르기를 먼저 해보고, 안 되면 그 요소의 가운데를 직접 누름 */
    private fun press(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        // 누를 수 있는 윗요소가 줄 하나 크기일 때만 접근성 누르기를 씀.
        // (목록 전체 같은 큰 요소를 누르면 엉뚱한 항목이 눌릴 수 있음 → 그럴 땐 글자 자리를 직접 터치)
        var t: AccessibilityNodeInfo? = n
        while (t != null && !t.isClickable) t = t.parent
        if (t != null && bounds(t).height() < resources.displayMetrics.heightPixels * 0.2f && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val b = bounds(n)
        return b.width() > 0 && b.height() > 0 && tapAt(b.exactCenterX(), b.exactCenterY())
    }

    /**
     * 하단 탭의 프로필 버튼 누르기. 피드 글의 버튼(답글·공유 등)이나 시스템 버튼을 잘못 누르지 않도록
     * 스레드 탭 줄(시스템 내비게이션 바 바로 위, 화면 높이의 약 7.5%) 안에서만 찾음.
     * 탭이 접근성 정보에 안 나오면 탭 줄의 맨 오른쪽 자리(가로 87%)를 직접 누름.
     */
    private fun findProfileTab(nodes: List<AccessibilityNodeInfo>, win: android.graphics.Rect): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(win.bottom)
        val barBottom = minOf(win.bottom, screenH - navInset()).toFloat()
        val barTop = barBottom - screenH * 0.075f
        val zone = nodes.filter { n ->
            val b = bounds(n)
            b.width() > 0 && b.height() > 0 && b.centerY() > barTop && b.centerY() < barBottom && b.width() < win.width() * 0.4f && b.height() < screenH * 0.12f
        }
        ThreadsAuto.note("  탭 줄 ${barTop.toInt()}~${barBottom.toInt()}/${screenH} 후보: " + zone.take(12).joinToString(" | ") { "${label(it).take(12)}@${bounds(it).centerX() * 100 / win.width().coerceAtLeast(1)}%${if (it.isClickable) "c" else ""}" })
        // 하단 탭이 있는 화면인지 확인: 탭 이름(피드·메시지·만들기·활동·프로필) 중 둘 이상이 탭 줄에 보여야 함.
        // 탭이 없는 화면(설정 하위 화면, 글쓰기 등)에서는 아무 데나 누르지 않음
        val tabNames = listOf("피드", "홈", "메시지", "검색", "만들기", "활동", "프로필", "Home", "Search", "Create", "Activity", "Profile")
        if (zone.count { n -> tabNames.any { label(n).contains(it, true) } } < 2) return null
        return zone.firstOrNull { n -> label(n).let { it.contains("프로필") || it.contains("Profile", true) } }
            ?: zone.filter { bounds(it).centerX() > win.left + win.width() * 0.75f }.maxByOrNull { bounds(it).centerX() }
    }

    /** node 또는 그 위의 누를 수 있는 요소를 누름. 단, 너무 큰 요소(화면 일부가 아니라 목록 전체 등)는 누르지 않음 */
    private fun clickSmall(n: AccessibilityNodeInfo?, win: android.graphics.Rect): Boolean {
        var t = n
        while (t != null && !t.isClickable) t = t.parent
        if (t == null) return false
        val b = bounds(t)
        if (b.height() > win.height() * 0.2f || b.width() > win.width() * 0.6f) return false
        return t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * 한 번에 한 동작씩: 프로필 탭 → (다른 계정이면) 설정 → 맨 아래 로그아웃 → 확인 → 계정 목록에서 target 선택.
     * 프로필에서 target 계정이면 아무것도 누르지 않고 끝냄.
     */
    private fun runSwitch(job: ThreadsAuto.Switch) {
        val now = System.currentTimeMillis()
        if (now - job.startedAt > 120_000 || job.steps > 30) { endSwitch("계정 전환을 끝내지 못했어요. 스레드에서 직접 전환해 주세요."); return }
        // 멈춤 규칙: 앞 동작 뒤 4초 안에 다음 동작이 이어지지 않으면 중단.
        // 예외(화면이 뜨기를 기다리는 시간): 스레드가 처음 열릴 때 12초, 로그아웃·로그인 처리 중이거나 빈 화면(불러오는 중)일 때 45초
        val waiting = job.loggingOut || job.loginClicked || job.loading
        val idle = now - (if (job.lastOkAt > 0) job.lastOkAt else job.startedAt)
        val limit = when { job.lastOkAt == 0L -> 12_000L; waiting -> 45_000L; else -> 4000L }
        if (idle > limit) {
            endSwitch(if (job.lastOkAt == 0L) "스레드 화면을 찾지 못해 멈췄어요." else "다음 동작이 이어지지 않아 멈췄어요. 설정의 '전환 기록'을 확인해 주세요.")
            return
        }
        if (now - job.lastActionAt < 450) return
        if (job.lastOkAt > 0 && now - job.lastOkAt < 1100) return // 누른 뒤에는 화면이 바뀔 시간을 줌
        val root = rootInActiveWindow ?: return
        if (root.packageName != ThreadsKit.THREADS_PACKAGE) return
        val nodes = all(root)
        val labels = nodes.map { label(it) }.filter { it.isNotEmpty() }
        val win = bounds(root).takeIf { it.height() > 0 } ?: resources.displayMetrics.let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
        val screen = ThreadsAuto.screenOf(labels, hasEditable = nodes.any { it.isEditable })
        fun acted(what: String, ok: Boolean) {
            ThreadsAuto.note("$screen: $what → ${if (ok) "누름" else "실패"}")
            if (ok) { job.steps++; job.lastOkAt = now; job.loading = false; job.lastKey = job.curKey }
            job.curKey = ""
            job.lastActionAt = now // 실패해도 잠깐 쉬었다가 다시 봄 (기록이 넘치지 않게)
            if (!ok) job.misses++
        }

        when (screen) {
            ThreadsAuto.Screen.LOGIN -> {
                val account = nodes.firstOrNull { ThreadsAuto.isAccount(label(it), job.target) }
                if (account == null) {
                    // 계정 목록이 늦게 뜨는 경우가 있어 바로 실패로 보지 않고 20초까지 기다림
                    if (job.loginSeenAt == 0L) { job.loginSeenAt = now; ThreadsAuto.note("  계정 목록이 뜨기를 기다리는 중") }
                    job.loading = true
                    if (now - job.loginSeenAt > 20_000) endSwitch("스레드 로그인 목록에 @${job.target} 계정이 없어요. 직접 로그인해 주세요.")
                    return
                }
                job.loggingOut = false
                if (!steady(job, "account", account, win)) return
                val ok = press(account)
                if (ok) job.loginClicked = true
                acted("계정 ${job.target}", ok)
            }
            ThreadsAuto.Screen.LOGOUT_DIALOG -> {
                // 확인 창의 '로그아웃' 버튼: 뒤에 깔린 설정 목록의 '로그아웃' 줄(화면 맨 아래)이 아니라 더 위에 있는 것
                val buttons = nodes.filter { label(it).trim().let { l -> l == "로그아웃" || l.equals("Log out", true) } }
                ThreadsAuto.note("  로그아웃 버튼: " + buttons.joinToString(" | ") { "y${bounds(it).centerY() * 100 / win.height().coerceAtLeast(1)}%${if (it.isClickable) "c" else ""}" })
                val confirm = buttons.minByOrNull { bounds(it).centerY() }
                if (!steady(job, "confirm", confirm, win)) return // 확인 창이 다 뜰 때까지 기다림
                val ok = press(confirm)
                if (ok) job.loggingOut = true
                acted("로그아웃 확인", ok)
            }
            ThreadsAuto.Screen.COMPOSE -> acted("글쓰기 화면에서 뒤로", performGlobalAction(GLOBAL_ACTION_BACK))
            ThreadsAuto.Screen.PROFILE -> {
                if (job.loginClicked) { switchDone(job, "@${job.target} 계정으로 전환했어요."); return }
                if (nodes.any { ThreadsAuto.isAccount(label(it), job.target) }) { switchDone(job, "이미 @${job.target} 계정이에요."); return }
                job.sawOther = true
                // 오른쪽 위 설정(톱니바퀴): 이름으로 찾고, 없으면 맨 위 줄에서 가장 오른쪽에 있는 작은 버튼
                val top = nodes.filter { n -> val b = bounds(n); b.width() > 0 && b.centerY() < win.top + win.height() * 0.09f && b.width() < win.width() * 0.25f }
                ThreadsAuto.note("  상단 후보: " + top.take(10).joinToString(" | ") { "${label(it).take(12)}@${bounds(it).centerX() * 100 / win.width().coerceAtLeast(1)}%${if (it.isClickable) "c" else ""}" })
                val gear = top.firstOrNull { n -> label(n).let { it.contains("설정") || it.contains("Settings", true) } }
                    ?: top.filter { bounds(it).centerX() > win.left + win.width() * 0.8f }.maxByOrNull { bounds(it).centerX() }
                if (!steady(job, "gear", gear, win)) return
                acted("설정 버튼", clickSmall(gear, win))
            }
            ThreadsAuto.Screen.SETTINGS -> {
                if (!job.sawOther) { // 프로필을 거치지 않고 설정 화면이 열려 있던 경우: 계정을 확인할 수 없으니 뒤로
                    acted("설정 화면에서 뒤로", performGlobalAction(GLOBAL_ACTION_BACK)); return
                }
                if (job.loggingOut) return // 로그아웃 확인을 이미 눌렀음: 처리될 때까지 기다림
                val logout = nodes.firstOrNull { label(it).trim().let { l -> l == "로그아웃" || l.equals("Log out", true) } }
                if (logout != null && bounds(logout).bottom <= win.bottom) {
                    // 목록이 넘어가는 중에 누르면 다른 줄이 눌림 → '로그아웃' 줄이 멈춘 뒤에 누름
                    if (steady(job, "logout", logout, win)) acted("로그아웃", press(logout))
                } else if (now - job.lastOkAt > 700) acted("아래로 넘기기", nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
            }
            ThreadsAuto.Screen.OTHER -> {
                if (job.loginClicked) {
                    // 로그인 뒤 홈 화면이 뜸: 맨 위에 내 계정 이름이 보이거나 6초가 지나면 끝
                    if (now - job.lastOkAt > 2000 && (nodes.any { ThreadsAuto.isAccount(label(it), job.target) } || now - job.lastOkAt > 6000)) switchDone(job, "@${job.target} 계정으로 전환했어요.")
                    return
                }
                // 홈 화면 맨 위 '새로운 소식을 전하세요' 바로 위에 지금 계정 이름이 나옴 → 이미 맞는 계정이면 프로필까지 갈 필요 없음
                val prompt = nodes.firstOrNull { label(it).let { l -> l.contains("새로운 소식을 전하세요") || l.contains("What's new", true) } }
                if (prompt != null) {
                    val pb = bounds(prompt)
                    val mine = nodes.filter { n -> val b = bounds(n); label(n).isNotEmpty() && n !== prompt && b.bottom <= pb.top + 8 && pb.top - b.bottom < win.height() * 0.05f && Math.abs(b.left - pb.left) < win.width() * 0.08f }
                    ThreadsAuto.note("  홈 계정: " + mine.joinToString(",") { label(it).take(20) })
                    if (mine.any { ThreadsAuto.isAccount(label(it), job.target) }) { switchDone(job, "이미 @${job.target} 계정이에요."); return }
                }
                val tab = findProfileTab(nodes, win)
                if (tab != null) {
                    if (steady(job, "tab", tab, win)) acted("프로필 탭", clickSmall(tab, win) || bounds(tab).let { tapAt(it.exactCenterX(), it.exactCenterY()) })
                } else if (job.loggingOut || labels.isEmpty() || labels.any { it.contains("하는 중") || it.contains("불러오는") || it.contains("Loading", true) }) {
                    // 로그아웃 처리 중이거나 스레드가 다시 켜지는 중(빈 화면): 아무것도 누르지 않고 기다림
                    if (!job.loading) ThreadsAuto.note("  화면이 뜨기를 기다리는 중: " + labels.take(3).joinToString(" | ") { it.take(14) })
                    job.loading = true
                } else {
                    // 하단 탭이 없는 화면(글쓰기, 설정 하위 화면 등): 뒤로 나가서 탭이 있는 화면까지 돌아감
                    ThreadsAuto.note("  보이는 글자: " + labels.take(8).joinToString(" | ") { it.take(14) })
                    job.backs++
                    if (job.backs > 6) { endSwitch("스레드 첫 화면으로 돌아가지 못해 멈췄어요."); return }
                    acted("탭 없는 화면에서 뒤로", performGlobalAction(GLOBAL_ACTION_BACK))
                }
            }
        }
    }

    private fun finish() {
        ThreadsAuto.pending = null
        Toast.makeText(this, "본문을 입력했어요. 확인 후 게시를 눌러주세요.", Toast.LENGTH_LONG).show()
    }

    private fun find(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 3000) {
            val n = queue.removeFirst()
            if (match(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    override fun onInterrupt() {}
}
