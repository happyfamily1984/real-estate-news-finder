package com.realestatenewsfinder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq 요약 (무료 API 키, Llama 계열 AI). Gemini 한도가 떨어졌을 때 두 번째로 사용. 실패하면 null.
 */
object Groq {
    private const val BASE = "https://api.groq.com/openai/v1"
    private const val COOLDOWN_MS = 10 * 60_000L
    @Volatile var lastError: String? = null

    fun inCooldown(prefs: Prefs) = System.currentTimeMillis() < prefs.groqCooldownUntil
    fun usable(prefs: Prefs) = prefs.groqKey.isNotBlank() && !inCooldown(prefs)

    private fun request(method: String, url: String, key: String, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000; conn.readTimeout = 60000
        conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            return code to (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    /** 쓸 수 있는 모델 중 번역에 맞는 큰 범용 모델 선택 (이름이 바뀌어도 동작하도록) */
    fun pickModel(listJson: String): String? {
        val data = JSONObject(listJson).optJSONArray("data") ?: return null
        val ids = (0 until data.length()).map { data.getJSONObject(it).getString("id") }
            .filter { !Regex("whisper|guard|tts|speech|vision-preview|embed|compound|allam|orpheus", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        fun size(n: String) = Regex("(\\d+)b", RegexOption.IGNORE_CASE).find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return ids.sortedWith(
            compareByDescending<String> { it.contains("versatile") }.thenByDescending { it.contains("llama") }.thenByDescending { size(it) },
        ).firstOrNull()
    }

    private fun model(prefs: Prefs): String? {
        prefs.groqModel.takeIf { it.isNotBlank() }?.let { return it }
        val (code, text) = request("GET", "$BASE/models", prefs.groqKey)
        if (code != 200) { lastError = if (code == 401) "API 키가 올바르지 않아요" else "모델 목록 HTTP $code"; return null }
        return pickModel(text)?.also { prefs.groqModel = it }
    }

    /** 자유 질문 (요약 등). 실패하면 null */
    suspend fun ask(ctx: Context, system: String, user: String): String? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (!usable(prefs)) return@withContext null
        runCatching {
            val model = model(prefs) ?: return@runCatching null
            val body = JSONObject().put("model", model).put("temperature", 0.2)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            val (code, text) = request("POST", "$BASE/chat/completions", prefs.groqKey, body.toString())
            if (code == 429) { prefs.groqCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS; lastError = "무료 한도를 다 썼어요"; return@runCatching null }
            if (code == 404 || (code == 400 && text.contains("model"))) prefs.groqModel = ""
            if (code != 200) { lastError = if (code == 401) "API 키가 올바르지 않아요" else "HTTP $code"; return@runCatching null }
            lastError = null
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().takeIf { it.isNotEmpty() }
        }.getOrElse { lastError = it.message ?: "연결 실패"; null }
    }
}
