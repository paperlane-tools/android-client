package com.yzj.client

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BankStore {
    private const val PREFS = "yzj_gm"
    private const val BANK = "answerBank"
    private const val NOTES = "bankNotes"
    private const val BANK_DIR = "banks"

    data class Parsed(
        val bank: JSONObject,
        val notes: JSONObject?,
        val source: String
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(slot: Int, name: String) = "slot$slot:$name"

    fun count(context: Context, slot: Int): Int {
        val raw = readValue(context, slot, BANK) ?: "{}"
        return countEntries(runCatching { JSONObject(raw) }.getOrElse { JSONObject() })
    }

    private fun countEntries(bank: JSONObject): Int {
        var n = 0
        val it = bank.keys()
        while (it.hasNext()) if (!isMetaKey(it.next())) n++
        return n
    }

    private fun isMetaKey(key: String): Boolean =
        key.startsWith("__") || key in setOf(
            "schema", "slot", "exportedAt", "count", "latestAt",
            "account", "stuId", "version", "app"
        )

    fun exportJson(context: Context, slot: Int): JSONObject {
        val out = JSONObject()
        runCatching {
            val bank = cleanBank(
                JSONObject(readValue(context, slot, BANK) ?: "{}")
            )
            val notes = JSONObject(readValue(context, slot, NOTES) ?: "{}")
            out.put("schema", "yzj-bank-export/v1")
            out.put("slot", slot)
            out.put("exportedAt", System.currentTimeMillis())
            out.put("answerBank", bank)
            out.put("bankNotes", notes)
        }
        return out
    }

    fun exportToFile(context: Context, slot: Int): String {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "yzj-export"
        )
        if (!dir.isDirectory && !dir.mkdirs()) error("无法创建导出目录")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val out = File(dir, "bank-slot${slot + 1}-$stamp.json")
        out.writeText(exportJson(context, slot).toString(2))
        return out.absolutePath
    }

    /**
     * Accepts web flat export, app wrapper, raw object, array, or CSV.
     */
    fun importData(context: Context, slot: Int, text: String): Parsed {
        val parsed = parse(text.trim().removePrefix("\uFEFF"))
        if (parsed.bank.length() == 0) error("没有解析到题库条目")
        writeValue(context, slot, BANK, parsed.bank.toString())
        if (parsed.notes != null) {
            writeValue(context, slot, NOTES, parsed.notes.toString())
        } else {
            removeValue(context, slot, NOTES)
        }
        return parsed
    }

    fun clear(context: Context, slot: Int) {
        removeValue(context, slot, BANK)
        removeValue(context, slot, NOTES)
    }

    fun summary(context: Context, slot: Int): String {
        val n = count(context, slot)
        return if (n == 0) "题库为空" else "题库 $n 条"
    }

    /**
     * The quiz engine runs in an account process while imports happen in the
     * main process. SharedPreferences can keep a stale per-process cache, so
     * banks use app-private files that both processes can see immediately.
     */
    internal fun readValue(context: Context, slot: Int, name: String): String? {
        val target = dataFile(context, slot, name)
        if (target.isFile) {
            val value = runCatching { target.readText() }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        val legacy = prefs(context).getString(key(slot, name), null) ?: return null
        // Migrate the v0.4.5 SharedPreferences representation on first read.
        runCatching { writeValue(context, slot, name, legacy) }
        return legacy
    }

    internal fun writeValue(context: Context, slot: Int, name: String, value: String) {
        val target = dataFile(context, slot, name)
        val dir = target.parentFile ?: error("题库目录无效")
        if (!dir.isDirectory && !dir.mkdirs()) error("无法创建题库目录")
        val tmp = File(target.absolutePath + ".tmp")
        tmp.writeText(value)
        runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure {
            runCatching {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.onFailure {
                target.writeText(value)
                tmp.delete()
            }
        }
    }

    internal fun removeValue(context: Context, slot: Int, name: String) {
        dataFile(context, slot, name).delete()
        val legacyKey = key(slot, name)
        if (prefs(context).contains(legacyKey)) {
            prefs(context).edit().remove(legacyKey).apply()
        }
    }

    private fun dataFile(context: Context, slot: Int, name: String): File {
        require(name == BANK || name == NOTES) { "unsupported bank field" }
        return File(File(context.filesDir, BANK_DIR), "slot${slot}_$name.json")
    }

    private fun parse(text: String): Parsed {
        if (text.isEmpty()) error("文件为空")
        if (!text.startsWith("{") && !text.startsWith("[")) return parseCsv(text)
        return if (text.startsWith("[")) {
            Parsed(arrayToBank(JSONArray(text)), null, "array")
        } else {
            parseObjectRoot(JSONObject(text))
        }
    }

    private fun parseObjectRoot(root: JSONObject): Parsed {
        val rootNotes = root.optJSONObject("bankNotes")
            ?: root.optJSONObject("notes")
            ?: root.optJSONObject("bank_notes")
        val wrappers = listOf("answerBank", "bank", "data", "entries", "items", "questions")
        for (name in wrappers) {
            when (val value = root.opt(name)) {
                is JSONObject -> {
                    val child = parseObjectRoot(value)
                    return child.copy(
                        source = "json:$name",
                        notes = child.notes ?: rootNotes
                    )
                }
                is JSONArray -> return Parsed(arrayToBank(value), null, "json:$name")
            }
        }
        return Parsed(cleanBank(root), rootNotes, "json")
    }

    private fun cleanBank(root: JSONObject): JSONObject {
        val out = JSONObject()
        var entries = 0
        val it = root.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (isMetaKey(k)) continue
            when (val v = root.opt(k)) {
                is JSONObject -> {
                    out.put(k, v); entries++
                }
                is String -> {
                    out.put(k, JSONObject().put("a", v)); entries++
                }
                is Number, is Boolean -> {
                    out.put(k, JSONObject().put("a", v.toString())); entries++
                }
                null, JSONObject.NULL -> Unit
            }
        }
        // A single entry object imported directly.
        if (entries == 0 && (root.has("a") || root.has("answer"))) {
            out.put("imported", root)
        }
        return out
    }

    private fun arrayToBank(array: JSONArray): JSONObject {
        val out = JSONObject()
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is JSONObject -> {
                    val k = firstNonEmpty(
                        item.optString("key"), item.optString("k"),
                        item.optString("id"), item.optString("qid")
                    ).ifEmpty { "import:$i" }
                    val entry = JSONObject()
                    val keyNames = setOf("key", "k", "id", "qid")
                    val answerNames = setOf("answer", "ans", "value")
                    val fields = item.keys()
                    while (fields.hasNext()) {
                        val field = fields.next()
                        if (field in keyNames || field in answerNames) continue
                        copyIf(entry, item, field)
                    }
                    val answer = firstNonEmpty(
                        item.optString("a"), item.optString("answer"),
                        item.optString("ans"), item.optString("value")
                    )
                    if (answer.isNotEmpty()) entry.put("a", answer)
                    if (entry.length() > 0) out.put(k, entry)
                }
                is JSONArray -> {
                    val k = item.optString(0, "import:$i")
                    val a = item.optString(1, "")
                    if (k.isNotEmpty() && a.isNotEmpty()) {
                        out.put(k, JSONObject().put("a", a))
                    }
                }
                is String -> out.put("import:$i", JSONObject().put("a", item))
            }
        }
        return out
    }

    private fun copyIf(target: JSONObject, source: JSONObject, key: String) {
        val value = source.opt(key)
        if (value != null && value != JSONObject.NULL && value.toString().isNotEmpty()) {
            target.put(key, value)
        }
    }

    private fun parseCsv(text: String): Parsed {
        val rows = csvRows(text)
        if (rows.isEmpty()) error("CSV 为空")
        val header = rows[0].map { it.trim().lowercase(Locale.ROOT) }
        fun idx(vararg names: String): Int {
            for (n in names) {
                val i = header.indexOf(n.lowercase(Locale.ROOT))
                if (i >= 0) return i
            }
            return -1
        }
        val keyIdx = idx("key", "k", "id", "qid")
        val ansIdx = idx("答案", "answer", "a", "ans")
        if (ansIdx < 0) error("CSV 没有答案列")
        val qIdx = idx("题干", "question", "q")
        val tIdx = idx("题型", "type", "t")
        val atIdx = idx("写入时间", "time", "at", "timestamp")
        val srcIdx = idx("来源", "source", "src")
        val bank = JSONObject()
        var imported = 0
        for (r in 1 until rows.size) {
            val row = rows[r]
            val answer = row.getOrNull(ansIdx)?.trim().orEmpty()
            if (answer.isEmpty()) continue
            val rawKey = if (keyIdx >= 0) row.getOrNull(keyIdx)?.trim().orEmpty() else ""
            val key = rawKey.ifEmpty { "csv:$r" }
            val entry = JSONObject().put("a", answer)
            if (qIdx >= 0) row.getOrNull(qIdx)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { entry.put("q", it) }
            if (tIdx >= 0) row.getOrNull(tIdx)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { entry.put("t", it) }
            if (srcIdx >= 0) row.getOrNull(srcIdx)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { entry.put("src", it) }
            entry.put("at",
                if (atIdx >= 0) parseTime(row.getOrNull(atIdx)?.trim().orEmpty())
                else System.currentTimeMillis())
            bank.put(key, entry)
            imported++
        }
        if (imported == 0) error("CSV 没有可导入的答案行")
        return Parsed(bank, null, "csv")
    }

    private fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    row.add(field.toString()); field.setLength(0)
                }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (field.isNotEmpty() || row.isNotEmpty()) {
                        row.add(field.toString()); rows.add(row.toList())
                        row.clear(); field.setLength(0)
                    }
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString()); rows.add(row.toList())
        }
        return rows
    }

    private fun parseTime(raw: String): Long {
        if (raw.isEmpty()) return System.currentTimeMillis()
        val formats = listOf(
            "yyyy/M/d H:mm:ss", "yyyy-MM-dd HH:mm:ss",
            "yyyy/M/d HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (f in formats) {
            runCatching {
                return SimpleDateFormat(f, Locale.CHINA).parse(raw)?.time
                    ?: System.currentTimeMillis()
            }
        }
        return System.currentTimeMillis()
    }

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotEmpty() } ?: ""
}
