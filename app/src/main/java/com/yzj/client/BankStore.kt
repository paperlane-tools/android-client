package com.yzj.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BankStore {
    private const val PREFS = "yzj_gm"
    private const val BANK = "answerBank"
    private const val NOTES = "bankNotes"
    private const val BANK_DIR = "banks"
    private const val LOCK_FILE = ".bank-sync.lock"
    private val PROCESS_LOCK = Any()

    data class Parsed(
        val bank: JSONObject,
        val notes: JSONObject?,
        val source: String
    )

    data class MergeResult(
        val incoming: Int,
        val added: Int,
        val updated: Int,
        val unchanged: Int,
        val finalCount: Int
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    fun previewMerge(context: Context, slot: Int, text: String): MergeResult {
        val parsed = parseImport(text)
        ensureSharedInitialized(context)
        val current = JSONObject(readSharedValue(context, BANK) ?: "{}")
        return mergeInto(current, parsed.bank).second
    }

    fun mergeData(context: Context, slot: Int, text: String): MergeResult {
        val parsed = parseImport(text)
        ensureSharedInitialized(context)
        return withFileLock(context) {
            val currentBank = JSONObject(readSharedValue(context, BANK) ?: "{}")
            val currentNotes = JSONObject(readSharedValue(context, NOTES) ?: "{}")
            val (mergedBank, result) = mergeInto(currentBank, parsed.bank)
            replaceFile(sharedDataFile(context, BANK), mergedBank.toString())
            if (parsed.notes != null) {
                val (mergedNotes, _) = mergeInto(currentNotes, parsed.notes)
                replaceFile(sharedDataFile(context, NOTES), mergedNotes.toString())
            }
            result
        }
    }

    fun clear(context: Context, slot: Int) {
        removeValue(context, slot, BANK)
        removeValue(context, slot, NOTES)
    }

    fun summary(context: Context, slot: Int): String {
        val n = count(context, slot)
        return if (n == 0) "共享题库为空" else "共享题库 $n 条"
    }

    /**
     * The quiz engine runs in an account process while imports happen in the
     * main process. SharedPreferences can keep a stale per-process cache, so
     * banks use app-private files that both processes can see immediately.
     */
    internal fun readValue(context: Context, slot: Int, name: String): String? {
        ensureSharedInitialized(context)
        return readSharedValue(context, name)
    }

    internal fun writeValue(context: Context, slot: Int, name: String, value: String) {
        withFileLock(context) {
            replaceFile(sharedDataFile(context, name), value)
        }
    }

    /**
     * The userscript writes the complete bank object. Merge it with the shared
     * file so two live instances cannot overwrite each other's new answers.
     * A deletion is recognised only when this instance had previously seen the
     * same entry; entries added by another instance meanwhile stay intact.
     */
    internal fun mergeValue(
        context: Context,
        slot: Int,
        name: String,
        incomingText: String,
        lastSeenText: String?
    ): String {
        val incoming = JSONObject(incomingText)
        ensureSharedInitialized(context)
        return withFileLock(context) {
            val current = runCatching {
                JSONObject(readSharedValue(context, name).orEmpty().ifBlank { "{}" })
            }.getOrElse { JSONObject() }
            val lastSeen = lastSeenText?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
            val merged = mergeEntries(current, incoming, lastSeen)
            replaceFile(sharedDataFile(context, name), merged.toString())
            merged.toString()
        }
    }

    internal fun removeValue(context: Context, slot: Int, name: String) {
        // Keep an explicit empty shared object. Deleting the file would make
        // the next launch treat old per-slot files as unmigrated and restore
        // a bank that the user deliberately cleared.
        writeValue(context, slot, name, "{}")
    }

    fun exportText(context: Context, slot: Int): String =
        exportJson(context, slot).toString(2)

    fun exportFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        return "yzj-bank-shared-$stamp.json"
    }

    private fun ensureSharedInitialized(context: Context) {
        val bankFile = sharedDataFile(context, BANK)
        val notesFile = sharedDataFile(context, NOTES)
        if (bankFile.isFile && notesFile.isFile) return
        withFileLock(context) {
            val bankTarget = sharedDataFile(context, BANK)
            val notesTarget = sharedDataFile(context, NOTES)
            if (!bankTarget.isFile) {
                replaceFile(bankTarget, migrateLegacy(context, BANK).toString())
            }
            if (!notesTarget.isFile) {
                replaceFile(notesTarget, migrateLegacy(context, NOTES).toString())
            }
        }
    }

    private fun migrateLegacy(context: Context, name: String): JSONObject {
        val merged = JSONObject()
        val pattern = Regex("^slot(\\d+)_${Regex.escape(name)}\\.json$")
        val dir = bankDir(context)
        var foundSlotFile = false
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val match = pattern.matchEntire(file.name) ?: return@forEach
            match.groupValues[1].toIntOrNull() ?: return@forEach
            foundSlotFile = true
            val fileValue = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (fileValue != null) mergeNewer(merged, fileValue)
        }
        // File storage was introduced for the same canonical bank. Legacy
        // SharedPreferences may be the older CSV import with different keys,
        // so it is only a fallback when no per-slot bank file exists.
        if (foundSlotFile) return merged
        val prefPattern = Regex("^slot(\\d+):${Regex.escape(name)}$")
        prefs(context).all.forEach { (key, raw) ->
            if (!prefPattern.matches(key) || raw !is String || raw.isBlank()) return@forEach
            val parsed = runCatching { JSONObject(raw) }.getOrNull()
            if (parsed != null) mergeNewer(merged, parsed)
        }
        return merged
    }

    private fun mergeEntries(
        current: JSONObject,
        incoming: JSONObject,
        lastSeen: JSONObject?
    ): JSONObject {
        val merged = JSONObject()
        current.keys().forEach { key ->
            current.optJSONObject(key)?.let { merged.put(key, it) }
        }
        if (lastSeen != null) {
            current.keys().forEach { key ->
                if (!incoming.has(key) && lastSeen.has(key)) {
                    val currentAt = merged.optJSONObject(key)?.optLong("at", 0L) ?: 0L
                    val seenAt = lastSeen.optJSONObject(key)?.optLong("at", 0L) ?: 0L
                    if (currentAt <= seenAt) merged.remove(key)
                }
            }
        }
        incoming.keys().forEach { key ->
            val next = incoming.optJSONObject(key) ?: return@forEach
            val previous = merged.optJSONObject(key)
            if (previous == null ||
                next.optLong("at", 0L) >= previous.optLong("at", 0L)
            ) {
                merged.put(key, next)
            }
        }
        return merged
    }

    private fun mergeNewer(target: JSONObject, source: JSONObject) {
        source.keys().forEach { key ->
            val next = source.optJSONObject(key) ?: return@forEach
            val previous = target.optJSONObject(key)
            if (previous == null ||
                next.optLong("at", 0L) >= previous.optLong("at", 0L)
            ) {
                target.put(key, next)
            }
        }
    }

    private fun readSharedValue(context: Context, name: String): String? =
        sharedDataFile(context, name).takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }

    private fun parseImport(text: String): Parsed {
        val parsed = parse(text.trim().removePrefix("\uFEFF"))
        if (parsed.bank.length() == 0) error("没有解析到题库条目")
        return parsed
    }

    private fun mergeInto(
        current: JSONObject,
        incoming: JSONObject
    ): Pair<JSONObject, MergeResult> {
        val merged = JSONObject(current.toString())
        var added = 0
        var updated = 0
        var unchanged = 0
        var total = 0
        incoming.keys().forEach { key ->
            if (isMetaKey(key)) return@forEach
            val next = incoming.optJSONObject(key) ?: return@forEach
            total++
            val previous = merged.optJSONObject(key)
            when {
                previous == null -> added++
                previous.toString() == next.toString() -> unchanged++
                else -> updated++
            }
            merged.put(key, next)
        }
        return merged to MergeResult(
            incoming = total,
            added = added,
            updated = updated,
            unchanged = unchanged,
            finalCount = countEntries(merged)
        )
    }

    private fun replaceFile(target: File, value: String) {
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

    private fun <T> withFileLock(context: Context, block: () -> T): T =
        synchronized(PROCESS_LOCK) {
            val dir = bankDir(context)
            if (!dir.isDirectory && !dir.mkdirs()) error("无法创建题库目录")
            FileChannel.open(
                File(dir, LOCK_FILE).toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ).use { channel ->
                channel.lock().use { block() }
            }
        }

    private fun bankDir(context: Context): File = File(context.filesDir, BANK_DIR)

    private fun sharedDataFile(context: Context, name: String): File {
        require(name == BANK || name == NOTES) { "unsupported bank field" }
        return File(bankDir(context), "shared_$name.json")
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
