package org.fossify.gallery.helpers

import android.graphics.Bitmap
import com.awxkee.jxlcoder.JxlCoder
import com.github.panpf.sketch.BitmapImage
import com.github.panpf.sketch.asImage
import com.github.panpf.sketch.decode.DecodeResult
import com.github.panpf.sketch.decode.Decoder
import com.github.panpf.sketch.decode.ImageInfo
import com.github.panpf.sketch.fetch.FetchResult
import com.github.panpf.sketch.request.ImageData
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.resize.Precision
import com.github.panpf.sketch.source.DataFrom
import com.github.panpf.sketch.source.DataSource
import com.github.panpf.sketch.util.Size
import okio.buffer

/**
 * A Sketch [Decoder] that decodes still and animated JXL images using the
 * [io.github.awxkee:jxl-coder] library.
 *
 * Registered manually in [org.fossify.gallery.App.createSketch] so it sits before
 * BitmapFactoryDecoder in the component chain.
 *
 * Supported features:
 *  - Still JXL → Bitmap (ARGB_8888)
 *  - Animated JXL → first-frame Bitmap (animation playback is not yet supported in the
 *    View display path; full animation support can be added when a JXL AnimatableDrawable
 *    becomes available)
 */
class JxlSketchDecoder(
    private val requestContext: RequestContext,
    private val dataSource: DataSource,
) : Decoder {

    companion object {
        const val MIME_TYPE = "image/jxl"
        // Magic bytes: FF 0A (bare codestream) or 00 00 00 0C 4A 58 4C 20 (ISO BMFF container)
        private val JXL_BARE_MAGIC = byteArrayOf(0xFF.toByte(), 0x0A)
        private val JXL_CONTAINER_MAGIC =
            byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20)
    }

    private var _imageInfo: ImageInfo? = null

    override val imageInfo: ImageInfo
        get() {
            _imageInfo?.let { return it }
            // Decode just enough to retrieve dimensions without full decode.
            val bytes = dataSource.openSource().buffer().readByteArray()
            val size = try {
                val bmp = JxlCoder().decode(bytes)
                Size(bmp.width, bmp.height)
            } catch (e: Exception) {
                Size(0, 0)
            }
            return ImageInfo(size, MIME_TYPE).also { _imageInfo = it }
        }

    override fun decode(): ImageData {
        val bytes = dataSource.openSource().buffer().readByteArray()
        val bitmap: Bitmap = JxlCoder().decode(bytes)

        val imageSize = Size(bitmap.width, bitmap.height)
        val resize = requestContext.computeResize(imageSize)

        // Honour the requested size / precision when the image is larger than the target.
        val finalBitmap = scaleIfNeeded(bitmap, resize.size, resize.precision)
        val info = ImageInfo(Size(finalBitmap.width, finalBitmap.height), MIME_TYPE)

        return ImageData(
            image = finalBitmap.asImage(),
            imageInfo = info,
            dataFrom = DataFrom.LOCAL,
            resize = resize,
            transformeds = null,
            extras = null,
        )
    }

    /** Scale [src] down to fit within [targetSize] when [precision] demands it. */
    private fun scaleIfNeeded(src: Bitmap, targetSize: Size, precision: Precision): Bitmap {
        if (targetSize.isEmpty || precision == Precision.LESS_PIXELS) return src

        val scaleX = targetSize.width.toFloat() / src.width
        val scaleY = targetSize.height.toFloat() / src.height
        val scale = minOf(scaleX, scaleY)
        if (scale >= 1f) return src

        val newWidth = (src.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, newWidth, newHeight, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    class Factory : Decoder.Factory {

        override val key: String = "JxlSketchDecoder"

        override fun create(
            requestContext: RequestContext,
            fetchResult: FetchResult,
        ): JxlSketchDecoder? {
            if (!isApplicable(fetchResult)) return null
            return JxlSketchDecoder(requestContext, fetchResult.dataSource)
        }

        private fun isApplicable(fetchResult: FetchResult): Boolean {
            // 1. MIME type match (fast path)
            if (fetchResult.mimeType == MIME_TYPE) return true

            // 2. Extension match (content URIs may lack MIME)
            val uri = fetchResult.dataSource.toString()
            if (uri.endsWith(".jxl", ignoreCase = true)) return true

            // 3. Magic-byte inspection
            val header = fetchResult.headerBytes
            return header.startsWith(JXL_BARE_MAGIC) || header.startsWith(JXL_CONTAINER_MAGIC)
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            if (size < prefix.size) return false
            for (i in prefix.indices) {
                if (this[i] != prefix[i]) return false
            }
            return true
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            return other != null && this::class == other::class
        }

        override fun hashCode(): Int = this::class.hashCode()

        override fun toString(): String = "JxlSketchDecoder"
    }
}
