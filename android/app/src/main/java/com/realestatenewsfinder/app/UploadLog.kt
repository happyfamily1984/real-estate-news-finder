package com.realestatenewsfinder.app

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/**
 * 스레드로 보낸 글 기록 (글 id → 보낸 시각). 목록에서 '올림' 표시를 하고, 같은 글을 또 올리려 하면 한 번 물어봄.
 * 앱은 스레드 앱에서 '게시'까지 눌렀는지는 알 수 없어서, '스레드에 올리기'로 스레드에 넘긴 시점을 기록함. 잘못 표시되면 지울 수 있음.
 */
object UploadLog {
    private const val KEY = "uploaded"
    private const val MAX = 1500

    /** 화면이 바뀐 것을 바로 알 수 있게 Compose 상태로 들고 있음 */
    val sent = mutableStateMapOf<String, Long>()
    private var loaded = false

    /** 저장된 기록(JSON) → 맵. 가장 오래된 것부터 잘라 MAX개까지만 */
    fun parse(json: String?): Map<String, Long> {
        val o = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optLong(it) }.entries.sortedByDescending { it.value }.take(MAX).associate { it.key to it.value }
    }

    fun toJson(map: Map<String, Long>): String = JSONObject().also { o -> map.entries.sortedByDescending { it.value }.take(MAX).forEach { o.put(it.key, it.value) } }.toString()

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        sent.putAll(parse(sp(ctx).getString(KEY, null)))
    }

    fun mark(ctx: Context, id: String, at: Long = System.currentTimeMillis()) {
        load(ctx)
        sent[id] = at
        sp(ctx).edit().putString(KEY, toJson(sent)).apply()
    }

    fun clear(ctx: Context, id: String) {
        load(ctx)
        sent.remove(id)
        sp(ctx).edit().putString(KEY, toJson(sent)).apply()
    }

    /** "11/18 14:20" */
    fun label(at: Long): String = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA).format(java.util.Date(at))
}
