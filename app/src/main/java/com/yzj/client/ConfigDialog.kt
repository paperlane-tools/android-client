package com.yzj.client

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Form-based configuration editor. No hand-written JSON.
 * Slot < 0 edits a shared profile; slot >= 0 edits an instance override.
 */
object ConfigDialog {
    private data class Provider(
        val id: String,
        val label: String,
        val proto: String,
        val base: String,
        val model: String,
        val vision: String
    )

    private val providers = listOf(
        Provider("deepseek", "DeepSeek", "openai", "https://api.deepseek.com",
            "deepseek-flash", "deepseek-flash"),
        Provider("openai", "OpenAI", "openai", "https://api.openai.com/v1",
            "gpt-5.2-mini", "gpt-5.2-mini"),
        Provider("anthropic", "Anthropic Claude", "anthropic", "https://api.anthropic.com/v1",
            "claude-haiku-4-5", "claude-haiku-4-5"),
        Provider("gemini", "Google Gemini", "gemini",
            "https://generativelanguage.googleapis.com/v1beta",
            "gemini-3-flash", "gemini-3-flash"),
        Provider("moonshot", "月之暗面 Kimi", "openai", "https://api.moonshot.cn/v1",
            "kimi-k2-turbo-preview", "moonshot-v1-8k-vision-preview"),
        Provider("zhipu", "智谱 GLM", "openai",
            "https://open.bigmodel.cn/api/paas/v4", "glm-4.6", "glm-4.6v"),
        Provider("dashscope", "阿里百炼 · 通义千问", "openai",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3-max", "qwen3-vl-plus"),
        Provider("custom", "自定义 OpenAI 兼容", "openai", "", "", "")
    )

    fun show(activity: Activity, slot: Int, after: (() -> Unit)? = null) {
        val root = ConfigStore.load(activity)
        val names = ConfigStore.profileNames(root).toMutableList()
        var loading = true

        val scroll = ScrollView(activity)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(activity, 18), Ui.dp(activity, 16),
                Ui.dp(activity, 18), Ui.dp(activity, 16))
        }
        scroll.addView(box)

        box.addView(Ui.text(activity,
            if (slot >= 0) "实例 ${slot + 1} 配置" else "统一配置方案", 19, Ui.TEXT))
        box.addView(Ui.text(activity,
            if (slot >= 0) "选择基础方案，修改后保存为当前实例的覆盖配置。"
            else "修改当前方案；可保存后一键应用到全部实例。",
            12, Ui.MUTED).apply {
            setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10))
        })

        val profileSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, names)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        box.addView(profileSpinner)
        val assignment = Ui.text(activity, "", 12, Ui.MUTED).apply {
            setPadding(0, Ui.dp(activity, 5), 0, Ui.dp(activity, 8))
        }
        box.addView(assignment)

        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(form, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun section(title: String): LinearLayout {
            val card = Ui.card(activity)
            card.addView(Ui.text(activity, title, 14, Ui.ACCENT).apply {
                setPadding(0, 0, 0, Ui.dp(activity, 6))
            })
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = Ui.dp(activity, 10)
            form.addView(card, lp)
            return card
        }

        fun field(value: String, hint: String = "", numeric: Boolean = false,
                  password: Boolean = false, multi: Boolean = false): EditText =
            EditText(activity).apply {
                setText(value)
                this.hint = hint
                textSize = 14f
                setSingleLine(!multi)
                minLines = if (multi) 4 else 1
                inputType = when {
                    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    else -> InputType.TYPE_CLASS_TEXT
                }
                if (multi) gravity = Gravity.TOP or Gravity.START
            }

        fun row(card: LinearLayout, label: String, fieldView: EditText) {
            val line = Ui.row(activity)
            line.addView(Ui.text(activity, label, 12, Ui.MUTED).apply {
                width = Ui.dp(activity, 128)
            })
            line.addView(fieldView, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(line)
        }

        fun switchRow(card: LinearLayout, label: String, checked: Boolean): Switch {
            val line = Ui.row(activity)
            line.addView(Ui.text(activity, label, 13, Ui.TEXT), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(activity).apply { isChecked = checked }
            line.addView(sw)
            card.addView(line)
            return sw
        }

        val ai = section("AI 服务商")
        val providerSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item,
                providers.map { it.label })
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        row(ai, "服务商", EditText(activity).apply { visibility = android.view.View.GONE })
        ai.removeViewAt(ai.childCount - 1)
        ai.addView(providerSpinner)

        val baseUrl = field("", "https://api.deepseek.com")
        val model = field("", "deepseek-flash")
        val vision = field("", "留空复用文字模型")
        val apiKey = field("", "留空保持不变；已保存的 Key 会继续使用", password = true)
        val timeout = field("90", "秒", numeric = true)
        row(ai, "base_url", baseUrl)
        row(ai, "模型", model)
        row(ai, "图片模型", vision)
        row(ai, "API Key", apiKey)
        row(ai, "超时", timeout)
        val clearKey = Ui.button(activity, "清除已保存 Key", false)
        ai.addView(clearKey)

        val quiz = section("刷题行为")
        val autoFinalize = switchRow(quiz, "写入后自动交卷", false)
        val confirmFinalize = switchRow(quiz, "交卷前二次确认", true)
        val unattended = switchRow(quiz, "启动无人值守", false)
        val silent = switchRow(quiz, "静默模式（不弹预览）", true)
        val useBank = switchRow(quiz, "优先使用本地题库", true)
        val bankLearn = switchRow(quiz, "交卷后按官方结果自学习", true)
        val delayEnabled = switchRow(quiz, "启用随机延迟", true)
        val delayPerQuestion = switchRow(quiz, "逐题随机间隔", true)
        val lowConfidence = field("0.7", "0~1", numeric = true)
        val maxTasks = field("20", "单轮最多任务数", numeric = true)
        val retry = field("2", "失败重试次数", numeric = true)
        val batchSize = field("10", "每批题数", numeric = true)
        row(quiz, "低置信阈值", lowConfidence)
        row(quiz, "最多任务", maxTasks)
        row(quiz, "重试次数", retry)
        row(quiz, "每批题数", batchSize)

        val delay = section("延迟与节奏")
        val delayQMin = field("4", "秒", numeric = true)
        val delayQMax = field("12", "秒", numeric = true)
        val delaySMin = field("2", "分钟", numeric = true)
        val delaySMax = field("6", "分钟", numeric = true)
        val delayBMin = field("3", "分钟", numeric = true)
        val delayBMax = field("12", "分钟", numeric = true)
        row(delay, "逐题最小", delayQMin)
        row(delay, "逐题最大", delayQMax)
        row(delay, "交卷最小", delaySMin)
        row(delay, "交卷最大", delaySMax)
        row(delay, "任务间隔最小", delayBMin)
        row(delay, "任务间隔最大", delayBMax)

        val material = section("资料任务")
        val matMin = field("30", "秒", numeric = true)
        val matMax = field("90", "秒", numeric = true)
        val matTick = field("10", "秒", numeric = true)
        val matGapMin = field("15", "秒", numeric = true)
        val matGapMax = field("45", "秒", numeric = true)
        row(material, "每份最短", matMin)
        row(material, "每份最长", matMax)
        row(material, "上报节拍", matTick)
        row(material, "间隔最短", matGapMin)
        row(material, "间隔最长", matGapMax)

        val skip = section("跳过规则")
        skip.addView(Ui.text(activity,
            "一行一条；留空使用脚本内置默认规则。", 11, Ui.MUTED))
        val skipRules = field("", "例如：谈谈你\n心得体会\n请上传", multi = true)
        skip.addView(skipRules, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun cfgText(key: String, default: String = ""): String =
            ConfigStore.profile(root, names[profileSpinner.selectedItemPosition])
                .optString(key, default)

        fun loadIntoForm(cfg: JSONObject, profileName: String) {
            loading = true
            val pIndex = providers.indexOfFirst { it.id == cfg.optString("provider", "deepseek") }
                .coerceAtLeast(0)
            providerSpinner.setSelection(pIndex, false)
            baseUrl.setText(cfg.optString("baseUrl", providers[pIndex].base))
            model.setText(cfg.optString("model", providers[pIndex].model))
            vision.setText(cfg.optString("modelVision", providers[pIndex].vision))
            timeout.setText(cfg.optInt("timeoutSec", 90).toString())
            autoFinalize.isChecked = cfg.optBoolean("autoFinalize", false)
            confirmFinalize.isChecked = cfg.optBoolean("confirmBeforeFinalize", true)
            unattended.isChecked = cfg.optBoolean("unattended", false)
            silent.isChecked = cfg.optBoolean("silent", true)
            useBank.isChecked = cfg.optBoolean("useBank", true)
            bankLearn.isChecked = cfg.optBoolean("bankLearn", true)
            delayEnabled.isChecked = cfg.optBoolean("delayEnabled", true)
            delayPerQuestion.isChecked = cfg.optBoolean("delayPerQuestion", true)
            lowConfidence.setText(cfg.optDouble("lowConfidence", 0.7).toString())
            maxTasks.setText(cfg.optInt("uaMaxTasks", 20).toString())
            retry.setText(cfg.optInt("uaRetry", 2).toString())
            batchSize.setText(cfg.optInt("batchSize", 10).toString())
            delayQMin.setText(cfg.optInt("delayQuestionMin", 4).toString())
            delayQMax.setText(cfg.optInt("delayQuestionMax", 12).toString())
            delaySMin.setText(cfg.optInt("delaySubmitMin", 2).toString())
            delaySMax.setText(cfg.optInt("delaySubmitMax", 6).toString())
            delayBMin.setText(cfg.optInt("delayBetweenMin", 3).toString())
            delayBMax.setText(cfg.optInt("delayBetweenMax", 12).toString())
            matMin.setText(cfg.optInt("matMin", 30).toString())
            matMax.setText(cfg.optInt("matMax", 90).toString())
            matTick.setText(cfg.optInt("matTick", 10).toString())
            matGapMin.setText(cfg.optInt("matGapMin", 15).toString())
            matGapMax.setText(cfg.optInt("matGapMax", 45).toString())
            val rules = cfg.optJSONArray("skipRules")
            skipRules.setText(
                if (rules == null) "" else (0 until rules.length())
                    .joinToString("\n") { rules.optString(it, "") }
            )
            val hasKey = if (slot >= 0) {
                ConfigStore.slotHasApiKey(activity, slot) ||
                    ConfigStore.profileHasApiKey(activity, profileName)
            } else ConfigStore.profileHasApiKey(activity, profileName)
            apiKey.hint = if (hasKey) "已保存 Key；留空保持不变" else "留空表示不设置"
            assignment.text = if (slot >= 0)
                "当前实例基础方案：${ConfigStore.assignment(root, slot)}；正在编辑：$profileName"
            else "当前方案：$profileName"
            loading = false
        }

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?,
                                        position: Int, id: Long) {
                if (loading) return
                val p = providers[position]
                if (p.base.isNotEmpty()) baseUrl.setText(p.base)
                if (p.model.isNotEmpty()) model.setText(p.model)
                vision.setText(p.vision)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        fun selectedProfile() = names[profileSpinner.selectedItemPosition]

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?,
                                        position: Int, id: Long) {
                if (loading) return
                val name = names[position]
                val cfg = if (slot >= 0) ConfigStore.resolved(root, slot)
                else ConfigStore.profile(root, name)
                loadIntoForm(cfg, name)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        fun collect(): JSONObject {
            val cfg = JSONObject()
            val p = providers[providerSpinner.selectedItemPosition]
            cfg.put("provider", p.id)
            cfg.put("proto", p.proto)
            cfg.put("baseUrl", baseUrl.text.toString().trim())
            cfg.put("model", model.text.toString().trim())
            cfg.put("modelVision", vision.text.toString().trim())
            cfg.put("timeoutSec", timeout.text.toString().toIntOrNull() ?: 90)
            cfg.put("autoFinalize", autoFinalize.isChecked)
            cfg.put("confirmBeforeFinalize", confirmFinalize.isChecked)
            cfg.put("unattended", unattended.isChecked)
            cfg.put("silent", silent.isChecked)
            cfg.put("useBank", useBank.isChecked)
            cfg.put("bankLearn", bankLearn.isChecked)
            cfg.put("delayEnabled", delayEnabled.isChecked)
            cfg.put("delayPerQuestion", delayPerQuestion.isChecked)
            cfg.put("lowConfidence", lowConfidence.text.toString().toDoubleOrNull() ?: 0.7)
            cfg.put("uaMaxTasks", maxTasks.text.toString().toIntOrNull() ?: 20)
            cfg.put("uaRetry", retry.text.toString().toIntOrNull() ?: 2)
            cfg.put("batchSize", batchSize.text.toString().toIntOrNull() ?: 10)
            cfg.put("delayQuestionMin", delayQMin.text.toString().toIntOrNull() ?: 4)
            cfg.put("delayQuestionMax", delayQMax.text.toString().toIntOrNull() ?: 12)
            cfg.put("delaySubmitMin", delaySMin.text.toString().toIntOrNull() ?: 2)
            cfg.put("delaySubmitMax", delaySMax.text.toString().toIntOrNull() ?: 6)
            cfg.put("delayBetweenMin", delayBMin.text.toString().toIntOrNull() ?: 3)
            cfg.put("delayBetweenMax", delayBMax.text.toString().toIntOrNull() ?: 12)
            cfg.put("matMin", matMin.text.toString().toIntOrNull() ?: 30)
            cfg.put("matMax", matMax.text.toString().toIntOrNull() ?: 90)
            cfg.put("matTick", matTick.text.toString().toIntOrNull() ?: 10)
            cfg.put("matGapMin", matGapMin.text.toString().toIntOrNull() ?: 15)
            cfg.put("matGapMax", matGapMax.text.toString().toIntOrNull() ?: 45)
            val rules = skipRules.text.toString().lines()
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (rules.isNotEmpty()) cfg.put("skipRules", JSONArray(rules))
            return cfg
        }

        fun persist(apply: Boolean) {
            val name = selectedProfile()
            val cfg = collect()
            if (slot >= 0) {
                ConfigStore.assign(root, slot, name)
                ConfigStore.setOverride(root, slot, cfg)
            } else {
                ConfigStore.setProfile(root, name, cfg)
            }
            val key = apiKey.text.toString()
            if (key.isNotEmpty()) {
                if (slot >= 0) ConfigStore.setSlotApiKey(activity, slot, key)
                else ConfigStore.setProfileApiKey(activity, name, key)
            }
            ConfigStore.save(activity, root)
            if (apply) {
                if (slot >= 0) {
                    ConfigStore.applyToSlot(activity, root, slot)
                    if (key.isNotEmpty()) ConfigStore.setSlotApiKey(activity, slot, key)
                } else {
                    ConfigStore.applyToAll(activity, root)
                }
            }
            Toast.makeText(activity,
                if (apply) "已保存并应用" else "已保存", Toast.LENGTH_SHORT).show()
            after?.invoke()
        }

        val actions = Ui.row(activity)
        val save = Ui.button(activity, "保存", false)
        val apply = Ui.button(activity, if (slot >= 0) "保存并应用到本实例" else "保存并应用全部", true)
        actions.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(apply, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(actions)

        val actions2 = Ui.row(activity)
        val defaults = Ui.button(activity, "恢复默认值", false)
        val newProfile = Ui.button(activity, "新建方案", false)
        val deleteProfile = Ui.button(activity, "删除方案", false)
        actions2.addView(defaults)
        actions2.addView(newProfile)
        actions2.addView(deleteProfile)
        box.addView(actions2)

        val close = Ui.button(activity, "关闭", false)
        box.addView(close)

        val dialog = AlertDialog.Builder(activity).setView(scroll)
            .setNegativeButton(null, null).create()

        save.setOnClickListener { persist(false) }
        apply.setOnClickListener { persist(true) }
        defaults.setOnClickListener {
            loadIntoForm(ConfigStore.defaults(), selectedProfile())
        }
        clearKey.setOnClickListener {
            if (slot >= 0) ConfigStore.setSlotApiKey(activity, slot, null)
            else ConfigStore.setProfileApiKey(activity, selectedProfile(), null)
            apiKey.setText("")
            apiKey.hint = "已清除；留空表示不设置"
            Toast.makeText(activity, "已清除 API Key", Toast.LENGTH_SHORT).show()
        }
        newProfile.setOnClickListener {
            val input = EditText(activity).apply { hint = "方案名称" }
            AlertDialog.Builder(activity)
                .setTitle("新建配置方案")
                .setView(input)
                .setPositiveButton("创建") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) return@setPositiveButton
                    ConfigStore.setProfile(root, name, collect())
                    ConfigStore.save(activity, root)
                    dialog.dismiss()
                    show(activity, slot, after)
                }
                .setNegativeButton("取消", null)
                .show()
        }
        deleteProfile.setOnClickListener {
            val name = selectedProfile()
            if (name == ConfigStore.DEFAULT_PROFILE) {
                Toast.makeText(activity, "默认方案不能删除", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(activity)
                .setTitle("删除方案：$name")
                .setMessage("已分配到这个方案的实例会回退到默认方案。")
                .setPositiveButton("删除") { _, _ ->
                    ConfigStore.deleteProfile(activity, root, name)
                    ConfigStore.save(activity, root)
                    dialog.dismiss()
                    show(activity, slot, after)
                }
                .setNegativeButton("取消", null)
                .show()
        }
        close.setOnClickListener { dialog.dismiss() }

        val initialName = if (slot >= 0) ConfigStore.assignment(root, slot)
        else names.first()
        val initialIndex = names.indexOf(initialName).coerceAtLeast(0)
        profileSpinner.setSelection(initialIndex, false)
        val initialCfg = if (slot >= 0) ConfigStore.resolved(root, slot)
        else ConfigStore.profile(root, initialName)
        loadIntoForm(initialCfg, initialName)

        dialog.show()
    }
}
