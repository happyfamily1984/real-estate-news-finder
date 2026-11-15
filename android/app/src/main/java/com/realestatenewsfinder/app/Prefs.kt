package com.realestatenewsfinder.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify", false)
        set(v) = sp.edit().putBoolean("notify", v).apply()

    var minScore: Int
        get() = sp.getInt("minScore", 55)
        set(v) = sp.edit().putInt("minScore", v).apply()

    var keywordsText: String
        get() = sp.getString("keywords", "") ?: ""
        set(v) = sp.edit().putString("keywords", v).apply()

    val keywords: List<String>
        get() = keywordsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 알림을 받지 않을 소스 (기본: 전부 받음) */
    var mutedSources: Set<String>
        get() = sp.getStringSet("muted", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("muted", v).apply()

    var intervalMin: Int
        get() = sp.getInt("interval", 30)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** 요약용 Gemini API 키 (사용자가 구글 AI Studio에서 무료 발급) */
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) { sp.edit().putString("geminiKey", v.trim()).putString("geminiModel", "").putString("geminiModels", "").putLong("geminiCooldown", 0).apply() }

    /** 시도할 Gemini 모델 후보 (쉼표 구분) */
    var geminiModels: String
        get() = sp.getString("geminiModels", "") ?: ""
        set(v) = sp.edit().putString("geminiModels", v).apply()

    var geminiModel: String
        get() = sp.getString("geminiModel", "") ?: ""
        set(v) = sp.edit().putString("geminiModel", v).apply()

    var geminiCooldownUntil: Long
        get() = sp.getLong("geminiCooldown", 0)
        set(v) = sp.edit().putLong("geminiCooldown", v).apply()

    /** 요약용 Groq API 키 (무료 발급). Gemini 다음 순서로 사용 */
    var groqKey: String
        get() = sp.getString("groqKey", "") ?: ""
        set(v) { sp.edit().putString("groqKey", v.trim()).putString("groqModel", "").putLong("groqCooldown", 0).apply() }

    var groqModel: String
        get() = sp.getString("groqModel", "") ?: ""
        set(v) = sp.edit().putString("groqModel", v).apply()

    var groqCooldownUntil: Long
        get() = sp.getLong("groqCooldown", 0)
        set(v) = sp.edit().putLong("groqCooldown", v).apply()

    // 예비 요약 AI (ExtraAi: openrouter) — 키를 바꾸면 모델·쉬는 시간 초기화
    fun aiKey(id: String): String = sp.getString("ai.$id.key", "") ?: ""
    fun setAiKey(id: String, v: String) = sp.edit().putString("ai.$id.key", v.trim()).putString("ai.$id.model", "").putString("ai.$id.models", "").putLong("ai.$id.cooldown", 0).apply()
    fun aiModel(id: String): String = sp.getString("ai.$id.model", "") ?: ""
    fun setAiModel(id: String, v: String) = sp.edit().putString("ai.$id.model", v).apply()
    fun aiModels(id: String): String = sp.getString("ai.$id.models", "") ?: ""
    fun setAiModels(id: String, v: String) = sp.edit().putString("ai.$id.models", v).apply()
    fun aiCooldown(id: String): Long = sp.getLong("ai.$id.cooldown", 0)
    fun setAiCooldown(id: String, v: Long) = sp.edit().putLong("ai.$id.cooldown", v).apply()

    /** 스레드 글 말투: true = 반말(기본, 스레드에서 흔한 말투), false = 존댓말 */
    var casualTone: Boolean
        get() = sp.getBoolean("casualTone", true)
        set(v) = sp.edit().putBoolean("casualTone", v).apply()

    /** 워터마크 둘째 줄: 내 계정 이름. 비우면 표시 안 함 */
    var cardHandle: String
        get() = sp.getString("cardHandle", "@buals_zip") ?: ""
        set(v) = sp.edit().putString("cardHandle", v.trim()).apply()

    /** 뉴스·정부 글을 스레드로 보낼 때 본문 끝에 원문 링크를 넣을지 */
    var includeLink: Boolean
        get() = sp.getBoolean("includeLink", true)
        set(v) = sp.edit().putBoolean("includeLink", v).apply()

    /** 워터마크 첫 줄 문구. 비우면 표시 안 함 */
    var cardTagline: String
        get() = sp.getString("cardTagline", "부동산 알려주는 스레드") ?: ""
        set(v) = sp.edit().putString("cardTagline", v.trim()).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboardingDone", false)
        set(v) = sp.edit().putBoolean("onboardingDone", v).apply()

    var feedUrl: String
        get() = sp.getString("feedUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FEED_URL
        set(v) = sp.edit().putString("feedUrl", v.trim()).apply()

    /** 이미 확인한 글 id. null이면 아직 기준점을 잡지 않은 상태 */
    var seenIds: Set<String>?
        get() = sp.getStringSet("seen", null)
        set(v) = sp.edit().putStringSet("seen", v).apply()
}
