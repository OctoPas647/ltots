# --- Safe R8 -------------------------------------------------------------------------------
# Nothing is renamed: class names stay real (also makes any crash report readable).
-dontobfuscate
# Keep every library exactly as shipped except LToTS itself, Kotlin and AndroidX, which R8 may
# optimise (Compose relies on R8 for its release-mode speed). The download engine (yt-dlp,
# Apache Commons, Jackson), the glass library, Guava etc. are untouched.
-keep class !androidx.**,!kotlin.**,!kotlinx.**,!com.ltots.**,** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

# Keep Media3 session service entry points (referenced from the manifest).
-keep class com.ltots.app.playback.PlaybackService { *; }
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.errorprone.annotations.**

# yt-dlp wrapper uses Jackson reflection for its data classes.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.ext.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
-keep class com.ltots.app.download.DownloadService { *; }

# Rubber Band: the native code looks up this class, its native methods and its "handle" field by name.
-keep class com.breakfastquay.rubberband.** { *; }

# Apache Commons Compress (used by the download engine) can use XZ when it's present; it isn't.
-dontwarn org.tukaani.xz.**
