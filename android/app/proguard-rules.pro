# Keep AndroidX @Keep annotations and native entry points stable for the
# JNI bridge and Activity-owned callback surface.
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    native <methods>;
}