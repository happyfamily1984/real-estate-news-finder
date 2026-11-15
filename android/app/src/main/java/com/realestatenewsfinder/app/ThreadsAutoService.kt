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
    fun note(line: String) { log = (log + line + "\n").takeLast(2500) }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ThreadsAuto.switching?.let { runSwitch(it); return }
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
        if (click(n)) return true
        val b = bounds(n)
        return b.width() > 0 && b.height() > 0 && tapAt(b.exactCenterX(), b.exactCenterY())
    }

    /**
     * 하단 탭의 프로필 버튼 누르기. 피드 글의 버튼(답글·공유 등)이나 시스템 버튼을 잘못 누르지 않도록
     * 스레드 탭 줄(시스템 내비게이션 바 바로 위, 화면 높이의 약 7.5%) 안에서만 찾음.
     * 탭이 접근성 정보에 안 나오면 탭 줄의 맨 오른쪽 자리(가로 87%)를 직접 누름.
     */
    private fun pressProfileTab(nodes: List<AccessibilityNodeInfo>, win: android.graphics.Rect): Boolean {
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(win.bottom)
        val barBottom = minOf(win.bottom, screenH - navInset()).toFloat()
        val barTop = barBottom - screenH * 0.075f
        val zone = nodes.filter { n ->
            val b = bounds(n)
            b.width() > 0 && b.height() > 0 && b.centerY() > barTop && b.centerY() < barBottom && b.width() < win.width() * 0.4f && b.height() < screenH * 0.12f
        }
        ThreadsAuto.note("  탭 줄 ${barTop.toInt()}~${barBottom.toInt()}/${screenH} 후보: " + zone.take(12).joinToString(" | ") { "${label(it).take(12)}@${bounds(it).centerX() * 100 / win.width().coerceAtLeast(1)}%${if (it.isClickable) "c" else ""}" })
        val tab = zone.firstOrNull { n -> label(n).let { it.contains("프로필") || it.contains("Profile", true) } }
            ?: zone.filter { bounds(it).centerX() > win.left + win.width() * 0.75f }.maxByOrNull { bounds(it).centerX() }
        if (tab != null && (clickSmall(tab, win) || bounds(tab).let { tapAt(it.exactCenterX(), it.exactCenterY()) })) return true
        // 탭 정보가 없을 때: 탭 줄 가운데 높이, 다섯 칸 중 맨 오른쪽 칸
        return tapAt(win.left + win.width() * 0.867f, barBottom - screenH * 0.036f)
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
        if (now - job.startedAt > 60_000 || job.steps > 30) { endSwitch("계정 전환을 끝내지 못했어요. 스레드에서 직접 전환해 주세요."); return }
        if (now - job.lastActionAt < 900) return
        val root = rootInActiveWindow ?: return
        if (root.packageName != ThreadsKit.THREADS_PACKAGE) return
        val nodes = all(root)
        val labels = nodes.map { label(it) }.filter { it.isNotEmpty() }
        val win = bounds(root).takeIf { it.height() > 0 } ?: resources.displayMetrics.let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
        val screen = ThreadsAuto.screenOf(labels, hasEditable = nodes.any { it.isEditable })
        fun acted(what: String, ok: Boolean) {
            ThreadsAuto.note("$screen: $what → ${if (ok) "누름" else "실패"}")
            if (ok) job.steps++
            job.lastActionAt = now // 실패해도 잠깐 쉬었다가 다시 봄 (기록이 넘치지 않게)
            if (!ok) job.misses++
            if (job.misses > 12) endSwitch("스레드 화면에서 누를 곳을 찾지 못했어요. 설정의 '전환 기록'을 보내주세요.")
        }

        when (screen) {
            ThreadsAuto.Screen.LOGIN -> {
                val account = nodes.firstOrNull { ThreadsAuto.isAccount(label(it), job.target) }
                if (account == null) { endSwitch("스레드 로그인 목록에 @${job.target} 계정이 없어요. 직접 로그인해 주세요."); return }
                val ok = press(account)
                if (ok) job.loginClicked = true
                acted("계정 ${job.target}", ok)
            }
            ThreadsAuto.Screen.LOGOUT_DIALOG -> {
                // 확인 창의 '로그아웃' 버튼: 뒤에 깔린 설정 목록의 '로그아웃' 줄(화면 맨 아래)이 아니라 더 위에 있는 것
                val buttons = nodes.filter { label(it).trim().let { l -> l == "로그아웃" || l.equals("Log out", true) } }
                ThreadsAuto.note("  로그아웃 버튼: " + buttons.joinToString(" | ") { "y${bounds(it).centerY() * 100 / win.height().coerceAtLeast(1)}%${if (it.isClickable) "c" else ""}" })
                acted("로그아웃 확인", press(buttons.minByOrNull { bounds(it).centerY() }))
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
                acted("설정 버튼", clickSmall(gear, win))
            }
            ThreadsAuto.Screen.SETTINGS -> {
                if (!job.sawOther) { // 프로필을 거치지 않고 설정 화면이 열려 있던 경우: 계정을 확인할 수 없으니 뒤로
                    acted("설정 화면에서 뒤로", performGlobalAction(GLOBAL_ACTION_BACK)); return
                }
                val logout = nodes.firstOrNull { label(it).trim().let { l -> l == "로그아웃" || l.equals("Log out", true) } }
                if (logout != null && bounds(logout).bottom <= win.bottom) acted("로그아웃", press(logout))
                else acted("아래로 넘기기", nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
            }
            ThreadsAuto.Screen.OTHER -> {
                if (job.loginClicked) {
                    // 로그인 뒤 홈 화면이 뜸: 맨 위에 내 계정 이름이 보이거나 6초가 지나면 끝
                    if (now - job.lastActionAt > 2500 && (nodes.any { ThreadsAuto.isAccount(label(it), job.target) } || now - job.lastActionAt > 6000)) switchDone(job, "@${job.target} 계정으로 전환했어요.")
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
                acted("프로필 탭", pressProfileTab(nodes, win))
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
