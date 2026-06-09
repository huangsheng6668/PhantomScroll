# PhantomScroll R8/ProGuard Rules

# Keep App Entry Points
-keep class com.phantom.scroll.PhantomScrollApp { *; }
-keep class com.phantom.scroll.MainActivity { *; }
-keep class com.phantom.scroll.service.PhantomScrollService { *; }

# Keep Lifecycle and SavedState internals that use reflection
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.android.HandlerContext {
    *** handler;
}

# Log suppression rules for release builds (PhantomLog handles debugging flow, but R8 can strip android.util.Log references if any remain)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
