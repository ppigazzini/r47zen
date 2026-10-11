# Keep AndroidX @Keep annotations and native entry points stable for the
# JNI bridge and Activity-owned callback surface.
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    native <methods>;
}
# Release builds keep warnings and errors and drop verbose, debug and info
# logging, which R8 can remove once it may assume the calls have no effect.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
