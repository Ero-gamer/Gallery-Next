package org.fossify.gallery

import com.github.ajalt.reprint.core.Reprint
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.SingletonSketch
import org.fossify.commons.FossifyApp
import org.fossify.gallery.helpers.JxlSketchDecoder

class App : FossifyApp(), SingletonSketch.Factory {

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        Reprint.initialize(this)
        // Sketch is auto-initialized via SingletonSketch.Factory implemented above.
        // All format decoders (gif, animated-gif-koral, animated-webp, svg) auto-register
        // via ServiceLoader – no explicit addDecoder calls are needed for them.
        // Only the custom JXL decoder requires manual registration.
    }

    /**
     * Provides a customised [Sketch] singleton that additionally registers our [JxlSketchDecoder]
     * for still and animated JXL image support. All other decoders (BitmapFactory, GIF, WebP,
     * SVG …) are registered automatically via their respective module ServiceLoader entries.
     */
    override fun createSketch(context: PlatformContext): Sketch {
        return Sketch.Builder(context).apply {
            components {
                // Register JXL decoder before BitmapFactoryDecoder so .jxl files are caught first
                addDecoder(JxlSketchDecoder.Factory())
            }
        }.build()
    }
}
