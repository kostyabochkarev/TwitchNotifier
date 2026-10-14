package com.example.twitchnotifier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TwitchApi {

    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private const val GQL_URL = "https://gql.twitch.tv/gql"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    suspend fun isStreamLive(login: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("query", """
                query {
                  user(login: "$login") {
                    stream { id type }
                  }
                }
            """.trimIndent())
        }.toString().toRequestBody(JSON)

        val req = Request.Builder()
            .url(GQL_URL)
            .header("Client-ID", CLIENT_ID)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@withContext false
                if (!resp.isSuccessful) return@withContext false
                val json = JSONObject(text)
                val data = json.optJSONObject("data") ?: return@withContext false
                val user = data.optJSONObject("user") ?: return@withContext false
                user.optJSONObject("stream") != null
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Возвращает имя и аватар канала, либо null если канал не найден */
    suspend fun fetchChannelInfo(login: String): Channel? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("query", """
                query {
                  user(login: "$login") {
                    login
                    displayName
                    profileImageURL(width: 300)
                  }
                }
            """.trimIndent())
        }.toString().toRequestBody(JSON)

        val req = Request.Builder()
            .url(GQL_URL)
            .header("Client-ID", CLIENT_ID)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@withContext null
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(text)
                val data = json.optJSONObject("data") ?: return@withContext null
                val user = data.optJSONObject("user") ?: return@withContext null
                Channel(
                    login = user.getString("login"),
                    displayName = user.getString("displayName"),
                    avatarUrl = user.optString("profileImageURL", "")
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
