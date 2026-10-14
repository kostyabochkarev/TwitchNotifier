package com.example.twitchnotifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Channel(val id: String, val login: String, val displayName: String)

object Storage {
    private const val P = "twitch_notifier"
    private const val K_CLIENT = "client_id"
    private const val K_TOKEN = "access_token"
    private const val K_CHANNELS = "channels"
    private const val K_VERIFIER = "code_verifier"

    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    var Context.clientId: String?
        get() = sp(this).getString(K_CLIENT, null)
        set(v) = sp(this).edit().putString(K_CLIENT, v).apply()

    var Context.accessToken: String?
        get() = sp(this).getString(K_TOKEN, null)
        set(v) = sp(this).edit().putString(K_TOKEN, v).apply()

    var Context.codeVerifier: String?
        get() = sp(this).getString(K_VERIFIER, null)
        set(v) = sp(this).edit().putString(K_VERIFIER, v).apply()

    fun getChannels(c: Context): MutableList<Channel> {
        val raw = sp(c).getString(K_CHANNELS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Channel(o.getString("id"), o.getString("login"), o.getString("display_name")))
        }
        return out
    }

    fun saveChannels(c: Context, list: List<Channel>) {
        val arr = JSONArray()
        for (ch in list) {
            arr.put(JSONObject().apply {
                put("id", ch.id)
                put("login", ch.login)
                put("display_name", ch.displayName)
            })
        }
        sp(c).edit().putString(K_CHANNELS, arr.toString()).apply()
    }
}
