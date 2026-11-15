package com.realestatenewsfinder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 최근 본 글 (글 정보를 그대로 저장해서 피드에서 빠져도 볼 수 있음) */
class PostStore(ctx: Context, name: String, private val max: Int) {
    private val file = File(ctx.applicationContext.filesDir, "$name.json")

    fun all(): List<Post> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { Post.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** 맨 앞에 추가 (이미 있으면 앞으로 이동) */
    fun add(post: Post) = save(listOf(post) + all().filter { it.id != post.id })

    fun clear() = save(emptyList())

    private fun save(list: List<Post>) {
        val arr = JSONArray()
        list.take(max).forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    companion object {
        fun recent(ctx: Context) = PostStore(ctx, "recent", 100)
        fun toJson(p: Post): JSONObject = p.toJson()
        fun fromJson(o: JSONObject) = Post.fromJson(o)
    }
}
