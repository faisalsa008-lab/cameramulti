package com.example.multilenscamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/** Live preview filter: GPU-composited colour matrix on the PreviewView (needs COMPATIBLE/TextureView mode). */
fun View.applyColorFilter(matrix: ColorMatrix?) {
    if (matrix == null) {
        setLayerType(View.LAYER_TYPE_NONE, null)
    } else {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }
}

/** Post-processing for the saved photo (simple Bitmap pipeline). */
object ImageFilters {

    fun applyMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Decodes a JPEG, down-sampling by powers of two so the longest side is <= [maxSide] (avoids OOM on 50MP+ sensors). */
    fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("JPEG decode failed")
    }
}
