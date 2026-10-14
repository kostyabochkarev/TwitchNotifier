package com.example.twitchnotifier

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.twitchnotifier.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {

    companion object {
        const val REDIRECT_URI = "http://localhost"
    }

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

        b.btnClientId.setOnClickListener { askClientId() }
        b.btnConnect.setOnClickListener { startOAuth() }
        b.btnAdd.setOnClickListener { askAddChannel() }
        b.btnStop.setOnClickListener {
            TwitchService.stop(this)
            toast("Служба остановлена")
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val cid = applicationContext.clientId
        b.tvClientId.text = if (cid.isNullOrEmpty()) "Client ID не задан" else "Client ID: $cid"
        val token = applicationContext.accessToken
        b.tvStatus.text = if (token.isNullOrEmpty()) "Не авторизован" else "Авторизован"
        adapter.submit(Storage.getChannels(this))
    }

    private fun askClientId() {
        val et = EditText(this).apply {
            hint = "Client ID из dev.twitch.tv"
            setText(applicationContext.clientId ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Twitch Client ID")
            .setMessage("Создай приложение на dev.twitch.tv/console/apps\nOAuth Redirect URL: $REDIRECT_URI")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                applicationContext.clientId = et.text.toString().trim()
                refreshUi()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startOAuth() {
        val cid = applicationContext.clientId
        if (cid.isNullOrEmpty()) {
            toast("Сначала задай Client ID")
            return
        }

        val verifier = randomVerifier()
        applicationContext.codeVerifier = verifier
        val challenge = sha256Base64Url(verifier)

        val url = "https://id.twitch.tv/oauth2/authorize" +
                "?client_id=$cid" +
                "&redirect_uri=${Uri.encode(REDIRECT_URI)}" +
                "&response_type=code" +
                "&code_challenge=$challenge" +
                "&code_challenge_method=S256"

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Авторизация в Twitch")
            .setView(wv)
            .setNegativeButton("Отмена", null)
            .create()

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.startsWith(REDIRECT_URI)) {
                    val parsed = Uri.parse(u)
                    val code = parsed.getQueryParameter("code")
                    val err = parsed.getQueryParameter("error_description")
                    dialog.dismiss()
                    if (code != null) exchange(code)
                    else if (err != null) toast("Ошибка: $err")
                    return true
                }
                return false
            }
        }
        dialog.show()
        wv.loadUrl(url)
    }

    private fun exchange(code: String) {
        val cid = applicationContext.clientId ?: return
        val verifier = applicationContext.codeVerifier ?: return
        lifecycleScope.launch {
            try {
                val resp = TwitchApi.exchangeCode(cid, code, verifier, REDIRECT_URI)
                val token = resp.getString("access_token")
                applicationContext.accessToken = token
                toast("Авторизация успешна")
                refreshUi()
                TwitchService.reload(this@MainActivity)
            } catch (e: Exception) {
                toast("Ошибка обмена: ${e.message}")
            }
        }
    }

    private fun askAddChannel() {
        val cid = applicationContext.clientId
        val token = applicationContext.accessToken
        if (cid.isNullOrEmpty() || token.isNullOrEmpty()) {
            toast("Сначала подключись к Twitch")
            return
        }
        val et = EditText(this).apply { hint = "логин канала, например shroud" }
        AlertDialog.Builder(this)
            .setTitle("Добавить канал")
            .setView(et)
            .setPositiveButton("Добавить") { _, _ ->
                val login = et.text.toString().trim()
                if (login.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        val ch = TwitchApi.resolveChannel(cid, token, login)
                        val list = Storage.getChannels(this@MainActivity)
                        if (list.any { it.id == ch.id }) {
                            toast("Уже в списке")
                            return@launch
                        }
                        list.add(ch)
                        Storage.saveChannels(this@MainActivity, list)
                        adapter.submit(list)
                        TwitchService.reload(this@MainActivity)
                        toast("${ch.displayName} добавлен")
                    } catch (e: Exception) {
                        toast("Ошибка: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmRemove(ch: Channel) {
        AlertDialog.Builder(this)
            .setTitle("Удалить ${ch.displayName}?")
            .setPositiveButton("Удалить") { _, _ ->
                val list = Storage.getChannels(this).filterNot { it.id == ch.id }.toMutableList()
                Storage.saveChannels(this, list)
                adapter.submit(list)
                TwitchService.reload(this)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun sha256Base64Url(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return Base64.encodeToString(
            d, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
