package com.example.twitchnotifier

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Channel(val login: String, val displayName: String)

private const val P = "twitch_notifier"
private const val K_CHANNELS = "channels"
private const val K_LIVE = "live_state"

private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

object Storage {

    fun getChannels(c: Context): MutableList<Channel> {
        val raw = sp(c).getString(K_CHANNELS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Channel(o.getString("login"), o.getString("display_name")))
        }
        return out
    }

    fun saveChannels(c: Context, list: List<Channel>) {
        val arr = JSONArray()
        for (ch in list) {
            arr.put(JSONObject().apply {
                put("login", ch.login)
                put("display_name", ch.displayName)
            })
        }
        sp(c).edit().putString(K_CHANNELS, arr.toString()).apply()
    }

    /** true = канал сейчас в эфире (чтобы не спамить уведомлениями) */
    fun isLive(c: Context, login: String): Boolean {
        val raw = sp(c).getString(K_LIVE, "{}") ?: "{}"
        return JSONObject(raw).optBoolean(login, false)
    }

    fun setLive(c: Context, login: String, live: Boolean) {
        val raw = sp(c).getString(K_LIVE, "{}") ?: "{}"
        val obj = JSONObject(raw)
        obj.put(login, live)
        sp(c).edit().putString(K_LIVE, obj.toString()).apply()
    }
}
