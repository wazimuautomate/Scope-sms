# R8 rules for release builds. Debug builds don't minify.
#
# Empty beyond the defaults for now — the Phase 0 scaffold has nothing that
# reflection can break. Rules get added by the phases that introduce the risk:
#
#  - Phase 5: the gateway's JSON models. Moshi/Retrofit resolve them
#    reflectively, so R8 will happily rename fields it thinks are unused and
#    the request silently serialises to the wrong shape. Keep the model
#    classes and generated adapters.
#  - Phase 3/8: Room entities.
#
# Worth stating plainly: minification is only exercised in release builds, and
# release builds only happen at Phase 11. That means an R8 bug in the gateway
# client would first appear in the APK the agent actually installs, after every
# debug build looked fine. Whoever wires up Phase 11 should run a release build
# through CI early rather than discovering this at tag time.

# Keep source line numbers in stack traces; the file name is hidden anyway.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Phase 5 — SCOPE SMS gateway client --------------------------------------
#
# Moshi reads the request/response models reflectively (see ScopeSmsGateway.create:
# the KotlinJsonAdapterFactory route, chosen over codegen to avoid an unverified
# Moshi-1.15.2-on-KSP2 dependency — see memory.md). Reflection means R8 cannot
# see the field reads, so without these rules it renames `senderId` to `a` and
# the gateway receives JSON it doesn't understand.
#
# This breaks in release only. Every debug CI run stays green.
-keep @androidx.annotation.Keep class com.scopesms.autoreply.network.** { *; }

# Moshi's reflective adapter needs Kotlin metadata and the generic signatures to
# reconstruct constructor parameters, nullability and default values.
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlin.Metadata { *; }
-dontwarn org.jetbrains.annotations.**

# Retrofit: the API is an interface resolved via a dynamic proxy, and its
# suspend-function return types live only in the generic signature.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowobfuscation interface <1>

# OkHttp/Okio ship rules of their own; these silence known-benign warnings about
# optional compile-time-only dependencies (Conscrypt, BouncyCastle, Animal Sniffer).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Phase 5b — Room ---------------------------------------------------------
# Room generates its implementations at compile time, but the entity fields are
# read reflectively by the generated code's column mapping.
-keep class com.scopesms.autoreply.queue.OutboundJob { *; }
-keep class com.scopesms.autoreply.queue.OutboundJobStatus { *; }
