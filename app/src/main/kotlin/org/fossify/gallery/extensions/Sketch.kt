package org.fossify.gallery.extensions

import com.github.panpf.sketch.transition.ViewCrossfadeTransition

/**
 * Creates a [ViewCrossfadeTransition.Factory] that only crossfades when loading from a non-memory
 * source (i.e. skips the fade when the image is served instantly from memory cache).
 *
 * This matches the previous Glide behaviour where cache hits didn't trigger the crossfade
 * animation, keeping the UI snappy.
 */
fun sketchCrossfadeTransition(durationMillis: Int = 300): ViewCrossfadeTransition.Factory {
    return ViewCrossfadeTransition.Factory(
        durationMillis = durationMillis,
        fadeStart = true,
        preferExactIntrinsicSize = false,
        alwaysUse = false,
    )
}
