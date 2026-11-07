package com.yzj.client

import android.app.Activity
import android.content.Context

object InstanceStore {
    const val MIN = 2
    const val MAX = 6

    private fun prefs(context: Context) =
        context.getSharedPreferences("yzj_instances", Context.MODE_PRIVATE)

    fun count(context: Context): Int =
        prefs(context).getInt("count", 2).coerceIn(MIN, MAX)

    fun setCount(context: Context, count: Int) {
        prefs(context).edit().putInt("count", count.coerceIn(MIN, MAX)).apply()
    }

    fun activityClass(slot: Int): Class<out Activity> = when (slot) {
        0 -> Account0Activity::class.java
        1 -> Account1Activity::class.java
        2 -> Account2Activity::class.java
        3 -> Account3Activity::class.java
        4 -> Account4Activity::class.java
        else -> Account5Activity::class.java
    }

    fun label(slot: Int) = "实例 ${slot + 1}"
}
