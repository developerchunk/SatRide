# SatRide release rules.
#
# Most libraries here ship their own `consumer-rules.pro` and need nothing from us — Compose, Room,
# OkHttp, DataStore, the Firebase SDKs and AndroidX all do. What is left is the handful of places
# where something is looked up by *name* at runtime, which R8 cannot see and therefore removes or
# renames. Each block below says which mechanism it is protecting, because a keep rule with no
# stated reason is one nobody can ever safely delete.

# --- Crash reports worth reading ---------------------------------------------------------------
# Obfuscation is left on; these attributes are what let Crashlytics map a stack trace back through
# `mapping.txt`. Without SourceFile/LineNumberTable every frame reports as unknown.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-renamesourcefileattribute SourceFile

# --- kotlinx.serialization ---------------------------------------------------------------------
# Navigation Compose's type-safe routes ARE serializers: every `SatRideRoute` is `@Serializable`, and
# the library resolves each one's generated `$$serializer` reflectively when it encodes a
# destination's arguments. Lose these and navigation fails at runtime — on the *release* build
# only — with a serializer-not-found error.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-dontnote kotlinx.serialization.**

# The app's own route and DTO types, kept whole. They are small, there are few of them, and the
# serializer rules above depend on the class shape surviving intact.
-keep,includedescriptorclasses class com.developerstring.satride.navigation.SatRideRoute { *; }
-keep,includedescriptorclasses class com.developerstring.satride.navigation.SatRideRoute$* { *; }
-keep @kotlinx.serialization.Serializable class com.developerstring.satride.** { *; }

# --- Ktor ---------------------------------------------------------------------------------------
# Engines are discovered through a `ServiceLoader`-style lookup over `HttpClientEngineContainer`
# implementations, so the OkHttp engine has no compile-time reference anywhere in this app.
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class io.ktor.client.** { *; }
-keep class io.ktor.serialization.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
# Ktor references JVM-server and Slf4j classes that are simply absent on Android.
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**

# --- Koin ---------------------------------------------------------------------------------------
# Definitions are resolved by type. The constructor DSL keeps that compile-time, but view models
# are still instantiated reflectively through the ViewModel factory, so their constructors have to
# survive.
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keepclassmembers class com.developerstring.satride.** {
    public <init>(...);
}
-dontwarn org.koin.**

# --- Room ---------------------------------------------------------------------------------------
# The generated `_Impl` classes are loaded by name from the abstract database class.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.developerstring.satride.core.database.** { *; }
-dontwarn androidx.room.paging.**

# --- Firebase -----------------------------------------------------------------------------------
# Auth and Firestore models are read reflectively when documents are mapped to objects, and
# Crashlytics' own rules want the exception classes intact.
-keep class com.developerstring.satride.core.auth.** { *; }
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- Kotlin runtime -----------------------------------------------------------------------------
# Coroutines' internal service loader, and the enum machinery `valueOf` reflection needs.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn kotlin.Experimental
-dontwarn java.lang.invoke.StringConcatFactory
