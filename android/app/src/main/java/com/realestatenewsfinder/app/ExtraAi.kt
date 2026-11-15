package com.realestatenewsfinder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 예비 요약 AI (무료 키). Gemini → Groq 다음에 순서대로 시도.
 * 둘 다 Groq과 같은 호출 방식(OpenAI 호환)이라 주소만 다름. 모델 이름은 자주 바뀌므로 목록에서 골라 씀.
 */
class ExtraAi(val id: String, val label: String, private val base: String, val keyUrl: String, val keyHint: String, private val rank: (JSONArray) -> List<String>) {
    @Volatile var lastError: String? = null

    fun usable(prefs: Prefs) = prefs.aiKey(id).isNotBlank() && System.currentTimeMillis() >= prefs.aiCooldown(id)

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

    /** 시도할 모델 목록: 마지막으로 성공한 모델이 맨 앞 */
    private fun candidates(prefs: Prefs): List<String> {
        if (prefs.aiModels(id).isBlank()) {
            val (code, text) = request("GET", "$base/models", prefs.aiKey(id))
            if (code != 200) { lastError = if (code == 401) "API 키가 올바르지 않아요" else "모델 목록 HTTP $code"; return emptyList() }
            val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
            prefs.setAiModels(id, rank(data).take(5).joinToString(","))
        }
        val all = prefs.aiModels(id).split(',').filter { it.isNotBlank() }
        return (listOf(prefs.aiModel(id)).filter { it in all } + all).distinct()
    }

    /** 자유 질문 (요약). 실패하면 null → 다음 AI로 */
    suspend fun ask(ctx: Context, system: String, user: String): String? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (!usable(prefs)) return@withContext null
        runCatching {
            val models = candidates(prefs)
            var allQuota = models.isNotEmpty()
            for (model in models) {
                val body = JSONObject().put("model", model).put("temperature", 0.2)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                val (code, text) = request("POST", "$base/chat/completions", prefs.aiKey(id), body.toString())
                if (code == 401 || code == 403) { lastError = "API 키가 올바르지 않아요 (HTTP $code)"; return@runCatching null }
                if (code != 200) {
                    // 한도 초과(429)나 없어진 모델(400/404)이면 다음 모델로
                    lastError = "HTTP $code ($model)"
                    if (code != 429) allQuota = false
                    continue
                }
                val out = runCatching {
                    JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                }.getOrDefault("")
                if (out.isNotEmpty()) { prefs.setAiModel(id, model); lastError = null; return@runCatching out }
                allQuota = false
            }
            if (allQuota) { prefs.setAiCooldown(id, System.currentTimeMillis() + COOLDOWN_MS); lastError = "무료 한도를 다 썼어요 · 10분 동안 쉬어요" }
            else if (models.isNotEmpty()) prefs.setAiModels(id, "") // 모델 목록이 바뀐 듯 → 다음에 다시 고름
            null
        }.getOrElse { lastError = it.message ?: "연결 실패"; null }
    }

    companion object {
        private const val COOLDOWN_MS = 10 * 60_000L
        private val SKIP = Regex("whisper|guard|tts|speech|embed|vision|image|audio|coder|code|ocr|rerank|moderation|safety|tiny|mini|nano|1b|3b", RegexOption.IGNORE_CASE)

        private fun ids(data: JSONArray) = (0 until data.length()).map { data.getJSONObject(it) }
        private fun paramB(id: String) = Regex("(\\d+)b\\b", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        /** 한국어 요약에 무난한 범용 계열을 앞에 */
        private fun family(id: String) = listOf("gemini", "gemma", "llama-3.3", "llama-4", "qwen", "deepseek", "gpt-oss", "mistral", "llama").indexOfFirst { id.contains(it, true) }.let { if (it < 0) 99 else it }

        /** OpenRouter: 무료 모델(id가 ':free'로 끝남)만 */
        fun rankOpenRouter(data: JSONArray): List<String> = ids(data)
            .filter { it.optString("id").endsWith(":free") && !SKIP.containsMatchIn(it.optString("id")) }
            .sortedWith(compareBy<JSONObject> { family(it.optString("id")) }.thenByDescending { paramB(it.optString("id")) }.thenByDescending { it.optInt("context_length") })
            .map { it.getString("id") }

        /** Cerebras: 큰 범용 모델 우선 */
        fun rankCerebras(data: JSONArray): List<String> = ids(data).map { it.optString("id") }
            .filter { it.isNotBlank() && !SKIP.containsMatchIn(it) }
            .sortedWith(compareBy<String> { family(it) }.thenByDescending { paramB(it) })

        val CEREBRAS = ExtraAi("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "https://cloud.cerebras.ai/", "API 키 붙여넣기 (csk-…)", ::rankCerebras)
        val OPENROUTER = ExtraAi("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", "API 키 붙여넣기 (sk-or-…)", ::rankOpenRouter)
        /** 시도 순서 (Gemini → Groq 다음) */
        val ALL = listOf(CEREBRAS, OPENROUTER)
    }
}
