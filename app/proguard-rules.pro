# Keep the JNI bridge — names are referenced from native code.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.hdrstacker.NativeHdr { *; }

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
