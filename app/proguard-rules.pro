-keep class org.fossify.** { *; }
-dontwarn android.graphics.Canvas
-dontwarn org.fossify.**
-dontwarn org.apache.**

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# RenderScript
-keepclasseswithmembernames class * {
native <methods>;
}
-keep class androidx.renderscript.** { *; }

# Reprint
-keep class com.github.ajalt.reprint.module.** { *; }

# Sketch image loader
-keep class com.github.panpf.sketch.** { *; }
-dontwarn com.github.panpf.sketch.**

# JXL decoder
-keep class com.awxkee.jxlcoder.** { *; }
-dontwarn com.awxkee.jxlcoder.**

# Penfeizhou animated image libs (APNG, AVIF, animated WebP)
-keep class com.github.penfeizhou.animation.** { *; }
-dontwarn com.github.penfeizhou.animation.**

# OkIO (used by Sketch internally)
-dontwarn okio.**
