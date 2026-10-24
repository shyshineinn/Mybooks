package com.huangder.lumibooks.data.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** 把本地封面压缩到服务端 5MB 限制内并上传。 */
class MyBooksCoverUploader(
    private val client: MyBooksApiClient
) {
    suspend fun upload(file: File, token: String): String = withContext(Dispatchers.IO) {
        if (!file.isFile) error("找不到本地封面文件")
        val bytes = compress(file)
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        withContext(Dispatchers.IO) { client.uploadCover(dataUrl, token) }
    }

    private fun compress(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("封面图片无法解析")
        }
        var maxSide = 1600
        var quality = 88
        while (true) {
            val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
                ?: error("封面图片无法解析")
            val scaled = scaleDown(decoded, maxSide)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            val bytes = out.toByteArray()
            if (bytes.size <= 5 * 1024 * 1024) return bytes
            if (quality > 60) quality -= 10 else maxSide = (maxSide * 0.8f).toInt()
            if (maxSide < 600) error("封面图片过大，无法压缩到 5MB 以内")
        }
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxSide && h / 2 >= maxSide) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
