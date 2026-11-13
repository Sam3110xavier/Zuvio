# Full builds can load Android CloudStream .cs3 packages dynamically.
# Keep shrinking/obfuscation enabled, but skip R8 optimization passes that become
# prohibitively slow with the CloudStream compatibility runtime dependency graph.
-dontoptimize

# Some CloudStream providers are compiled against fuzzywuzzy and resolve this
# package by its original JVM name from dynamically loaded .cs3 dex files.
-keep class me.xdrop.fuzzywuzzy.** { *; }
-dontwarn me.xdrop.fuzzywuzzy.**

# Dynamic CloudStream providers can link directly against kotlinx.serialization
# ABI classes, so their JVM names must remain available in full release builds.
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Several providers also call CloudStream cryptography helpers directly.
-keep class dev.whyoleg.cryptography.** { *; }
-keep interface dev.whyoleg.cryptography.** { *; }
-dontwarn dev.whyoleg.cryptography.**


# CloudStream extensions are loaded from external .cs3 DEX files at runtime.
# R8 cannot see those references while shrinking the host APK. Preserve the
# binary names AND members of the runtime ABI used by the official NuvioTV and
# ARVIO sideload loaders; keeping only public/protected members is insufficient
# for Kotlin-generated bridges, reflective helpers and library internals.
-keep class com.lagradost.** { *; }
-keep interface com.lagradost.** { *; }
-dontwarn com.lagradost.**

-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }

-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.uwetrottmann.tmdb2.** { *; }
-keep class me.xdrop.fuzzywuzzy.** { *; }
-keep class dev.whyoleg.cryptography.** { *; }

# These packages expose optional JVM integrations that are not present on Android.
-dontwarn com.google.re2j.**
-dontwarn com.sun.jna.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
