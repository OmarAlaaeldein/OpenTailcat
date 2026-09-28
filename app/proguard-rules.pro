# Tailcat Proguard / R8 Optimization Rules

# 1. Keep the Go Mobile JNI surface (the AAR's consumer rule keeps the same).
# App models are parsed with org.json, not reflection, and manifest
# components are kept by AAPT's generated rules.
-keep class com.tailcat.golib.engine.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. Coroutines & Flow optimization
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 4. Strip release debug logging
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# 5. Suppress harmless warnings
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.Unsafe

# Tink references these compile-time-only nullness/error-prone annotations. They do not
# participate in encrypted preference behavior at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
