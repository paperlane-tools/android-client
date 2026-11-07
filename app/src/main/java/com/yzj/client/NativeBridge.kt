package com.yzj.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class NativeBridge(
    private val activity: AccountActivity,
    private val webView: WebView,
    private val slot: Int
) {
    private val prefs = activity.getSharedPreferences("yzj_gm", Context.MODE_PRIVATE)
    private val pool = Executors.newCachedThreadPool()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()

    private fun key(name: String) = "slot$slot:$name"

    @JavascriptInterface
    fun getValue(name: String?): String? {
        if (name == null) return null
        // Secret values are never stored in plaintext SharedPreferences.
        if (name == "apiKey") {
            val value = SecureStore.get(activity, SecureStore.slotApiKeyName(slot))
                ?: return null
            return JSONObject.quote(value)
        }
        if (name == "answerBank" || name == "bankNotes") {
            // GM_getValue parses this return value with JSON.parse. The bank
            // file already contains JSON, so returning it raw yields an object;
            // quoting it would turn the whole bank into one long string.
            return BankStore.readValue(activity, slot, name)
        }
        return prefs.getString(key(name), null)
    }

    @JavascriptInterface
    fun setValue(name: String?, value: String?) {
        if (name == null) return
        if (name == "apiKey") {
            val raw = value?.let {
                runCatching { JSONObject("{\"v\":$it}").optString("v") }.getOrNull()
            } ?: ""
            SecureStore.put(activity, SecureStore.slotApiKeyName(slot), raw)
            return
        }
        if (name == "answerBank" || name == "bankNotes") {
            if (value.isNullOrBlank() || value == "null") {
                BankStore.removeValue(activity, slot, name)
            } else {
                runCatching { JSONObject(value) }
                    .onSuccess { BankStore.writeValue(activity, slot, name, value) }
                    .onFailure {
                        activity.appendLog("error", "题库写入失败：${it.message}")
                    }
            }
            return
        }
        prefs.edit().putString(key(name), value).apply()
    }

    @JavascriptInterface
    fun setClipboard(text: String?) {
        activity.runOnUiThread {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("yzj", text ?: ""))
        }
    }

    @JavascriptInterface
    fun log(level: String?, message: String?) {
        Log.i(TAG, "slot$slot ${level.orEmpty()} ${message.orEmpty()}")
        activity.appendLog(level ?: "info", message ?: "")
    }

    @JavascriptInterface
    fun toast(message: String?) {
        activity.showToastFromJs(message ?: "")
    }

    @JavascriptInterface
    fun status(message: String?) {
        activity.setStatusFromJs(message ?: "")
    }

    @JavascriptInterface
    fun abortXhr(id: String?) {
        id?.let { connections.remove(it)?.disconnect() }
    }

    @JavascriptInterface
    fun xhr(id: String?, method: String?, url: String?, headersJson: String?,
            body: String?, timeoutMs: Int) {
        val requestId = id ?: return
        val target = url ?: return
        pool.execute {
            var conn: HttpURLConnection? = null
            val started = System.currentTimeMillis()
            try {
                conn = URL(target).openConnection() as HttpURLConnection
                connections[requestId] = conn
                conn.requestMethod = (method ?: "GET").uppercase()
                val timeout = timeoutMs.takeIf { it > 0 }?.coerceIn(3000, 120000) ?: 30000
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("User-Agent", UA)

                if (!headersJson.isNullOrBlank()) {
                    val headers = JSONObject(headersJson)
                    headers.keys().forEach { h -> conn.setRequestProperty(h, headers.optString(h)) }
                }
                if (!body.isNullOrEmpty()) {
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }

                val status = conn.responseCode
                activity.appendLog("net",
                    "${(method ?: "GET").uppercase()} ${safeUrl(target)} → $status " +
                        "(${System.currentTimeMillis() - started}ms)")
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                val responseHeaders = JSONObject()
                conn.headerFields.forEach { (name, values) ->
                    if (name != null && values != null) {
                        responseHeaders.put(name, values.joinToString(", "))
                    }
                }
                connections.remove(requestId)
                callback("__yzjXhrDone", requestId, status.toString(), text,
                    responseHeaders.toString())
            } catch (e: Exception) {
                connections.remove(requestId)
                activity.appendLog("error",
                    "${(method ?: "GET").uppercase()} ${safeUrl(target)} → " +
                        (e.message ?: e.javaClass.simpleName))
                callback("__yzjXhrFail", requestId, e.message ?: e.javaClass.simpleName)
            } finally {
                conn?.disconnect()
            }
        }
    }

    @JavascriptInterface
    fun saveText(filename: String?, text: String?) {
        runCatching {
            val dir = File(
                activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "yzj-export"
            )
            if (!dir.isDirectory && !dir.mkdirs()) error("mkdir failed")
            val safe = (filename ?: "yzj-export.txt")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "yzj-export.txt" }
            val out = File(dir, safe)
            FileOutputStream(out).use { it.write((text ?: "").toByteArray(StandardCharsets.UTF_8)) }
            activity.showToastFromJs("已保存：${out.absolutePath}")
        }.onFailure {
            activity.showToastFromJs("保存失败：${it.message}")
        }
    }

    private fun callback(function: String, vararg args: String) {
        val script = buildString {
            append("window.").append(function).append("(")
            args.forEachIndexed { index, arg ->
                if (index > 0) append(',')
                append(JSONObject.quote(arg))
            }
            append(");")
        }
        webView.post {
            runCatching { webView.evaluateJavascript(script, null) }
        }
    }

    private fun safeUrl(raw: String): String = runCatching {
        val url = URL(raw)
        val path = url.path.orEmpty().take(90)
        url.host + path
    }.getOrDefault(raw.take(120))

    companion object {
        private const val TAG = "YZJ_BRIDGE"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
    }
}
