# Full builds can load Android CloudStream .cs3 packages dynamically.
# Full builds load third-party .cs3 DEX against these exact JVM names and signatures.
# Keep the host-facing CloudStream ABI stable: plugins are compiled separately and
# resolve these classes/methods by their original bytecode references at runtime.
-keep class com.lagradost.cloudstream3.** { *; }
-keep interface com.lagradost.cloudstream3.** { *; }
-keep class com.lagradost.api.** { *; }
-keep interface com.lagradost.api.** { *; }

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

# CloudStream plugins are compiled outside this application and can link to these
# bundled runtime libraries by their original JVM names. R8 cannot infer those
# references from the host app's static call graph.
-keep class org.mozilla.javascript.** { *; }
-keep interface org.mozilla.javascript.** { *; }
-keep class org.jsoup.** { *; }
-keep interface org.jsoup.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keep class com.lagradost.nicehttp.** { *; }
-keep interface com.lagradost.nicehttp.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-keep interface org.schabi.newpipe.extractor.** { *; }
-keep class com.google.common.** { *; }
-keep interface com.google.common.** { *; }
-keep class kotlinx.datetime.** { *; }
-keep interface kotlinx.datetime.** { *; }

# Rhino has an optional JavaBeans bridge for desktop JVMs. Android does not ship
# java.beans; suppress the optional-reference warning without pretending this
# supplies those classes at runtime. The Android runtime must not invoke that bridge.
-dontwarn java.beans.**
