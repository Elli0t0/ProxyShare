# Strip Log.v / Log.d calls from release builds.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# JNI: keep native method names so the engine can find them.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# JNI: the engine calls EngineListener.onEvent from Rust, so the interface
# and its method must survive R8 with their names intact.
-keep class com.example.proxyshare.engine.EngineListener {
    *;
}
