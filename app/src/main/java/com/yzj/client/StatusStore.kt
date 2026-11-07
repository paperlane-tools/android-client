package com.yzj.client

import android.content.Context
import org.json.JSONObject
import java.io.File

object StatusStore {
    private fun file(context: Context, slot: Int) =
        File(context.filesDir, "account_status_$slot.json")

    fun write(context: Context, slot: Int, status: JSONObject) {
        val target = file(context, slot)
        val tmp = File(target.absolutePath + ".tmp")
        runCatching {
            tmp.writeText(status.toString())
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(status.toString())
                tmp.delete()
            }
        }
    }

    fun read(context: Context, slot: Int): JSONObject {
        val target = file(context, slot)
        if (!target.isFile) return JSONObject()
        return runCatching { JSONObject(target.readText()) }.getOrElse { JSONObject() }
    }

    fun delete(context: Context, slot: Int) {
        file(context, slot).delete()
    }
}
