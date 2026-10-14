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

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()
    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    suspend fun exchangeCode(clientId: String, code: String, verifier: String, redirectUri: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = ("client_id=$clientId" +
                    "&code=$code" +
                    "&code_verifier=$verifier" +
                    "&grant_type=authorization_code" +
                    "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, "UTF-8"))
                .toRequestBody(FORM)
            val req = Request.Builder()
                .url("https://id.twitch.tv/oauth2/token")
                .post(body)
                .build()
            http.newCall(req).execute().use {
                val text = it.body!!.string()
                if (!it.isSuccessful) throw RuntimeException("${it.code}: $text")
                JSONObject(text)
            }
        }

    suspend fun resolveChannel(clientId: String, token: String, login: String): Channel =
        withContext(Dispatchers.IO) {
            val clean = login.trim().removePrefix("@").lowercase()
            if (clean.isEmpty()) throw RuntimeException("Пустой логин")
            val req = Request.Builder()
                .url("https://api.twitch.tv/helix/users?login=$clean")
                .header("Client-Id", clientId)
                .header("Authorization", "Bearer $token")
                .build()
            val json = http.newCall(req).execute().use { JSONObject(it.body!!.string()) }
            val data = json.optJSONArray("data") ?: throw RuntimeException("Пустой ответ Twitch")
            if (data.length() == 0) throw RuntimeException("Канал «$clean» не найден")
            val u = data.getJSONObject(0)
            Channel(u.getString("id"), u.getString("login"), u.getString("display_name"))
        }

    suspend fun subscribeStreamOnline(
        clientId: String, token: String, sessionId: String, broadcasterId: String
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("type", "stream.online")
            put("version", "1")
            put("condition", JSONObject().put("broadcaster_user_id", broadcasterId))
            put("transport", JSONObject().apply {
                put("method", "websocket")
                put("session_id", sessionId)
            })
        }.toString().toRequestBody(JSON)

        val req = Request.Builder()
            .url("https://api.twitch.tv/helix/eventsub/subscriptions")
            .header("Client-Id", clientId)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body!!.string()
            if (!resp.isSuccessful) throw RuntimeException("${resp.code}: $text")
            JSONObject(text).getJSONArray("data").getJSONObject(0).getString("id")
        }
    }
}
