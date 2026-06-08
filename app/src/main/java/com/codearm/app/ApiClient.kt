package com.codearm.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String
)

object ApiClient {
    const val BASE_URL =
        "https://7977cb2d-e439-4454-9b48-667c86887c36-00-1ebj8xtpb3xtu.worf.replit.dev"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun stream(
        messages: List<ChatMessage>,
        mode: String,
        onToken: (String) -> Unit,
        onThink: (String) -> Unit = {},
        onDone: () -> Unit,
        onError: (String) -> Unit
    ): Call {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val bodyStr = JSONObject()
            .put("messages", arr)
            .put("mode", mode)
            .toString()

        val request = Request.Builder()
            .url("$BASE_URL/api/chat")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onError(e.message ?: "فشل الاتصال")
            }

            override fun onResponse(call: Call, response: Response) {
                val src = response.body?.source()
                if (!response.isSuccessful || src == null) {
                    onError("خطأ ${response.code}: ${response.message}")
                    return
                }
                try {
                    while (!src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        if (line.startsWith("data: ")) {
                            val json = line.removePrefix("data: ").trim()
                            if (json == "[DONE]" || json.isEmpty()) continue
                            runCatching {
                                val obj = JSONObject(json)
                                val token   = obj.optString("token", "")
                                val thought = obj.optString("thought", "")
                                val done    = obj.optBoolean("done", false)
                                if (thought.isNotEmpty()) onThink(thought)
                                if (token.isNotEmpty())   onToken(token)
                                if (done) return
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!call.isCanceled()) onError(e.message ?: "انقطع الاتصال")
                } finally {
                    onDone()
                    runCatching { response.close() }
                }
            }
        })
        return call
    }
}
