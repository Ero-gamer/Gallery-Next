package org.fossify.gallery.svg

import android.widget.ImageView
import com.github.panpf.sketch.request.ImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.request.Listener

/**
 * Sketch [Listener] equivalent of the old Glide `SvgSoftwareLayerSetter`.
 *
 * Enables the software layer on the target [ImageView] when an SVG loads successfully so that
 * `PictureDrawable` renders correctly, and clears it on failure. Attach via
 * `ImageRequest.Builder.addListener(SvgSoftwareLayerSetter(imageView))`.
 */
class SvgSoftwareLayerSetter(private val view: ImageView) : Listener {

    override fun onSuccess(request: ImageRequest, result: ImageResult.Success) {
        view.setLayerType(ImageView.LAYER_TYPE_SOFTWARE, null)
    }

    override fun onError(request: ImageRequest, error: ImageResult.Error) {
        view.setLayerType(ImageView.LAYER_TYPE_NONE, null)
    }
}
