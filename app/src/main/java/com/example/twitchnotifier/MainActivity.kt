package com.example.twitchnotifier

import android.Manifest
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.twitchnotifier.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        adapter = ChannelAdapter { ch -> confirmRemove(ch) }
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.btnAdd.setOnClickListener { askAddChannel() }
        b.btnStop.setOnClickListener {
            TwitchService.stop(this)
            toast("Служба остановлена")
        }

        refreshUi()
        TwitchService.reload(this)
        refreshAvatars()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val channels = Storage.getChannels(this)
        adapter.submit(channels)
        b.tvStatus.text = if (channels.isEmpty()) "Список пуст" else "Отслеживается: ${channels.size}"
    }

    /** Тихо обновляет имена и аватарки всех каналов в фоне */
    private fun refreshAvatars() {
        lifecycleScope.launch {
            val current = Storage.getChannels(this@MainActivity)
            if (current.isEmpty()) return@launch
            val updated = mutableListOf<Channel>()
            for (ch in current) {
                val info = withContext(Dispatchers.IO) { TwitchApi.fetchChannelInfo(ch.login) }
                updated.add(info ?: ch)
            }
            Storage.saveChannels(this@MainActivity, updated)
            refreshUi()
        }
    }

    private fun askAddChannel() {
        val et = EditText(this).apply { hint = "логин канала, например shroud" }
        AlertDialog.Builder(this)
            .setTitle("Добавить канал")
            .setView(et)
            .setPositiveButton("Добавить") { _, _ ->
                val login = et.text.toString().trim().removePrefix("@").lowercase()
                if (login.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    toast("Проверяю канал…")
                    val info = TwitchApi.fetchChannelInfo(login)
                    if (info == null) {
                        toast("Канал «$login» не найден")
                        return@launch
                    }
                    val list = Storage.getChannels(this@MainActivity)
                    if (list.any { it.login.equals(info.login, ignoreCase = true) }) {
                        toast("Уже в списке")
                        return@launch
                    }
                    list.add(info)
                    Storage.saveChannels(this@MainActivity, list)
                    refreshUi()
                    TwitchService.reload(this@MainActivity)
                    toast("${info.displayName} добавлен")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmRemove(ch: Channel) {
        AlertDialog.Builder(this)
            .setTitle("Удалить ${ch.displayName}?")
            .setPositiveButton("Удалить") { _, _ ->
                val list = Storage.getChannels(this).filterNot {
                    it.login.equals(ch.login, ignoreCase = true)
                }.toMutableList()
                Storage.saveChannels(this, list)
                Storage.setLive(this, ch.login, false)
                refreshUi()
                TwitchService.reload(this)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
