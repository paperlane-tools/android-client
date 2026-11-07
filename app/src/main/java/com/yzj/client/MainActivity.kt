package com.yzj.client

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private var pendingBankSlot = -1

    private val previews = arrayOfNulls<ImageView>(InstanceStore.MAX)
    private val placeholders = arrayOfNulls<TextView>(InstanceStore.MAX)
    private val chips = arrayOfNulls<TextView>(InstanceStore.MAX)
    private val statusViews = arrayOfNulls<TextView>(InstanceStore.MAX)
    private val bankViews = arrayOfNulls<TextView>(InstanceStore.MAX)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 22),
                Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 24))
        }
        scroll.addView(root)

        val header = Ui.row(this)
        header.addView(Ui.title(this, "易智教多开助手"), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.button(this, "统一配置", true).apply {
            setOnClickListener { ConfigDialog.show(this@MainActivity, -1) { refreshStatus() } }
        })
        root.addView(header)

        root.addView(Ui.text(this,
            "单台模拟器内多实例运行 · Keystore 加密 · 12 小时会话保活", 13, Ui.MUTED)
            .apply { setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6)) })

        summary = Ui.text(this, "", 12, Ui.MUTED).apply {
            setPadding(0, 0, 0, Ui.dp(this@MainActivity, 12))
        }
        root.addView(summary)

        val addRow = Ui.row(this)
        addRow.addView(Ui.button(this, "+ 添加实例", false).apply {
            setOnClickListener {
                val n = InstanceStore.count(this@MainActivity)
                if (n >= InstanceStore.MAX) {
                    toast("最多 ${InstanceStore.MAX} 个实例")
                } else {
                    InstanceStore.setCount(this@MainActivity, n + 1)
                    rebuild()
                }
            }
        })
        addRow.addView(Ui.button(this, "− 移除末位", false).apply {
            setOnClickListener {
                val n = InstanceStore.count(this@MainActivity)
                if (n <= InstanceStore.MIN) {
                    toast("至少保留 ${InstanceStore.MIN} 个实例")
                } else {
                    sendControl(AccountActivity.ACTION_STOP, n - 1)
                    InstanceStore.setCount(this@MainActivity, n - 1)
                    rebuild()
                }
            }
        })
        root.addView(addRow)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 4))
        }
        root.addView(list)
        root.addView(batchBar())
        root.addView(Ui.text(this,
            "提示：同一模拟器切换实例时后台 WebView 定时器会被系统节流；" +
                "需要长跑时使用“仅日志”保持前台，或一台 MuMu 实例跑一个账号。12 小时保活服务只维持 HTTP 会话，不驱动 WebView 定时器。",
            12, Ui.MUTED).apply { setPadding(0, Ui.dp(this@MainActivity, 14), 0, 0) })

        setContentView(scroll)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun batchBar(): View {
        val row = Ui.row(this).apply { setPadding(0, Ui.dp(this@MainActivity, 10), 0, 0) }
        row.addView(Ui.button(this, "全部启动", true).apply {
            setOnClickListener {
                for (i in 0 until InstanceStore.count(this@MainActivity)) startAccount(i, false)
            }
        })
        row.addView(Ui.button(this, "全部停止", false).apply {
            setOnClickListener {
                for (i in 0 until InstanceStore.count(this@MainActivity)) {
                    sendControl(AccountActivity.ACTION_STOP, i)
                }
            }
        })
        row.addView(Ui.button(this, "全部重置", false).apply {
            setOnClickListener { confirmResetAll() }
        })
        row.addView(Ui.button(this, "配置应用到全部", false).apply {
            setOnClickListener {
                ConfigStore.applyToAll(this@MainActivity, ConfigStore.load(this@MainActivity))
                toast("配置已应用到全部实例")
            }
        })
        row.addView(Ui.button(this, "导出配置", false).apply {
            setOnClickListener { exportConfig() }
        })
        row.addView(Ui.button(this, "导入配置", false).apply {
            setOnClickListener { importConfig() }
        })
        val keep = Ui.button(this,
            if (SessionKeeper.isRunning) "停止会话保活" else "12h 会话保活", false)
        keep.setOnClickListener {
            if (SessionKeeper.isRunning) {
                SessionKeeper.stop(this@MainActivity)
                keep.text = "12h 会话保活"
                toast("会话保活已停止")
            } else {
                ensureNotificationPermission()
                SessionKeeper.start(this@MainActivity)
                keep.text = "停止会话保活"
                toast("会话保活已启动，每 10 分钟检查一次")
            }
        }
        row.addView(keep)
        row.addView(Ui.button(this, "刷新", false).apply {
            setOnClickListener { refreshStatus() }
        })
        return HorizontalScrollView(this).apply { addView(row) }
    }

    private fun rebuild() {
        list.removeAllViews()
        val count = InstanceStore.count(this)
        for (i in 0 until InstanceStore.MAX) {
            previews[i] = null
            placeholders[i] = null
            chips[i] = null
            statusViews[i] = null
            bankViews[i] = null
        }

        val columns = if (resources.displayMetrics.widthPixels >= 1200) 2 else 1
        var row: LinearLayout? = null
        for (i in 0 until count) {
            if (i % columns == 0) {
                row = Ui.row(this).apply { gravity = Gravity.TOP }
                list.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(this@MainActivity, 10) })
            }
            val card = instanceCard(i)
            row?.addView(card, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                leftMargin = Ui.dp(this@MainActivity, 6)
                rightMargin = Ui.dp(this@MainActivity, 6)
            })
        }
        if (columns == 2 && count % 2 == 1) {
            row?.addView(View(this), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        summary.text = "实例 $count/${InstanceStore.MAX} · 配置方案 " +
            "${ConfigStore.profileNames(ConfigStore.load(this)).size} 个 · " +
            "会话保活 ${if (SessionKeeper.isRunning) "开" else "关"}"
        refreshStatus()
    }

    private fun instanceCard(slot: Int): View {
        val card = Ui.card(this)
        val head = Ui.row(this)
        head.addView(Ui.text(this, InstanceStore.label(slot), 17, Ui.TEXT).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chip = Ui.chip(this, "未运行", Ui.MUTED)
        chips[slot] = chip
        head.addView(chip)
        card.addView(head)

        val preview = FrameLayout(this).apply {
            background = Ui.rounded(this@MainActivity, Color.rgb(234, 240, 237),
                Color.TRANSPARENT, 12)
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.rounded(this@MainActivity, Color.rgb(234, 240, 237),
                Color.TRANSPARENT, 12)
            clipToOutline = true
        }
        preview.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val placeholder = Ui.text(this, "暂无预览", 12, Ui.MUTED).apply {
            gravity = Gravity.CENTER
        }
        preview.addView(placeholder, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150)
        ).apply { topMargin = Ui.dp(this@MainActivity, 10) })
        previews[slot] = image
        placeholders[slot] = placeholder

        val status = Ui.text(this, "尚未打开", 12, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
        }
        statusViews[slot] = status
        card.addView(status)
        val bank = Ui.text(this, BankStore.summary(this, slot), 12, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 3), 0, Ui.dp(this@MainActivity, 4))
        }
        bankViews[slot] = bank
        card.addView(bank)

        val actions1 = Ui.row(this)
        addAction(actions1, "进入", true) { startAccount(slot, false) }
        addAction(actions1, "仅日志", false) { startAccount(slot, true) }
        addAction(actions1, "配置", false) {
            ConfigDialog.show(this, slot) { refreshStatus() }
        }
        card.addView(actions1)
        val actions2 = Ui.row(this)
        addAction(actions2, "题库", false) { showBankDialog(slot) }
        addAction(actions2, "重置", false) { confirmReset(slot) }
        addAction(actions2, "停止", false) { sendControl(AccountActivity.ACTION_STOP, slot) }
        card.addView(actions2)
        return card
    }

    private fun addAction(row: LinearLayout, label: String, primary: Boolean,
                          action: () -> Unit) {
        val b = Ui.button(this, label, primary)
        b.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            setMargins(Ui.dp(this@MainActivity, 2), Ui.dp(this@MainActivity, 6),
                Ui.dp(this@MainActivity, 2), 0)
        }
        b.setOnClickListener { action() }
        row.addView(b)
    }

    private fun refreshStatus() {
        val count = InstanceStore.count(this)
        val now = System.currentTimeMillis()
        for (i in 0 until count) {
            val status = statusViews[i] ?: continue
            val chip = chips[i] ?: continue
            val bank = bankViews[i] ?: continue
            bank.text = BankStore.summary(this, i)
            val o = StatusStore.read(this, i)
            if (o.length() == 0) {
                chip.text = "未运行"
                chip.setTextColor(Ui.MUTED)
                status.text = "尚未打开"
                status.setTextColor(Ui.MUTED)
                showPreview(i, false)
                continue
            }
            val at = o.optLong("updatedAt")
            val alive = o.optBoolean("alive") && at > 0 && now - at < 60000
            chip.text = if (alive) "运行中" else "已停止"
            chip.setTextColor(if (alive) Ui.ACCENT else Ui.MUTED)

            val token = o.optBoolean("token")
            val name = o.cleanString("name")
            val url = o.cleanString("url")
            val quiz = o.cleanString("quiz")
            val mat = o.cleanString("mat")
            val page = when {
                url.contains("stud_task") -> "任务页"
                url.contains("stud_material") -> "资料页"
                url.contains("login-error") -> "登录失效"
                url.contains("#/school") -> "登录页"
                else -> "站点"
            }
            val whenText = if (at > 0) DateUtils.getRelativeTimeSpanString(
                at, now, DateUtils.MINUTE_IN_MILLIS
            ).toString() else ""
            val session = when {
                o.optBoolean("sessionExpired") -> " · 会话需重登"
                o.optBoolean("sessionOk") -> " · 会话正常"
                else -> ""
            }
            status.text = (if (token) "已登录" else "未登录") +
                (if (name.isEmpty()) "" else " · $name") +
                " · $page" +
                (if (quiz.isEmpty()) "" else " · 作答 $quiz") +
                (if (mat.isEmpty()) "" else " · 资料 $mat") +
                session +
                (if (whenText.isEmpty()) "" else " · $whenText")
            status.setTextColor(if (token) Color.rgb(10, 110, 60) else Ui.WARN)
            showPreview(i, true)
        }
        summary.text = "实例 $count/${InstanceStore.MAX} · 配置方案 " +
            "${ConfigStore.profileNames(ConfigStore.load(this)).size} 个 · " +
            "会话保活 ${if (SessionKeeper.isRunning) "开" else "关"}"
    }

    private fun showPreview(slot: Int, hasStatus: Boolean) {
        val image = previews[slot] ?: return
        val placeholder = placeholders[slot] ?: return
        val file = PreviewStore.file(this, slot)
        if (file.isFile) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                placeholder.visibility = View.GONE
                return
            }
        }
        image.setImageDrawable(null)
        placeholder.visibility = View.VISIBLE
        placeholder.text = if (hasStatus) "暂无预览" else "尚未打开"
    }

    private fun startAccount(slot: Int, logMode: Boolean) {
        startActivity(Intent(this, InstanceStore.activityClass(slot)).apply {
            putExtra("slot", slot)
            putExtra("log_mode", logMode)
        })
    }

    private fun sendControl(action: String, slot: Int) {
        sendBroadcast(
            Intent(action).setPackage(packageName).putExtra("slot", slot),
            CONTROL_PERMISSION
        )
    }

    private fun confirmReset(slot: Int) {
        AlertDialog.Builder(this)
            .setTitle("重置实例 ${slot + 1}？")
            .setMessage("会清空该实例的登录态、Cookie、运行状态和预览图；配置方案和题库会保留。")
            .setPositiveButton("重置") { _, _ -> doReset(slot) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmResetAll() {
        AlertDialog.Builder(this)
            .setTitle("重置全部实例？")
            .setMessage("会清空所有实例的登录态、Cookie、运行状态和预览图；配置方案和题库会保留。")
            .setPositiveButton("全部重置") { _, _ ->
                for (i in 0 until InstanceStore.count(this)) doReset(i)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doReset(slot: Int) {
        // Clear the encrypted session from the main process too. If the account
        // process is not alive, its broadcast receiver will never run; without
        // this the next launch would restore the vault and appear logged in.
        SecureStore.remove(this, SecureStore.sessionName(slot))
        StatusStore.delete(this, slot)
        PreviewStore.delete(this, slot)
        val prefs = getSharedPreferences("yzj_gm", MODE_PRIVATE)
        val editor = prefs.edit()
        val prefix = "slot$slot:"
        // A receiver does not exist when that account process is stopped. The
        // mark makes the next AccountActivity clear WebView storage before it
        // navigates to any site page.
        editor.putBoolean("${prefix}pendingReset", true)
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix) && (
                    key.endsWith(":pendingFinalize") ||
                    key.endsWith(":lastTask") ||
                    key.endsWith(":uaState") ||
                    key.endsWith(":submitQueue")
                )
            ) {
                editor.remove(key)
            }
        }
        editor.commit()
        // Cookies can survive independently of sessionStorage and the vault.
        // Clear them even if the account process is currently stopped.
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
        }
        sendControl(AccountActivity.ACTION_RESET, slot)
        refreshStatus()
    }

    private fun showBankDialog(slot: Int) {
        val items = arrayOf("导出题库到文件", "从文件导入题库", "从剪贴板导入题库", "清空题库")
        AlertDialog.Builder(this)
            .setTitle("实例 ${slot + 1} · ${BankStore.summary(this, slot)}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportBank(slot)
                    1 -> importBankFromFile(slot)
                    2 -> importBankFromClipboard(slot)
                    else -> clearBank(slot)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun exportBank(slot: Int) {
        runCatching { BankStore.exportToFile(this, slot) }
            .onSuccess { toast("题库已导出：$it") }
            .onFailure { toast("导出失败：${it.message}") }
    }

    private fun importBankFromFile(slot: Int) {
        pendingBankSlot = slot
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_IMPORT_BANK)
    }

    private fun importBankFromClipboard(slot: Int) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板里没有文本")
            return
        }
        runCatching { BankStore.importData(this, slot, text) }
            .onSuccess {
                toast("已导入 ${BankStore.count(this, slot)} 条（${it.source}）")
                refreshStatus()
            }
            .onFailure { toast("导入失败：${it.message}") }
    }

    private fun clearBank(slot: Int) {
        AlertDialog.Builder(this)
            .setTitle("清空实例 ${slot + 1} 的题库？")
            .setMessage("只会删除本地题库，不影响服务器上的作答记录。")
            .setPositiveButton("清空") { _, _ -> BankStore.clear(this, slot); refreshStatus() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportConfig() {
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "yzj-export")
        if (!dir.isDirectory && !dir.mkdirs()) {
            toast("无法创建导出目录")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val file = File(dir, "config-$stamp.json")
        file.writeText(ConfigStore.load(this).toString(2))
        toast("配置已导出（不含 API Key）：${file.absolutePath}")
    }

    private fun importConfig() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_IMPORT_CONFIG)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uri = data.data ?: return
        when (requestCode) {
            REQ_IMPORT_BANK -> {
                val slot = pendingBankSlot
                pendingBankSlot = -1
                if (slot < 0) return
                runCatching { readUri(uri) }
                    .mapCatching { BankStore.importData(this, slot, it) }
                    .onSuccess {
                        toast("已导入 ${BankStore.count(this, slot)} 条题库（${it.source}）")
                        refreshStatus()
                    }
                    .onFailure { toast("导入失败：${it.message}") }
            }
            REQ_IMPORT_CONFIG -> {
                runCatching {
                    val root = ConfigStore.parse(readUri(uri))
                    ConfigStore.save(this, root)
                    ConfigStore.applyToAll(this, root)
                    rebuild()
                    toast("配置已导入并应用（API Key 需重新填写）")
                }.onFailure { toast("配置导入失败：${it.message}") }
            }
        }
    }

    private fun readUri(uri: Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("打不开文件")

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun JSONObject.cleanString(key: String): String =
        optString(key).takeUnless { it == "null" || it == "undefined" }.orEmpty()

    companion object {
        private const val REQ_IMPORT_BANK = 4101
        private const val REQ_IMPORT_CONFIG = 4103
        private const val REQ_NOTIFICATIONS = 4104
        private const val CONTROL_PERMISSION = "com.yzj.client.permission.CONTROL"
    }
}
