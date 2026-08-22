package dev.ahmedmohamed.hayai.source.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object SourcePreviewBitmapDecoder {
    fun decode(bytes: ByteArray, maximumDimension: Int = 512): Bitmap {
        require(maximumDimension in 64..2_048)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION) {
            "Preview image dimensions are invalid"
        }
        var sample = 1
        while (bounds.outWidth / sample > maximumDimension || bounds.outHeight / sample > maximumDimension) sample *= 2
        return requireNotNull(
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            ),
        ) { "Preview image could not be decoded" }
    }

    private const val MAX_SOURCE_DIMENSION = 16_384
}
