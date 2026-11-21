# Keep JNA & UniFFI generated native bindings
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.saltnote_ffi.** { *; }
-dontwarn com.sun.jna.**
