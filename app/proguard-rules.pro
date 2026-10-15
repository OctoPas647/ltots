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
