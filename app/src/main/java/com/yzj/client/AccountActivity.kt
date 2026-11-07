package com.yzj.client

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class AccountActivity : Activity() {
    protected abstract fun slot(): Int

    private var webView: WebView? = null
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logPanel: LinearLayout
    private lateinit var logSummary: TextView
    private data class LogEntry(val time: String, val level: String, val message: String)
    private val logEntries = mutableListOf<LogEntry>()
    private val logChips = mutableMapOf<String, TextView>()
    private val recentLogKeys = mutableMapOf<String, Long>()
    private var logFilter = "all"
    private val uiHandler = Handler(Looper.getMainLooper())
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private var lastInjectedUrl = ""
    private var accountSlot = 0
    private var keepScreenOn = false
    private var logMode = false
    private var receiverRegistered = false
    private var vaultRestoreTried = false
    private var qrBridgeAt = 0L
    private var resetInProgress = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.getIntExtra("slot", accountSlot) != accountSlot) return
            when (intent.action) {
                ACTION_STOP -> finish()
                ACTION_RESET -> resetInstance()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountSlot = slot()
        if (hasPendingReset()) preparePendingReset()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        status = Ui.text(this, "实例 ${accountSlot + 1}：准备加载…", 12, Ui.TEXT).apply {
            setPadding(Ui.dp(this@AccountActivity, 14), Ui.dp(this@AccountActivity, 10),
                Ui.dp(this@AccountActivity, 14), Ui.dp(this@AccountActivity, 4))
        }
        root.addView(status)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@AccountActivity, 8), 0,
                Ui.dp(this@AccountActivity, 8), Ui.dp(this@AccountActivity, 6))
        }
        val toolbarScroll = HorizontalScrollView(this).apply { addView(toolbar) }
        root.addView(toolbarScroll)

        toolbar.addView(toolButton("返回") { webView?.let { if (it.canGoBack()) it.goBack() } })
        toolbar.addView(toolButton("刷新") { webView?.reload() })
        toolbar.addView(toolButton("任务") { webView?.loadUrl(TASK_URL) })
        toolbar.addView(toolButton("资料") { webView?.loadUrl(MATERIAL_URL) })
        toolbar.addView(toolButton("扫码登录") {
            QrLogin.show(this, accountSlot) { webView?.loadUrl(TASK_URL) }
        })
        toolbar.addView(toolButton("导入文件") { importSession() })
        toolbar.addView(toolButton("粘贴登录态") { importSessionFromClipboard() })
        toolbar.addView(toolButton("导出登录态") { exportSession() })
        toolbar.addView(toolButton("清空登录态") { clearSession() })
        toolbar.addView(toolButton("日志模式") { setLogMode(!logMode) })
        toolbar.addView(toolButton("实例配置") {
            ConfigDialog.show(this, accountSlot)
        })
        toolbar.addView(toolButton("自检") { selfTest() })
        toolbar.addView(toolButton(if (keepScreenOn) "常亮开" else "常亮") { v ->
            keepScreenOn = !keepScreenOn
            if (keepScreenOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            (v as TextView).text = if (keepScreenOn) "常亮开" else "常亮"
        })
        toolbar.addView(toolButton("关闭") { finish() })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        webView = WebView(this).also { view ->
            content?.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            configureWebView(view)
            view.addJavascriptInterface(NativeBridge(this, view, accountSlot), "YzjNative")
        }

        logPanel = buildLogPanel()
        content?.addView(logPanel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        registerControlReceiver()
        loadInitialPage()
        uiHandler.post(statusTick)
        if (intent.getBooleanExtra("log_mode", false)) setLogMode(true)
        captureStatus()
    }

    private fun toolButton(label: String, onClick: (View) -> Unit): TextView =
        Ui.button(this, label, false).apply {
            textSize = 12f
            setPadding(Ui.dp(this@AccountActivity, 10), Ui.dp(this@AccountActivity, 7),
                Ui.dp(this@AccountActivity, 10), Ui.dp(this@AccountActivity, 7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = Ui.dp(this@AccountActivity, 4) }
            setOnClickListener { onClick(it) }
        }

    private fun configureWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = UA
        }
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                lastInjectedUrl = ""
                status?.text = "实例 ${accountSlot + 1}：加载中 ${safePage(url)}"
                if (resetInProgress) {
                    v.evaluateJavascript(
                        "try{sessionStorage.clear();localStorage.clear();}catch(e){}",
                        null
                    )
                }
            }

            override fun onPageFinished(v: WebView, url: String?) {
                status?.text = "实例 ${accountSlot + 1}：已加载 ${safePage(url)}"
                appendLog("info", "页面已加载 ${safePage(url)}")
                if (resetInProgress) {
                    // The login page can repopulate storage while loading. Clear
                    // once more after it settles, and never restore the vault.
                    v.evaluateJavascript(
                        "try{sessionStorage.clear();localStorage.clear();}catch(e){}"
                    ) {
                        CookieManager.getInstance().removeAllCookies {
                            CookieManager.getInstance().flush()
                            setPendingReset(false)
                            resetInProgress = false
                            vaultRestoreTried = true
                        }
                    }
                    v.postDelayed({ capturePreview(); captureStatus() }, 1500)
                    return
                }
                if (url != null && url.startsWith("https://www.csgmooc.com/")) {
                    injectScripts()
                    restoreVaultSessionIfNeeded()
                }
                v.postDelayed({ capturePreview(); captureStatus() }, 1500)
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest,
                                         error: WebResourceError) {
                if (request.isForMainFrame) {
                    status?.text = "实例 ${accountSlot + 1}：加载失败 " +
                        "${error.description} ${safePage(request.url.toString())}"
                }
            }

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "render process gone slot$accountSlot crashed=${detail.didCrash()}")
                Toast.makeText(this@AccountActivity,
                    "实例 ${accountSlot + 1} 的 WebView 已重启", Toast.LENGTH_LONG).show()
                recreate()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                Log.d(TAG, "slot$accountSlot console: ${cm.message()} @${cm.sourceId()}:${cm.lineNumber()}")
                if (shouldKeepConsole(cm.message())) {
                    val level = when (cm.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "error"
                        ConsoleMessage.MessageLevel.WARNING -> "warn"
                        else -> "console"
                    }
                    appendLog(level, cm.message())
                }
                return true
            }

            override fun onJsAlert(v: WebView, url: String?, message: String?,
                                   result: JsResult): Boolean {
                AlertDialog.Builder(this@AccountActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(v: WebView, url: String?, message: String?,
                                     result: JsResult): Boolean {
                AlertDialog.Builder(this@AccountActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(v: WebView, url: String?, message: String?,
                                    defaultValue: String?, result: JsPromptResult): Boolean {
                val input = EditText(this@AccountActivity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setText(defaultValue ?: "")
                }
                AlertDialog.Builder(this@AccountActivity)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
    }

    private fun injectScripts() {
        val view = webView ?: return
        val url = view.url ?: return
        if (url == lastInjectedUrl) return
        lastInjectedUrl = url
        val shim = "window.__YZJ_SLOT=$accountSlot;\n" + readAsset("shim.js")
        val quiz = readAsset("yzj-quiz.user.js")
        val mat = readAsset("yzj-mat.user.js")
        val all = "$shim\n;\n$quiz\n;\n$mat\n;"
        view.evaluateJavascript(all) { verifyInjection() }
    }

    private fun verifyInjection() {
        val view = webView ?: return
        view.postDelayed({
            view.evaluateJavascript(
                "JSON.stringify({quiz:(window.__yzj&&window.__yzj.VERSION)||null," +
                    "mat:(window.__yzjMat&&window.__yzjMat.VERSION)||null," +
                    "token:!!sessionStorage.getItem('reslibAuthtoken')," +
                    "bank:Object.keys(GM_getValue('answerBank',{})||{}).length," +
                    "href:location.href})"
            ) { value ->
                Log.i(TAG, "injection result slot$accountSlot: $value")
                val result = runCatching { JSONObject(unquote(value)) }.getOrNull()
                val quiz = result?.optString("quiz").cleanJsonString()
                val mat = result?.optString("mat").cleanJsonString()
                val bank = result?.optInt("bank", 0) ?: 0
                val token = result?.optBoolean("token") == true
                val detail = buildString {
                    append("脚本已注入")
                    if (quiz.isNotEmpty()) append(" · 作答 $quiz")
                    if (mat.isNotEmpty()) append(" · 资料 $mat")
                    if (bank > 0) append(" · 题库 $bank")
                    append(if (token) " · 已登录" else " · 未登录")
                }
                status?.text = "实例 ${accountSlot + 1}：$detail"
                appendLog("info", detail)
                captureStatus()
            }
        }, 2200)
    }

    private fun capturePreview() {
        if (logMode) return
        val view = webView ?: return
        view.post { PreviewStore.capture(this, view, accountSlot) }
    }

    private fun captureStatus() {
        if (resetInProgress) return
        val view = webView ?: return
        val js = "(function(){var o={};for(var i=0;i<sessionStorage.length;i++){" +
            "var k=sessionStorage.key(i);o[k]=sessionStorage.getItem(k);}" +
            "return JSON.stringify({values:o," +
            "quiz:(window.__yzj&&window.__yzj.VERSION)||null," +
            "mat:(window.__yzjMat&&window.__yzjMat.VERSION)||null," +
            "href:location.href});})()"
        view.evaluateJavascript(js) { value ->
            runCatching {
                val result = JSONObject(unquote(value))
                val values = result.optJSONObject("values") ?: JSONObject()
                val token = values.optString("reslibAuthtoken")
                if (token.isNotEmpty()) {
                    values.put("savedAt", System.currentTimeMillis())
                    SecureStore.putJson(this, SecureStore.sessionName(accountSlot), values)
                }
                val statusJson = JSONObject().apply {
                    put("slot", accountSlot)
                    put("alive", true)
                    put("updatedAt", System.currentTimeMillis())
                    put("token", token.isNotEmpty())
                    put("name", values.optString("login_name"))
                    put("stuId", values.optString("stuId"))
                    put("school", values.optString("schoolName"))
                    put("url", safePage(result.optString("href").cleanJsonString()))
                    put("quiz", result.optString("quiz").cleanJsonString())
                    put("mat", result.optString("mat").cleanJsonString())
                }
                StatusStore.write(this, accountSlot, statusJson)
            }.onFailure { Log.w(TAG, "captureStatus failed", it) }
        }
    }

    private fun restoreVaultSessionIfNeeded() {
        if (vaultRestoreTried) return
        val view = webView ?: return
        view.evaluateJavascript("sessionStorage.getItem('reslibAuthtoken')") { value ->
            if (value != null && value != "null" && value != "\"\"") return@evaluateJavascript
            vaultRestoreTried = true
            val session = SecureStore.getJson(this, SecureStore.sessionName(accountSlot)) ?: return@evaluateJavascript
            val token = session.optString("reslibAuthtoken")
            if (token.isNotEmpty() && session.optString("userInfo").isEmpty()) {
                view.loadUrl(ssoUrl(token, session.optInt("authType", 0)))
                return@evaluateJavascript
            }
            val js = "(function(){var d=$session;for(var k in d){" +
                "if(Object.prototype.hasOwnProperty.call(d,k)){" +
                "sessionStorage.setItem(k,String(d[k]));}}return true;})()"
            view.evaluateJavascript(js) { view.loadUrl(TASK_URL) }
        }
    }

    fun evaluateSessionScript(js: String) {
        webView?.evaluateJavascript(js) { webView?.loadUrl(TASK_URL) }
    }

    /** Finish a unified-portal QR login through the site's SSO bridge. */
    fun completeQrLogin(token: String, authType: Int = 0) {
        qrBridgeAt = System.currentTimeMillis()
        appendLog("info", "扫码确认成功，正在通过 SSO 桥接页完成登录")
        webView?.loadUrl(ssoUrl(token, authType))
        uiHandler.postDelayed({
            if (System.currentTimeMillis() - qrBridgeAt < 5000) return@postDelayed
            webView?.evaluateJavascript(
                "JSON.stringify({token:!!sessionStorage.getItem('reslibAuthtoken')," +
                    "userInfo:!!sessionStorage.getItem('userInfo'),href:location.href})"
            ) { value ->
                if (value != null && value.contains("\"userInfo\":false")) {
                    status?.text = "实例 ${accountSlot + 1}：扫码后未拿到用户信息"
                    toast("扫码桥接未拿到用户信息，请重新扫码；课堂/资源库模式要选对")
                }
            }
        }, 6500)
    }

    private fun ssoUrl(token: String, authType: Int): String =
        "$WHITE_BLANK?authType=${if (authType == 1) 1 else 0}" +
            "&aToken=${URLEncoder.encode(token, "UTF-8")}"

    private fun exportSession() {
        val view = webView ?: return
        val js = "(function(){var o={};for(var i=0;i<sessionStorage.length;i++){" +
            "var k=sessionStorage.key(i);o[k]=sessionStorage.getItem(k);}" +
            "return JSON.stringify(o);})()"
        view.evaluateJavascript(js) { value ->
            runCatching {
                copyToClipboard(unquote(value))
                Toast.makeText(this, "当前实例登录态已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importSessionFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            toast("剪贴板里没有文本")
            return
        }
        runCatching {
            val obj = JSONObject(text)
            if (obj.length() == 0) error("JSON 为空")
            obj.put("savedAt", System.currentTimeMillis())
            SecureStore.putJson(this, SecureStore.sessionName(accountSlot), obj)
            val js = "(function(){var d=$obj;var n=0;for(var k in d){" +
                "if(Object.prototype.hasOwnProperty.call(d,k)){" +
                "sessionStorage.setItem(k,String(d[k]));n++;}}return n;})()"
            webView?.evaluateJavascript(js) { webView?.loadUrl(TASK_URL) }
        }.onFailure { toast("剪贴板内容不是有效的登录态 JSON") }
    }

    private fun importSession() {
        val file = File(getExternalFilesDir(null), "session_storage.json")
        if (!file.isFile) {
            AlertDialog.Builder(this)
                .setTitle("没有找到登录态文件")
                .setMessage("请先执行：\nadb push session_storage.json \"${file.absolutePath}\"")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        runCatching {
            val obj = JSONObject(file.readText())
            if (obj.length() == 0) error("JSON 为空")
            obj.put("savedAt", System.currentTimeMillis())
            SecureStore.putJson(this, SecureStore.sessionName(accountSlot), obj)
            val js = "(function(){var d=$obj;var n=0;for(var k in d){" +
                "if(Object.prototype.hasOwnProperty.call(d,k)){" +
                "sessionStorage.setItem(k,String(d[k]));n++;}}return n;})()"
            webView?.evaluateJavascript(js) { webView?.loadUrl(TASK_URL) }
        }.onFailure {
            AlertDialog.Builder(this).setTitle("导入失败")
                .setMessage(it.message).setPositiveButton("确定", null).show()
        }
    }

    private fun clearSession() {
        AlertDialog.Builder(this)
            .setTitle("清空当前实例登录态？")
            .setMessage("会清空 sessionStorage、localStorage、Cookie 和加密保存的会话。")
            .setPositiveButton("清空") { _, _ -> resetInstance() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetInstance() {
        val view = webView
        setPendingReset(true)
        resetInProgress = true
        vaultRestoreTried = true
        SecureStore.remove(this, SecureStore.sessionName(accountSlot))
        StatusStore.delete(this, accountSlot)
        PreviewStore.delete(this, accountSlot)
        clearRuntimePrefs()
        setLogMode(false)
        if (view == null) {
            resetInProgress = false
            return
        }
        view.evaluateJavascript(
            "try{sessionStorage.clear();localStorage.clear();}catch(e){}"
        ) {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                view.loadUrl(LOGIN_URL)
            }
        }
        uiHandler.postDelayed({
            if (resetInProgress) view.loadUrl(LOGIN_URL)
        }, 3500)
        appendLog("warn", "实例已重置，回到登录页")
        toast("实例 ${accountSlot + 1} 已重置")
    }

    private fun loadInitialPage() {
        val view = webView ?: return
        if (!resetInProgress) {
            view.loadUrl(TASK_URL)
            return
        }
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            view.loadUrl(LOGIN_URL)
        }
    }

    private fun preparePendingReset() {
        resetInProgress = true
        vaultRestoreTried = true
        SecureStore.remove(this, SecureStore.sessionName(accountSlot))
        StatusStore.delete(this, accountSlot)
        PreviewStore.delete(this, accountSlot)
        clearRuntimePrefs()
    }

    private fun hasPendingReset(): Boolean =
        getSharedPreferences("yzj_gm", MODE_PRIVATE)
            .getBoolean("slot$accountSlot:pendingReset", false)

    private fun setPendingReset(pending: Boolean) {
        getSharedPreferences("yzj_gm", MODE_PRIVATE).edit()
            .putBoolean("slot$accountSlot:pendingReset", pending)
            .commit()
    }

    private fun clearRuntimePrefs() {
        val prefs = getSharedPreferences("yzj_gm", MODE_PRIVATE)
        val editor = prefs.edit()
        val prefix = "slot$accountSlot:"
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix) &&
                (key.endsWith(":pendingFinalize") ||
                    key.endsWith(":lastTask") ||
                    key.endsWith(":uaState") ||
                    key.endsWith(":submitQueue"))
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    private fun selfTest() {
        val view = webView ?: return
        val js = "(function(){var slot=window.__YZJ_SLOT;var probe='slot'+slot;" +
            "var out={slot:slot,prevSession:sessionStorage.getItem('yzj_probe')," +
            "prevLocal:localStorage.getItem('yzj_probe')," +
            "bank:Object.keys(GM_getValue('answerBank',{})||{}).length};" +
            "sessionStorage.setItem('yzj_probe',probe);localStorage.setItem('yzj_probe',probe);" +
            "out.session=sessionStorage.getItem('yzj_probe');out.local=localStorage.getItem('yzj_probe');" +
            "YzjNative.log('probe',JSON.stringify(out));" +
            "try{GM_xmlhttpRequest({method:'GET',url:'https://api.deepseek.com/models',timeout:15000," +
            "onload:function(r){out.xhr=r.status;YzjNative.log('probe',JSON.stringify(out));" +
            "YzjNative.status('自检 '+JSON.stringify(out));}," +
            "onerror:function(e){out.xhr='ERR '+e.error;YzjNative.log('probe',JSON.stringify(out));" +
            "YzjNative.status('自检 '+JSON.stringify(out));}});}" +
            "catch(e){out.xhr='EX '+e;YzjNative.log('probe',JSON.stringify(out));" +
            "YzjNative.status('自检 '+JSON.stringify(out));}})()"
        view.evaluateJavascript(js, null)
    }

    private fun buildLogPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Ui.BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@AccountActivity, 12), Ui.dp(this@AccountActivity, 10),
                Ui.dp(this@AccountActivity, 12), Ui.dp(this@AccountActivity, 8))
            background = Ui.rounded(this@AccountActivity, Color.WHITE, Ui.STROKE, 12)
        }
        val titleRow = Ui.row(this)
        titleRow.addView(Ui.text(this, "运行日志", 16, Ui.TEXT).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        logSummary = Ui.text(this, "0 条", 11, Ui.MUTED)
        titleRow.addView(logSummary)
        header.addView(titleRow)
        header.addView(Ui.text(this,
            "只显示有用的进度；网络请求只保留方法、域名、状态，不显示请求头。",
            11, Ui.MUTED).apply { setPadding(0, Ui.dp(this@AccountActivity, 3), 0, 0) })

        val chipScroll = HorizontalScrollView(this)
        val chipRow = Ui.row(this)
        fun addFilter(label: String, value: String) {
            val chip = Ui.text(this, label, 12, Ui.ACCENT).apply {
                setPadding(Ui.dp(this@AccountActivity, 10), Ui.dp(this@AccountActivity, 6),
                    Ui.dp(this@AccountActivity, 10), Ui.dp(this@AccountActivity, 6))
                setOnClickListener {
                    logFilter = value
                    renderLog()
                }
            }
            logChips[value] = chip
            chipRow.addView(chip)
        }
        addFilter("全部", "all")
        addFilter("进度", "progress")
        addFilter("网络", "net")
        addFilter("错误", "error")
        chipRow.addView(Ui.button(this, "清空", false).apply {
            setOnClickListener {
                synchronized(logEntries) { logEntries.clear() }
                synchronized(recentLogKeys) { recentLogKeys.clear() }
                renderLog()
            }
        })
        chipRow.addView(Ui.button(this, "复制", false).apply {
            setOnClickListener { copyLogToClipboard() }
        })
        chipScroll.addView(chipRow)
        header.addView(chipScroll)
        panel.addView(header)

        logScroll = ScrollView(this).apply {
            isFillViewport = true
            background = Ui.rounded(this@AccountActivity, Color.WHITE, Ui.STROKE, 12)
            clipToOutline = true
        }
        logView = Ui.text(this, "", 12, Color.rgb(35, 55, 44)).apply {
            typeface = Typeface.MONOSPACE
            setPadding(Ui.dp(this@AccountActivity, 12), Ui.dp(this@AccountActivity, 10),
                Ui.dp(this@AccountActivity, 12), Ui.dp(this@AccountActivity, 14))
            setLineSpacing(0f, 1.25f)
            movementMethod = ScrollingMovementMethod()
        }
        logScroll.addView(logView)
        panel.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            setMargins(Ui.dp(this@AccountActivity, 10), Ui.dp(this@AccountActivity, 4),
                Ui.dp(this@AccountActivity, 10), Ui.dp(this@AccountActivity, 10))
        })
        return panel
    }

    private fun setLogMode(enabled: Boolean) {
        val view = webView ?: return
        if (!this::logPanel.isInitialized) return
        if (logMode == enabled && logPanel.visibility ==
            if (enabled) View.VISIBLE else View.GONE
        ) return
        logMode = enabled
        if (enabled) {
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 2))
            logPanel.visibility = View.VISIBLE
            logPanel.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            renderLog()
            appendLog("info", "仅日志模式：WebView 保持 2dp 可见，脚本继续运行")
        } else {
            logPanel.visibility = View.GONE
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            appendLog("info", "已切回浏览器画面")
        }
    }

    fun appendLog(level: String, message: String) {
        if (isNoiseMessage(message)) return
        val cleaned = sanitizeLog(message) ?: return
        val normalized = normalizeLevel(level)
        val key = "$normalized|$cleaned"
        val now = System.currentTimeMillis()
        synchronized(recentLogKeys) {
            val previous = recentLogKeys[key]
            if (previous != null && now - previous < 5000) return
            recentLogKeys[key] = now
            if (recentLogKeys.size > 400) {
                recentLogKeys.entries.removeAll { now - it.value > 5000 }
            }
        }
        val entry = LogEntry(logTime.format(Date()), normalized, cleaned)
        synchronized(logEntries) {
            logEntries.add(entry)
            if (logEntries.size > 1000) {
                logEntries.subList(0, logEntries.size - 1000).clear()
            }
        }
        if (logMode) runOnUiThread { renderLog() }
    }

    private fun normalizeLevel(level: String): String = when (level.lowercase(Locale.ROOT)) {
        "page-error" -> "error"
        "net", "api" -> "net"
        "warn", "warning" -> "warn"
        "error" -> "error"
        "console" -> "console"
        else -> "info"
    }

    private fun sanitizeLog(message: String): String? {
        val headerPattern = Regex(
            "^(accept|accept-language|user-agent|content-type|origin|referer|" +
                "sec-[^:]+|cookie|authorization|host|content-length)\\s*:",
            RegexOption.IGNORE_CASE
        )
        val lines = message.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !headerPattern.containsMatchIn(it) }
        if (lines.isEmpty()) return null
        var text = lines.joinToString(" | ")
        text = text.replace(
            Regex(
                "(?i)(authorization|api[_-]?key|atoken|authtoken|access[_-]?token|" +
                    "refresh[_-]?token|cookie)\\s*[:=]\\s*[^\\s,;}]+"
            ),
            "$1=[已隐藏]"
        )
        if (text.length > 600) text = text.take(600) + "…"
        return text
    }

    private fun shouldKeepConsole(message: String): Boolean {
        val lower = message.lowercase(Locale.ROOT)
        if (isNoiseMessage(message)) return false
        val useful = listOf(
            "yzj", "gm ", "gm_", "扫码", "登录", "token", "题库", "资料",
            "ai", "api", "失败", "error", "exception", "超时", "session"
        )
        return useful.any { lower.contains(it) }
    }

    private fun isNoiseMessage(message: String): Boolean {
        val lower = message.lowercase(Locale.ROOT).trim()
        if (lower == "unknown") return true
        val noise = listOf(
            "vue-router", "devtools", "cannot read properties of null",
            "[object object]", "res通知", "res待办", "uncaught (in promise) undefined",
            "jquery-1.9.1", "sessionstorage", "failed to read the 'sessionstorage'",
            "socketurl", "学生任务"
        )
        return noise.any { lower.contains(it) }
    }

    private fun renderLog() {
        if (!this::logView.isInitialized || !this::logSummary.isInitialized) return
        val snapshot = synchronized(logEntries) { logEntries.toList() }
        val filtered = snapshot.filter { matchesFilter(it) }
        val builder = SpannableStringBuilder()
        if (filtered.isEmpty()) {
            builder.append(
                if (snapshot.isEmpty()) {
                    "暂无运行日志。\n\n开始任务后，这里会显示登录、脚本进度、" +
                        "题库命中、AI 请求和错误摘要。"
                } else {
                    "当前筛选没有日志。点击“全部”查看其他类别。"
                }
            )
            builder.setSpan(ForegroundColorSpan(Ui.MUTED), 0, builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (entry in filtered) {
            val timeStart = builder.length
            builder.append("[${entry.time}] ")
            builder.setSpan(ForegroundColorSpan(Ui.MUTED), timeStart, builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val label = when (entry.level) {
                "net" -> "NET "
                "error" -> "ERR "
                "warn" -> "WARN"
                "console" -> "PAGE"
                else -> "INFO"
            }
            val levelStart = builder.length
            builder.append(label).append("  ")
            builder.setSpan(ForegroundColorSpan(levelColor(entry.level)),
                levelStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), levelStart, builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(entry.message.replace("\n", "\n      ")).append('\n')
        }
        logView.text = builder
        logSummary.text = "${filtered.size}/${snapshot.size} 条"
        for ((key, chip) in logChips) {
            val selected = key == logFilter
            chip.setTextColor(if (selected) Color.WHITE else Ui.ACCENT)
            chip.background = Ui.rounded(this,
                if (selected) Ui.ACCENT else Color.WHITE,
                if (selected) Ui.ACCENT else Ui.STROKE, 10)
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun matchesFilter(entry: LogEntry): Boolean = when (logFilter) {
        "net" -> entry.level == "net"
        "error" -> entry.level == "error" || entry.level == "warn" ||
            entry.message.contains("失败") || entry.message.contains("错误")
        "progress" -> entry.level != "console"
        else -> true
    }

    private fun levelColor(level: String): Int = when (level) {
        "error" -> Ui.DANGER
        "warn" -> Ui.WARN
        "net" -> Color.rgb(37, 99, 180)
        "console" -> Ui.MUTED
        else -> Ui.ACCENT
    }

    private fun copyLogToClipboard() {
        val text = synchronized(logEntries) {
            logEntries.filter { matchesFilter(it) }
                .joinToString("\n") { "[${it.time}] ${it.level}  ${it.message}" }
        }
        if (text.isBlank()) {
            toast("当前筛选没有可复制的日志")
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("yzj-log", text))
        toast("日志已复制")
    }

    private fun safePage(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val clean = raw.substringBefore('?')
        return if (clean.length > 180) clean.take(180) + "…" else clean
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (!isFinishing && !resetInProgress) {
                captureStatus()
                if (!logMode) capturePreview()
            }
            uiHandler.postDelayed(this, 10000)
        }
    }

    // API 33+ uses RECEIVER_NOT_EXPORTED. On API 26-32 the four-argument
    // overload is restricted by a signature permission, so no other app can
    // send RESET/STOP or receive the matching package-scoped broadcast.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_RESET)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter, CONTROL_PERMISSION, null)
        }
        receiverRegistered = true
    }

    override fun onPause() {
        super.onPause()
        capturePreview()
        if (!isFinishing) captureStatus()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(statusTick)
        uiHandler.post(statusTick)
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        setLogMode(intent?.getBooleanExtra("log_mode", false) == true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(statusTick)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(controlReceiver) }
            receiverRegistered = false
        }
        runCatching {
            val current = StatusStore.read(this, accountSlot)
            if (current.length() > 0) {
                current.put("alive", false)
                current.put("updatedAt", System.currentTimeMillis())
                StatusStore.write(this, accountSlot, current)
            }
        }
        webView?.let {
            it.removeJavascriptInterface("YzjNative")
            it.loadUrl("about:blank")
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    fun showToastFromJs(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    fun setStatusFromJs(text: String) {
        val cleaned = sanitizeLog(text) ?: return
        runOnUiThread { status?.text = "实例 ${accountSlot + 1}：$cleaned" }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("yzj-session", text))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun readAsset(name: String): String =
        runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")

    private fun unquote(value: String?): String {
        if (value.isNullOrEmpty()) return "{}"
        if (!value.startsWith("\"")) return value
        return runCatching { JSONArray("[$value]").getString(0) }.getOrDefault(value)
    }

    private fun String?.cleanJsonString(): String =
        this?.takeUnless { it == "null" || it == "undefined" }.orEmpty()

    companion object {
        const val ACTION_RESET = "com.yzj.client.action.RESET"
        const val ACTION_STOP = "com.yzj.client.action.STOP"
        private const val CONTROL_PERMISSION = "com.yzj.client.permission.CONTROL"
        private const val TAG = "YZJ_ACCOUNT"
        private const val TASK_URL = "https://www.csgmooc.com/spoc/html/stud_task.html"
        private const val MATERIAL_URL = "https://www.csgmooc.com/spoc/html/stud_material.html"
        private const val LOGIN_URL = "https://www.csgmooc.com/uniplatform/#/school"
        private const val WHITE_BLANK = "https://www.csgmooc.com/spoc/html/white_blank.html"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
    }
}
