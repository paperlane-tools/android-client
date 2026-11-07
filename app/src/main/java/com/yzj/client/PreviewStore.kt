package com.yzj.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream

object PreviewStore {
    fun file(context: Context, slot: Int) =
        File(context.filesDir, "instance_${slot}_preview.jpg")

    fun capture(context: Context, webView: WebView?, slot: Int) {
        if (webView == null) return
        val width = webView.width
        val height = webView.height
        if (width < 80 || height < 80) return
        runCatching {
            val outWidth = 480
            val outHeight = (height * (outWidth / width.toFloat())).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val scale = outWidth / width.toFloat()
            canvas.scale(scale, scale)
            webView.draw(canvas)
            FileOutputStream(file(context, slot)).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 65, it)
            }
            bitmap.recycle()
        }
    }

    fun delete(context: Context, slot: Int) {
        file(context, slot).delete()
    }
}
