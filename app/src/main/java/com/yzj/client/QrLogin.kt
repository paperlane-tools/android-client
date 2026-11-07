package com.yzj.client

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class QrLogin(
    private val activity: AccountActivity,
    private val slot: Int,
    private val onSuccess: () -> Unit
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var dialog: AlertDialog? = null
    private lateinit var schoolInput: EditText
    private lateinit var authTypeSpinner: Spinner
    private lateinit var fetchButton: Button
    private lateinit var qrImage: ImageView
    private lateinit var status: TextView

    private fun open() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        box.addView(Ui.text(activity,
            "输入学校名称，获取登录二维码；用易智教 App 扫码后，必须在 App 内点“确认登录”。",
            13, Ui.MUTED))

        schoolInput = EditText(activity).apply {
            hint = "学校名称"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(activity.getSharedPreferences("yzj_login", Context.MODE_PRIVATE)
                .getString("schoolName", "天津市第一商业学校"))
            setPadding(0, dp(8), 0, dp(8))
        }
        box.addView(schoolInput)

        authTypeSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item,
                listOf("课堂模式（任务 / 资料）", "资源库观看模式"))
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        box.addView(authTypeSpinner)

        fetchButton = Button(activity).apply { text = "获取二维码"; isAllCaps = false }
        box.addView(fetchButton)

        qrImage = ImageView(activity).apply { adjustViewBounds = true }
        box.addView(qrImage, LinearLayout.LayoutParams(dp(300), dp(300)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })

        status = Ui.text(activity, "尚未获取二维码", 13, Ui.TEXT).apply {
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(status)

        val scroll = ScrollView(activity).apply { addView(box) }
        dialog = AlertDialog.Builder(activity)
            .setTitle("扫码登录 · 实例 ${slot + 1}")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()
        dialog?.setOnDismissListener {
            cancelled = true
            worker.shutdownNow()
        }
        dialog?.show()
        fetchButton.setOnClickListener { startFetch() }
    }

    private fun startFetch() {
        val name = schoolInput.text.toString().trim()
        if (name.isEmpty()) {
            status.text = "请先输入学校名称"
            return
        }
        cancelled = false
        val authType = authTypeSpinner.selectedItemPosition
        fetchButton.isEnabled = false
        qrImage.setImageDrawable(null)
        status.text = "正在解析学校…"
        worker.execute {
            try {
                val (encryptedSchool, schoolName) = resolveSchool(name)
                updateStatus("已找到：$schoolName，正在获取二维码…")
                val codeResp = getJson(
                    "$BASE/portal/login/loginCode?schoolNum=${enc(encryptedSchool)}",
                    headers(encryptedSchool)
                )
                if (codeResp.optString("errCode") != "200") {
                    error("取二维码失败：${codeResp.optString("errMsg")}")
                }
                val data = codeResp.optJSONObject("data") ?: error("二维码响应缺少 data")
                val ticket = data.optString("code")
                val imageUrl = data.optString("path")
                if (ticket.isEmpty() || imageUrl.isEmpty()) error("二维码响应缺少 code/path")

                val bitmap = loadBitmap(imageUrl)
                if (cancelled) return@execute
                main.post { qrImage.setImageBitmap(bitmap) }
                updateStatus("请用易智教 App 扫码，然后在 App 内点“确认登录”（最长等待 150 秒）…")
                activity.appendLog("info", "二维码已生成，等待易智教 App 扫码")
                val loginData = poll(ticket, encryptedSchool)
                if (!cancelled) completeLogin(loginData, authType)
            } catch (e: Exception) {
                updateStatus("登录失败：${e.message}")
                main.post { fetchButton.isEnabled = true }
            }
        }
    }

    private fun resolveSchool(name: String): Pair<String, String> {
        val resp = getJson("$BASE/portal/login/getSchools?name=${enc(name)}", headers(""))
        val array = resp.optJSONArray("data")
        if (array == null || array.length() == 0) error("没有找到学校「$name」")
        var chosen = array.optJSONObject(0)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            if (item != null && name == item.optString("schoolName")) {
                chosen = item
                break
            }
        }
        val num = chosen?.optString("schoolNum").orEmpty()
        val schoolName = chosen?.optString("schoolName").orEmpty()
        if (num.isEmpty()) error("学校缺少 schoolNum")
        return num to schoolName
    }

    private fun poll(ticket: String, schoolNum: String): JSONObject {
        var last = "未授权"
        repeat(150) {
            if (cancelled) error("已取消")
            val resp = getJson(
                "$BASE/portal/login/refreshCode?code=${enc(ticket)}&schoolNum=${enc(schoolNum)}",
                headers(schoolNum)
            )
            val code = resp.optString("errCode")
            val data = resp.optJSONObject("data")
            if (code == "509") error("二维码已过期，请重新获取")
            if (code == "200" && data != null) {
                when (data.optInt("action", 0)) {
                    1 -> {
                        updateStatus("已扫码，请在易智教 App 内点“确认登录”…")
                        activity.appendLog("info", "已扫码，等待手机确认登录")
                    }
                    2 -> {
                        val loginTicket = data.optString("ticket")
                        if (loginTicket.isNotEmpty()) {
                            updateStatus("已确认，正在换取登录态…")
                            activity.appendLog("info", "手机已确认，正在换取登录态")
                            val login = getJson(
                                "$BASE/portal/login/v1/appScanLogin?ticket=${enc(loginTicket)}",
                                headers(schoolNum)
                            )
                            if (login.optString("errCode") != "200") {
                                error("换登录态失败：${login.optString("errMsg")}")
                            }
                            return login.optJSONObject("data")
                                ?: error("登录响应缺少 data")
                        }
                    }
                    3 -> error("二维码已过期或被取消，请重新获取")
                }
            }
            if (code != "401" && code != "203") last = "$code ${resp.optString("errMsg")}"
            Thread.sleep(1000)
        }
        error("等待扫码超时（最后状态：$last）")
    }

    /**
     * The unified portal only returns an authtoken. The SPOC site requires the
     * white_blank.html SSO bridge to call auth/getUserDetail and fill
     * userInfo/schoolNum/encryptSchool/redirectUrl. Writing sessionStorage by
     * hand leaves those fields missing and the login fails.
     */
    private fun completeLogin(data: JSONObject, authType: Int) {
        val token = data.optString("authtoken")
        if (token.isEmpty()) error("登录响应缺少 authtoken")
        val stu = data.optJSONObject("teaStuBase") ?: JSONObject()
        val userName = stu.optString("username").ifEmpty { stu.optString("name") }
        val session = JSONObject().apply {
            put("reslibAuthtoken", token)
            put("savedAt", System.currentTimeMillis())
            put("authType", authType)
            if (userName.isNotEmpty()) put("login_name", userName)
        }
        SecureStore.putJson(activity, SecureStore.sessionName(slot), session)
        main.post {
            dialog?.takeIf { it.isShowing }?.dismiss()
            activity.completeQrLogin(token, authType)
            Toast.makeText(activity,
                "扫码成功，正在通过 SSO 桥接页完成登录…" +
                    if (userName.isEmpty()) "" else "（$userName）",
                Toast.LENGTH_LONG).show()
            onSuccess()
        }
    }

    private fun loadBitmap(imageUrl: String): Bitmap {
        val conn = URL(imageUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", UA)
        return conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }

    private fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept-Encoding", "identity")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            ?: error("HTTP $code 无响应体")
        return JSONObject(text)
    }

    private fun headers(schoolNum: String): Map<String, String> = linkedMapOf(
        "User-Agent" to UA,
        "Content-Type" to "application/json;charset=UTF-8",
        "schoolNum" to schoolNum,
        "Origin" to "https://www.csgmooc.com",
        "Referer" to "https://www.csgmooc.com/uniplatform/"
    )

    private fun updateStatus(text: String) {
        main.post { status.text = text }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun dp(value: Int): Int = Ui.dp(activity, value)

    companion object {
        private const val BASE = "https://www.csgmooc.com/uniBaseApi"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"

        fun show(activity: AccountActivity, slot: Int, onSuccess: () -> Unit) {
            QrLogin(activity, slot, onSuccess).open()
        }
    }
}
