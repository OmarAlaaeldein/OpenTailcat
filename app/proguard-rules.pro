# Tailcat Proguard / R8 Optimization Rules

# 1. Keep Tailcat domain models & JNI interfaces
-keep class com.tailcat.vpn.core.model.** { *; }
-keep class com.tailcat.vpn.service.TailcatVpnService { *; }
-keep class engine.Engine { *; }
-keep class com.tailcat.vpn.engine.Engine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2. Coroutines & Flow optimization
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# 3. CBOR & Jackson Serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**

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
