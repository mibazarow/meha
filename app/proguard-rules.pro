# =============================================================================
# R8 / ProGuard keep rules for the RELEASE build of «Меха» (com.andharness.ru)
#
# Strategy
# --------
# The release build now enables R8 minification + obfuscation + resource shrinking
# (see app/build.gradle.kts, release buildType). This file adds ONLY the rules the
# app itself needs on top of:
#   1) the AGP default profile  -> getDefaultProguardFile("proguard-android-optimize.txt")
#      (R8 full mode, native-method names, enum values()/valueOf(), @Keep, Parcelable,
#       and the manifest-component keep rules that aapt2/AGP feed to R8);
#   2) the consumer rules (proguard.txt / META-INF/proguard/*.pro) that the
#      dependencies ship inside their AAR/JAR artifacts. R8 applies these
#      automatically, so they are deliberately NOT duplicated here:
#        - kotlinx.serialization-core-jvm  (META-INF/proguard/kotlinx-serialization-common.pro:
#          @Serializable companions + generated serializers + descriptor field + annotations)
#        - Room (room-runtime: `-keep class * extends androidx.room.RoomDatabase`)
#        - Hilt/Dagger (hilt-android: @EntryPoint/@EarlyEntryPoint kept for the reflective cast)
#        - Jetpack Compose runtime/ui (source-info + ModifierNodeElement + accessibility rules)
#        - Glance (glance-appwidget: GeneratedMessageLite fields + ActionCallback subclasses)
#        - WorkManager (work-runtime: ListenableWorker names + constructors + WorkerParameters)
#        - AndroidX Startup (Initializer subclasses)
#        - Material Components, androidx.core, androidx.sqlite
#        - OkHttp (optional TLS providers), kotlinx-coroutines (ServiceLoader + volatile fields)
#        - SQLCipher (net.zetetic.database.** incl. JNI descriptors)
#        - ML Kit (text-recognition / common / vision / bundled-common) and Google Play
#          services (basement/tasks/base): native-method names, @UsedBy, SafeParcelable,
#          ReflectedParcelable, optional-provider -dontwarn lines
#        - pdfbox-android (SecurityHandler reflection + document-interchange classes)
#
# Everything the app reaches ONLY through reflection, JNI, or a class name baked
# into a string is kept explicitly below. Where the safe answer was uncertain we
# kept the whole library/package rather than risk a runtime NoClassDefFoundError.
#
# Sources / references used for these decisions:
#   [1] R8 / shrinking overview and keep-rule semantics:
#       https://developer.android.com/build/shrink-code
#   [2] R8 keep-rule syntax (-keep / -keepclassmembers / -keepclasseswithmembernames,
#       -if / -dontwarn, missing-class handling):
#       https://developer.android.com/build/shrink-code#keep-code
#       https://r8.googlesource.com/r8/+/refs/heads/main/doc/keeprules.md
#   [3] kotlinx.serialization official ProGuard rules (shipped as a consumer rule since
#       1.4; verified inside kotlinx-serialization-core-jvm-1.7.3.jar):
#       https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/proguard-rules.pro
#       https://kotlinlang.org/docs/serialization.html
#   [4] Consumer rules actually inspected in this project's Gradle cache
#       (~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<ver>/*.aar|*.jar),
#       i.e. each library's proguard.txt / META-INF/proguard/*.pro (listed above).
#
# NOTE: the debug-only DebugToolReceiver (app/src/debug/...) is not part of the
# release variant, so it is intentionally absent from this file.
# =============================================================================


# -----------------------------------------------------------------------------
# 0. Attributes needed by reflective/annotation-based code paths.
#    The serialization/Hilt/GMS consumer rules also add parts of this; keeping the
#    annotations is harmless and avoids ordering surprises. [1][2]
# -----------------------------------------------------------------------------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod


# -----------------------------------------------------------------------------
# 1. Manifest-declared components (Activities, Services, Receivers, Providers)
#    and the custom Application are kept automatically: aapt2/AGP generate keep
#    rules (aapt_rules.txt) from the merged AndroidManifest and pass them to R8.
#    No manual rules are required here. [1]
# -----------------------------------------------------------------------------


# -----------------------------------------------------------------------------
# 2. JNI: the native libraries of Tesseract4Android, ONNX Runtime, SQLCipher and
#    ML Kit resolve Java natives by name (Class + method), so names must survive.
#    This is the same rule the AGP default profile and ML Kit ship; repeated here
#    so the requirement is explicit and independent of the default profile. [3]
# -----------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}


# -----------------------------------------------------------------------------
# 3. Enums.
#    Several models persist an enum's `name` and read it back by comparing
#    `entries.firstOrNull { it.name == raw }` (e.g. SshAuthType, IdentityType,
#    InteractionChannel/Direction). Kotlin/JVM bakes the constant name literal
#    into the constructor, so `name()` stays stable under obfuscation; this rule
#    additionally preserves values()/valueOf() and disables enum unboxing. [1]
# -----------------------------------------------------------------------------
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# -----------------------------------------------------------------------------
# 4. @Keep support (androidx.annotation). The app itself does not use @Keep today,
#    but keeping this in place matches the AGP default profile and is future-proof. [1]
# -----------------------------------------------------------------------------
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * {*;}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}


# -----------------------------------------------------------------------------
# 5. Parcelable CREATOR fields (framework Parcelables are used via
#    getParcelableExtra / putParcelableArrayListExtra, e.g. Uri, UsbDevice, Intent).
#    Standard rule from the AGP default profile. [1]
# -----------------------------------------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}


# =============================================================================
# 6. jsch (com.github.mwiede:jsch) — SSH exec + SFTP.
#
#    jsch loads its crypto/kex/host-key/hash implementations REFLECTIVELY by class
#    name: JSch.class ships string defaults such as
#    "com.jcraft.jsch.jce.AES128CTR", "com.jcraft.jsch.jce.SignatureRSA",
#    "com.jcraft.jsch.bc.Twofish128CBC", "com.jcraft.jsch.jzlib.Compression",
#    and DHGEX/KeyExchange/Session/HostKey call Class.forName() on them. R8 would
#    otherwise strip or rename those classes and break every SSH session, so the
#    entire package is kept (it is small: ~316 classes) — the conservative choice.
#
#    The package also references OPTIONAL dependencies that are not on the Android
#    classpath (BouncyCastle for the `bc` variants, JNA for the OS agent connector,
#    log4j for optional logging, Kerberos/GSS): suppress the corresponding
#    missing-class warnings so R8 does not fail the build. [1][2]
# =============================================================================
-keep class com.jcraft.jsch.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn com.sun.jna.platform.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**


# =============================================================================
# 7. Tesseract4Android (cz.adaptech.tesseract4android) + Leptonica.
#
#    The AAR ships NO consumer rules. Its JNI layer looks classes/members up by
#    name (native methods nativeConstruct/nativeInit/... are matched against the
#    Java class name, and the native OCR progress path calls back into the
#    protected TessBaseAPI.onProgressValues(...) by name). The library is small
#    (~40 classes), so keep it whole. [1][3]
# =============================================================================
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }


# =============================================================================
# 8. ONNX Runtime Mobile (com.microsoft.onnxruntime:onnxruntime-android).
#
#    The AAR ships NO consumer rules. Its JNI glue finds Java classes
#    (ai/onnxruntime/...) by name and reads/writes Java fields (OrtSession,
#    OnnxTensor, session options, ...) through JNI field IDs, so obfuscating the
#    package would produce UnsatisfiedLinkError / NoSuchFieldError. ~59 classes,
#    kept whole. Used for the bundled offline embedding model. [1][3]
# =============================================================================
-keep class ai.onnxruntime.** { *; }


# =============================================================================
# 9. PDFBox-Android (com.tom-roush:pdfbox-android).
#
#    The AAR ships consumer rules for SecurityHandler reflection and the
#    document-interchange package; these are applied automatically (not repeated).
#    It additionally references the optional JPEG2000 decoder `com.gemalto.jp2.*`,
#    which is NOT bundled/declared, so suppress the missing-class warning (JPX
#    images were already unsupported without that dependency). [1][2]
# =============================================================================
-dontwarn com.gemalto.jp2.**


# =============================================================================
# 10. Apache Commons Compress / CSV / XZ.
#
#    commons-compress and commons-csv reference OPTIONAL codecs that are not on
#    the runtime classpath (zstd, brotli, commons-io, commons-codec). XZ is a real
#    dependency (org.tukaani:xz) and is therefore available, but its absence would
#    only affect the 7z codec, which is guarded at runtime. Suppress the optional
#    missing-class warnings; the reachable codecs are kept by normal reachability. [1][2]
# =============================================================================
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.apache.commons.io.**
-dontwarn org.apache.commons.codec.**


# =============================================================================
# 11. kxml2 — DocumentSupport.newPullParser() falls back to
#     Class.forName("org.kxml2.io.KXmlParser") only when the platform
#     XmlPullParserFactory fails (i.e. in bare JVM unit tests). On Android that
#     class lives in the boot classpath (not in android.jar), so R8 reports it as
#     a missing class: suppress the warning. The literal string is left untouched,
#     so the runtime lookup still works. [1][2]
# =============================================================================
-dontwarn org.kxml2.io.**


# =============================================================================
# 12. Assorted optional annotations/providers referenced by third-party jars
#     (javax.annotation / jspecify / errorprone / checkerframework are annotation-only
#     deps that some libraries mention in metadata; slf4j is pulled in transitively).
#     None of them are used reflectively at runtime. [1][2]
# =============================================================================
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn org.slf4j.**


# =============================================================================
# 13. Genuinely ABSENT optional dependencies (first R8 run surfaced these).
#     AGP generated them in app/build/outputs/mapping/release/missing_rules.txt.
#     Both are compile-time-only / optional code paths that are never reached on
#     Android, so warning suppression is the correct minimal fix (-dontwarn only
#     silences the missing-class warning; the classes stay unreferenced at runtime,
#     which is fine because the code path that needs them is opt-in). [1][2]
#
#     - edu.umd.cs.findbugs.annotations.SuppressFBWarnings: a static-analysis
#       annotation referenced by commons-csv (SOURCE-retention, not bundled, never
#       needed at runtime).
#     - org.newsclub.net.unix.* (junixsocket): OPTIONAL dependency of jsch's
#       JUnixSocketFactory (AF_UNIX sockets); not declared in the app, and the app
#       only uses jsch's regular TCP socket path.
# =============================================================================
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.newsclub.net.unix.**
