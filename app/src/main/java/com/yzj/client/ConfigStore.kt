package com.yzj.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Non-secret configuration lives in config.json. API keys are migrated into
 * SecureStore and never written to the JSON file.
 */
object ConfigStore {
    const val DEFAULT_PROFILE = "默认"
    private const val GM_PREFS = "yzj_gm"
    private const val API_KEY = "apiKey"

    fun defaults(): JSONObject = JSONObject().apply {
        put("provider", "deepseek")
        put("proto", "openai")
        put("baseUrl", "https://api.deepseek.com")
        put("model", "deepseek-flash")
        put("modelVision", "deepseek-flash")
        put("autoFinalize", false)
        put("confirmBeforeFinalize", true)
        put("readScoreAfterFinalize", true)
        put("showTokens", true)
        put("lowConfidence", 0.7)
        put("timeoutSec", 90)
        put("unattended", false)
        put("uaFinalize", true)
        put("uaSkipAnswered", true)
        put("uaRetry", 2)
        put("uaMaxTasks", 20)
        put("silent", true)
        put("lowConfStrict", false)
        put("uaOnly", "")
        put("batchSize", 10)
        put("noReason", false)
        put("useBank", true)
        put("bankLearn", true)
        put("delayEnabled", true)
        put("delayPerQuestion", true)
        put("delayQuestionMin", 4)
        put("delayQuestionMax", 12)
        put("delaySubmitMin", 2)
        put("delaySubmitMax", 6)
        put("delayBetweenMin", 3)
        put("delayBetweenMax", 12)
        put("skipEnabled", true)
        put("maxTokensCap", 16000)
        put("reasoningEffort", "high")
        put("matMin", 30)
        put("matMax", 90)
        put("matTick", 10)
        put("matGapMin", 15)
        put("matGapMax", 45)
    }

    private fun file(context: Context) = File(context.filesDir, "config.json")

    private fun emptyRoot(): JSONObject = JSONObject().apply {
        put("profiles", JSONObject().put(DEFAULT_PROFILE, defaults()))
        put("assignments", JSONObject())
        put("overrides", JSONObject())
    }

    @Synchronized
    fun load(context: Context): JSONObject {
        val f = file(context)
        if (!f.isFile) return emptyRoot()
        val root = runCatching { JSONObject(f.readText()) }.getOrElse { return emptyRoot() }
        ensure(root)
        return root
    }

    @Synchronized
    fun save(context: Context, root: JSONObject) {
        ensure(root)
        sanitizeSecrets(context, root)
        runCatching { file(context).writeText(root.toString(2)) }
    }

    private fun ensure(root: JSONObject) {
        runCatching {
            if (root.optJSONObject("profiles") == null) root.put("profiles", JSONObject())
            val profiles = root.getJSONObject("profiles")
            if (!profiles.has(DEFAULT_PROFILE)) profiles.put(DEFAULT_PROFILE, defaults())
            if (root.optJSONObject("assignments") == null) root.put("assignments", JSONObject())
            if (root.optJSONObject("overrides") == null) root.put("overrides", JSONObject())
        }
    }

    /** Move any imported apiKey fields into Keystore-backed storage. */
    private fun sanitizeSecrets(context: Context, root: JSONObject) {
        val profiles = root.optJSONObject("profiles")
        if (profiles != null) {
            val it = profiles.keys()
            while (it.hasNext()) {
                val name = it.next()
                val p = profiles.optJSONObject(name) ?: continue
                val key = p.optString(API_KEY, "")
                if (key.isNotEmpty()) {
                    SecureStore.put(context, SecureStore.profileApiKeyName(name), key)
                    p.remove(API_KEY)
                }
            }
        }
        val overrides = root.optJSONObject("overrides")
        if (overrides != null) {
            val it = overrides.keys()
            while (it.hasNext()) {
                val slot = it.next()
                val o = overrides.optJSONObject(slot) ?: continue
                val key = o.optString(API_KEY, "")
                if (key.isNotEmpty()) {
                    SecureStore.put(context, SecureStore.slotApiKeyName(slot.toIntOrNull() ?: 0), key)
                    o.remove(API_KEY)
                }
            }
        }
    }

    fun profileNames(root: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val profiles = root.optJSONObject("profiles")
        if (profiles != null) {
            val it = profiles.keys()
            while (it.hasNext()) out.add(it.next())
        }
        if (DEFAULT_PROFILE !in out) out.add(0, DEFAULT_PROFILE)
        return out
    }

    fun profile(root: JSONObject, name: String): JSONObject =
        root.optJSONObject("profiles")?.optJSONObject(name) ?: defaults()

    fun setProfile(root: JSONObject, name: String, profile: JSONObject) {
        runCatching {
            val profiles = root.optJSONObject("profiles")
                ?: JSONObject().also { root.put("profiles", it) }
            profiles.put(name, profile)
        }
    }

    fun deleteProfile(context: Context, root: JSONObject, name: String) {
        if (name == DEFAULT_PROFILE) return
        root.optJSONObject("profiles")?.remove(name)
        val assignments = root.optJSONObject("assignments")
        if (assignments != null) {
            val it = assignments.keys()
            while (it.hasNext()) {
                val slot = it.next()
                if (assignments.optString(slot) == name) {
                    runCatching { assignments.put(slot, DEFAULT_PROFILE) }
                }
            }
        }
        SecureStore.remove(context, SecureStore.profileApiKeyName(name))
    }

    fun assignment(root: JSONObject, slot: Int): String {
        val name = root.optJSONObject("assignments")?.optString(slot.toString(), "") ?: ""
        return if (name.isEmpty() || name !in profileNames(root)) DEFAULT_PROFILE else name
    }

    fun assign(root: JSONObject, slot: Int, name: String) {
        runCatching {
            val a = root.optJSONObject("assignments")
                ?: JSONObject().also { root.put("assignments", it) }
            a.put(slot.toString(), name)
        }
    }

    fun override(root: JSONObject, slot: Int): JSONObject =
        root.optJSONObject("overrides")?.optJSONObject(slot.toString()) ?: JSONObject()

    fun setOverride(root: JSONObject, slot: Int, value: JSONObject) {
        runCatching {
            val o = root.optJSONObject("overrides")
                ?: JSONObject().also { root.put("overrides", it) }
            if (value.length() == 0) o.remove(slot.toString())
            else o.put(slot.toString(), value)
        }
    }

    fun resolved(root: JSONObject, slot: Int): JSONObject =
        JSONObject().apply {
            merge(profile(root, assignment(root, slot)))
            merge(override(root, slot))
        }

    private fun JSONObject.merge(src: JSONObject) {
        val it = src.keys()
        while (it.hasNext()) {
            val k = it.next()
            runCatching { put(k, src.get(k)) }
        }
    }

    fun profileHasApiKey(context: Context, profile: String): Boolean =
        SecureStore.has(context, SecureStore.profileApiKeyName(profile))

    fun slotHasApiKey(context: Context, slot: Int): Boolean =
        SecureStore.has(context, SecureStore.slotApiKeyName(slot))

    fun getProfileApiKey(context: Context, profile: String): String? =
        SecureStore.get(context, SecureStore.profileApiKeyName(profile))

    fun getSlotApiKey(context: Context, slot: Int): String? =
        SecureStore.get(context, SecureStore.slotApiKeyName(slot))

    fun effectiveApiKey(context: Context, root: JSONObject, slot: Int): String? {
        val slotKey = getSlotApiKey(context, slot)
        if (!slotKey.isNullOrEmpty()) return slotKey
        return getProfileApiKey(context, assignment(root, slot))
    }

    fun setProfileApiKey(context: Context, profile: String, key: String?) {
        SecureStore.put(context, SecureStore.profileApiKeyName(profile), key)
    }

    fun setSlotApiKey(context: Context, slot: Int, key: String?) {
        SecureStore.put(context, SecureStore.slotApiKeyName(slot), key)
    }

    fun applyToSlot(context: Context, root: JSONObject, slot: Int) {
        val cfg = resolved(root, slot)
        val prefs = context.getSharedPreferences(GM_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val it = cfg.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k == API_KEY) continue
            val v = cfg.opt(k)
            if (v == null || v == JSONObject.NULL) continue
            val raw = if (v is String) JSONObject.quote(v) else v.toString()
            editor.putString("slot$slot:$k", raw)
        }
        editor.apply()
        // A profile-level key is the shared default. A per-instance override
        // key is written by the config dialog after this call.
        getProfileApiKey(context, assignment(root, slot))?.let {
            SecureStore.put(context, SecureStore.slotApiKeyName(slot), it)
        }
    }

    fun applyToAll(context: Context, root: JSONObject) {
        for (slot in 0 until InstanceStore.count(context)) applyToSlot(context, root, slot)
    }

    fun clearSlotPrefs(context: Context, slot: Int) {
        val prefs = context.getSharedPreferences(GM_PREFS, Context.MODE_PRIVATE)
        val prefix = "slot$slot:"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
        SecureStore.remove(context, SecureStore.slotApiKeyName(slot))
    }

    fun pretty(obj: JSONObject): String = runCatching { obj.toString(2) }.getOrDefault(obj.toString())

    fun parse(text: String): JSONObject = JSONObject(text.trim())
}
