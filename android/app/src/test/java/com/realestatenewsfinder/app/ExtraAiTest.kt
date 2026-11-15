package com.realestatenewsfinder.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraAiTest {
    @Test
    fun openRouterPicksOnlyFreeGeneralModels() {
        val data = JSONObject("""{"data":[
            {"id":"openai/gpt-4o","context_length":128000},
            {"id":"meta-llama/llama-3.3-70b-instruct:free","context_length":65536},
            {"id":"qwen/qwen-2.5-coder-32b-instruct:free","context_length":32768},
            {"id":"google/gemma-3-27b-it:free","context_length":96000},
            {"id":"meta-llama/llama-3.2-3b-instruct:free","context_length":20000}]}""").getJSONArray("data")
        assertEquals(listOf("google/gemma-3-27b-it:free", "meta-llama/llama-3.3-70b-instruct:free"), ExtraAi.rankOpenRouter(data))
    }

    @Test
    fun cerebrasPrefersLargeGeneralModel() {
        val data = JSONObject("""{"data":[{"id":"llama3.1-8b"},{"id":"llama-3.3-70b"},{"id":"qwen-3-32b"}]}""").getJSONArray("data")
        assertEquals("llama-3.3-70b", ExtraAi.rankCerebras(data).first())
    }
}
