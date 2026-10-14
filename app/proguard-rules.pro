# Keep JNI and reflection entry points for ApexContainer
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.apex.container.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
