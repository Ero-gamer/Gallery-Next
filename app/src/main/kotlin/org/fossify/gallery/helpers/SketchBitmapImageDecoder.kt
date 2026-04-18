package org.fossify.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.davemorrissey.labs.subscaleview.ImageDecoder
import com.github.panpf.sketch.BitmapImage
import com.github.panpf.sketch.cache.CachePolicy
import com.github.panpf.sketch.request.disallowAnimatedImage
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.sketch
import com.github.panpf.sketch.transform.RotateTransformation
import com.github.panpf.sketch.util.Size
import kotlinx.coroutines.runBlocking

/**
 * A [SubsamplingScaleImageView] [ImageDecoder] that uses Sketch to load the initial full-image
 * Bitmap preview.  This replaces the old Glide-based `MyGlideImageDecoder` and ensures the same
 * rich format support (JXL, AVIF, WebP, SVG-rasterised …) that the rest of the app uses.
 *
 * [degrees] is the EXIF rotation that SSIV should be told about; we apply the **inverse** here
 * so that the Bitmap is already oriented correctly and SSIV doesn't double-rotate.
 *
 * [cacheKey] is the Sketch memory/result cache key extra derived from [Medium.getSignature], used
 * to bust the cache when the file changes (e.g. after in-app rotation).
 */
class SketchBitmapImageDecoder(
    private val degrees: Int,
    private val cacheKey: String,
) : ImageDecoder {

    override fun decode(context: Context, uri: Uri): Bitmap {
        // Normalise the URI: SSIV may pass a "file://" URI, Sketch prefers plain paths.
        val path = uri.toString().let {
            when {
                it.startsWith("file://") -> it.removePrefix("file://")
                else -> it
            }
        }

        val request = ImageRequest(context, path) {
            // Use file signature as a cache-key extra so stale cache entries are skipped
            // after the file has been modified (e.g. after in-place rotation).
            memoryCacheKey(cacheKey)

            // Load at original resolution – SSIV handles its own tiling downsampling.
            size(Size(Int.MAX_VALUE, Int.MAX_VALUE))

            // ARGB_8888 is Sketch's default for Android; no explicit colorType call needed.

            // Apply inverse rotation so the Bitmap is upright; SSIV will then apply its own
            // orientation correction on top if needed.
            if (degrees != 0) {
                transformations(RotateTransformation(-degrees))
            }

            // Do not animate – we only need the first frame for SSIV.
            disallowAnimatedImage()
        }

        // The SSIV ImageDecoder interface is called from a background thread so
        // using runBlocking here is safe and avoids adding coroutine overhead.
        val result = runBlocking { context.sketch.execute(request) }

        return (result as? ImageResult.Success)?.image.let { image ->
            (image as? BitmapImage)?.bitmap
                ?: throw RuntimeException("Sketch could not decode image for SSIV: $path")
        }
    }
}
