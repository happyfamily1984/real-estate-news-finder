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

    @Volatile var pending: Job? = null

    fun start(text: String) { pending = Job(text) }

    fun isEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, ThreadsAutoService::class.java).flattenToString()
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }
}

/**
 * 스레드 앱 화면에서만 동작하는 접근성 서비스.
 * - 글쓰기 화면이 아니면 '새 스레드/만들기' 버튼을 눌러 글쓰기 화면을 엶
 * - 본문 입력칸에 본문을 한 번 붙여넣음
 * - 게시 버튼은 누르지 않음 (사용자가 확인 후 직접 게시)
 */
class ThreadsAutoService : AccessibilityService() {
    private val composeLabels = listOf("새 스레드", "새로운 스레드", "만들기", "작성", "New thread", "Create", "Compose")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
