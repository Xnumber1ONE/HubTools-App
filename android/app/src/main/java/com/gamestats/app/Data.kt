package com.gamestats.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // "local" = PC on the same Wi-Fi, "github" = private GitHub repo, "" = not chosen yet
    var mode: String
        get() = p.getString("mode", "") ?: ""
        set(v) { p.edit().putString("mode", v).apply() }

    // local PC
    var host: String
        get() = p.getString("host", "") ?: ""
        set(v) { p.edit().putString("host", v.trim()).apply() }
    var port: Int
        get() = p.getInt("port", 8787)
        set(v) { p.edit().putInt("port", v).apply() }
    var code: String
        get() = p.getString("code", "") ?: ""
        set(v) { p.edit().putString("code", v.trim()).apply() }
    var serverId: String
        get() = p.getString("serverId", "") ?: ""
        set(v) { p.edit().putString("serverId", v).apply() }
    var pcName: String
        get() = p.getString("pcName", "") ?: ""
        set(v) { p.edit().putString("pcName", v).apply() }

    // GitHub
    var repo: String
        get() = p.getString("repo", "") ?: ""
        set(v) { p.edit().putString("repo", v.trim()).apply() }
    var branch: String
        get() = p.getString("branch", "main") ?: "main"
        set(v) { p.edit().putString("branch", v.trim().ifEmpty { "main" }).apply() }
    var path: String
        get() = p.getString("path", "stats.json") ?: "stats.json"
        set(v) { p.edit().putString("path", v.trim().ifEmpty { "stats.json" }).apply() }
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) { p.edit().putString("token", v.trim()).apply() }

    val githubReady: Boolean get() = repo.contains("/") && token.isNotBlank()
    val localReady: Boolean get() = host.isNotBlank() && code.isNotBlank()

    // apps set up before the PC mode existed have no mode saved and keep using GitHub
    val useGithub: Boolean get() = if (mode.isNotEmpty()) mode == "github" else githubReady
}

class Repository(private val context: Context, val settings: Settings) {
    private val cacheFile get() = File(context.filesDir, "snapshot.json")

    fun cached(): Snapshot? = runCatching { parseSnapshot(cacheFile.readText()) }.getOrNull()

    suspend fun fetch(): Snapshot = withContext(Dispatchers.IO) {
        val text = if (settings.useGithub) fetchGithub() else fetchLocal()
        val snap = parseSnapshot(text)
        cacheFile.writeText(text)
        snap
    }

    private suspend fun fetchLocal(): String {
        val s = settings
        try {
            return httpGet("http://${s.host}:${s.port}/stats.json", "X-Code", s.code)
        } catch (e: IOException) {
            // the PC may have a new address: look for it again by its id
            val pc = if (s.serverId.isNotEmpty()) Discovery.scan().firstOrNull { it.id == s.serverId } else null
            if (pc == null) {
                throw IOException("Can't reach your PC. Is HubTools PC running, and is this phone on the same Wi-Fi?")
            }
            s.host = pc.host
            s.port = pc.port
            return httpGet("http://${s.host}:${s.port}/stats.json", "X-Code", s.code)
        }
    }

    private fun fetchGithub(): String {
        val s = settings
        return httpGet(
            "https://api.github.com/repos/${s.repo}/contents/${s.path}?ref=${s.branch}",
            "Authorization", "Bearer ${s.token}",
            accept = "application/vnd.github.raw+json",
        )
    }

    private fun httpGet(url: String, headerName: String, headerValue: String, accept: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty(headerName, headerValue)
        if (accept != null) c.setRequestProperty("Accept", accept)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.connectTimeout = 4000
        c.readTimeout = 60000
        return try {
            when (val code = c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                401 -> error(if (settings.useGithub) "GitHub 401: token rejected" else "Wrong code. Check the code shown in the PC window.")
                429 -> error("Too many wrong codes. Wait 5 minutes.")
                404 -> error("Not found (404). Check the settings.")
                else -> error("Server answered $code")
            }
        } finally {
            c.disconnect()
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app, Settings(app))
    val settings: Settings get() = repo.settings

    // list controls live here so they survive opening a game and coming back
    var sort by mutableStateOf(Sort.RECENT)
    var sortDesc by mutableStateOf(true)
    var filter by mutableStateOf(StatusFilter.ALL)
    var query by mutableStateOf("")

    var snapshot by mutableStateOf<Snapshot?>(repo.cached())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val configured: Boolean
        get() = if (settings.useGithub) settings.githubReady else settings.localReady

    init { refresh() }

    fun refresh() {
        if (loading || !configured) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                snapshot = repo.fetch()
            } catch (e: Exception) {
                error = (e.message ?: "Sync failed") + if (snapshot != null) " (showing saved data)" else ""
            }
            loading = false
        }
    }
}
