# The JNI bridge reaches CalculatorEngine by name. JNI_OnLoad finds the class and
# registers its external functions by name and signature, so they keep both; the
# native core calls its @Keep methods through GetStaticMethodID, which the AndroidX
# annotation library's consumer rules keep.
# scripts/android/check_jni_surface.sh checks every one in the minified dex.
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
