package com.realestatenewsfinder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini 요약 (사용자가 구글 AI Studio에서 발급한 무료 API 키 사용).
 * 무료 한도를 다 쓰거나(429) 오류가 나면 null을 돌려주고, 호출한 쪽이 Groq으로 넘어감.
 */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val COOLDOWN_MS = 60 * 60_000L

    /** 마지막 실패 사유 (설정 화면 표시용) */
    @Volatile var lastError: String? = null

    fun inCooldown(prefs: Prefs) = System.currentTimeMillis() < prefs.geminiCooldownUntil
    fun usable(prefs: Prefs) = prefs.geminiKey.isNotBlank() && !inCooldown(prefs)

    private fun request(method: String, url: String, key: String, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10000; conn.readTimeout = 30000
        conn.setRequestProperty("x-goog-api-key", key)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** 구글이 돌려준 오류 설명 (원인 파악용) */
    private fun errorMessage(body: String) =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body).take(160)

    /** 키로 쓸 수 있는 flash 모델들을 선호 순서대로 (정식 > 미리보기, 최신 버전 우선, 일반 > lite) */
    fun rankModels(listJson: String): List<String> {
        val models = JSONObject(listJson).optJSONArray("models") ?: return emptyList()
        val names = (0 until models.length()).map { models.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true }
            .map { it.getString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") && it.contains("flash") && !Regex("image|tts|live|audio|embedding|exp|thinking|8b|learnlm|native|robotics|computer").containsMatchIn(it) }
        fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return names.sortedWith(
            compareByDescending<String> { !it.contains("preview") }.thenByDescending { version(it) }.thenByDescending { !it.contains("lite") }.thenBy { it.length },
        )
    }

    fun pickModel(listJson: String): String? = rankModels(listJson).firstOrNull()

    /** 시도할 모델 목록: 마지막으로 성공한 모델이 맨 앞 */
    private fun candidates(prefs: Prefs): List<String> {
        if (prefs.geminiModels.isBlank()) {
            val (code, text) = request("GET", "$BASE/models?pageSize=200", prefs.geminiKey)
            if (code != 200) { lastError = "모델 목록 HTTP $code: ${errorMessage(text)}"; return emptyList() }
            prefs.geminiModels = rankModels(text).take(6).joinToString(",")
        }
        val all = prefs.geminiModels.split(',').filter { it.isNotBlank() }
        return (listOf(prefs.geminiModel).filter { it in all } + all).distinct()
    }

    /** 자유 질문 (요약 등). 실패하면 null → 호출한 쪽이 Groq으로 넘어감 */
    suspend fun ask(ctx: Context, system: String, user: String): String? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (!usable(prefs)) return@withContext null
        runCatching {
            var allQuota = true
            for (model in candidates(prefs)) {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.2))
                val (code, text) = request("POST", "$BASE/models/$model:generateContent", prefs.geminiKey, body.toString())
                if (code == 401 || code == 403) { lastError = "HTTP $code: ${errorMessage(text)}"; return@runCatching null }
                if (code != 200) { lastError = "HTTP $code ($model): ${errorMessage(text)}"; if (code != 429) allQuota = false; continue }
                val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val out = parts?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } }?.trim().orEmpty()
                if (out.isNotEmpty()) { prefs.geminiModel = model; lastError = null; return@runCatching out }
            }
            // 모든 모델이 한도 초과면 1시간 쉬고 Groq 사용
            if (allQuota && prefs.geminiModels.isNotBlank()) prefs.geminiCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
            null
        }.getOrElse { lastError = it.message ?: "연결 실패"; null }
    }
}
