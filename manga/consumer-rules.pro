# Imported Android extensions link to these APIs after release minification.
# Keep the bridge and the host libraries they compile against; no global R8 disable.
-keep class eu.kanade.tachiyomi.** { *; }
-keep interface eu.kanade.tachiyomi.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep interface uy.kohesive.injekt.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class rx.** { *; }
-keep class org.jsoup.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class nl.adaptivity.xmlutil.** { *; }
