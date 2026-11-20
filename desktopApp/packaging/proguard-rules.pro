# Keep rules for the packaged release build (packageRelease*, createReleaseDistributable).
#
# Shrinking only: nothing is renamed or optimised (see the build script), so stack traces, lookups by name and
# serialised names all stay as they are.
#
# What is shrunk is AndroidX, which here is Compose and the libraries it brings (Material icons, Material 3,
# foundation, UI, lifecycle, collection). That is where the dead weight is: material-icons-extended alone carries
# about 7,000 icons, of which the app draws a few hundred. Compose is written to be shrunk and ships no reflection
# of its own beyond what the plugin's default rules already cover.
#
# Everything else is kept whole, because a large part of it is reached in ways the shrinker cannot see:
# - by reflection or by name: kotlinx.serialization serializers, Ktor and its engine (ServiceLoader), NewPipe's
#   service list, JavaFX's toolkit and pipelines, Rhino's lazily loaded built-ins, ICU's data and services,
#   SQLite's JDBC driver, dbus-java proxies, SLF4J's provider, Coil's service-loaded fetchers and decoders;
# - from native code: JavaCPP/FFmpeg (bytedeco), ONNX Runtime, Skiko, WebRTC, QuickJS, JNA, mpv, and our own
#   JNI entry points (the analyser, the Windows frame and media bridges);
# - by code that is not in the package at all: manga parser bundles downloaded at run time are loaded into the
#   app's class loader and may call any of kotatsu-parsers' dependencies (OkHttp, jsoup, org.json, coroutines);
# - by the scripting engines' host code.
# Our own code (com.cosmotune.**, com.my.kizzy.**) is kept whole too: it is serialised, found by name in settings
# and called back from native code.
-keep class !androidx.**, ** { *; }

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions, SourceFile, LineNumberTable,
    RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, MethodParameters, Record, PermittedSubclasses,
    NestHost, NestMembers

# AndroidX pieces found by name rather than referenced.
# Service-loaded implementations (ProGuard does not read META-INF/services on its own).
-keep class * implements androidx.compose.ui.platform.PlatformContext { *; }
-keepnames class androidx.** implements java.io.Serializable
# Lifecycle and SavedState create ViewModels and their factories reflectively.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keepclassmembers class androidx.lifecycle.** { public <init>(...); }

# Anything with a native method, and whatever it is called from.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The libraries above reference optional dependencies that are not on this classpath and are never reached here
# (Graal's optional compiler and native-image pieces, OkHttp's Conscrypt/BouncyCastle/OpenJSSE platforms, PDFBox's
# JBIG2/JPX codecs, Log4j and Commons Logging bridges, Android-only paths in shared libraries, and so on). Their
# classes are kept unchanged whatever is reported, so these warnings say nothing about the packaged app.
-ignorewarnings
-dontnote **
