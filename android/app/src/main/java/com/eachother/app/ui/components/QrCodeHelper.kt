package com.eachother.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Pure ZXing offline QR helper (bitmap generation & image-file decoding via Android Photo Picker).
 * Requires zero CAMERA permission.
 */
object QrCodeHelper {
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeQrFromUri(context: Context, uri: Uri): String? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1024) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(DecodeHintType.TRY_HARDER to true)
            QRCodeReader().decode(binaryBitmap, hints).text
        } catch (_: Exception) {
            null
        }
    }

    fun extractCodeFromPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("eachother://", ignoreCase = true)) {
            val uri = Uri.parse(trimmed)
            val codeParam = uri.getQueryParameter("code")
            if (!codeParam.isNullOrBlank()) {
                return codeParam.trim()
            }
        }
        return trimmed
    }
}
