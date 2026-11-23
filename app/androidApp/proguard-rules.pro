# Keep JNA & UniFFI generated native bindings
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.saltnote_ffi.** { *; }
-keep class com.saltnote.bridge.** { *; }
-keep class com.saltnote.android.** { *; }

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-dontwarn com.sun.jna.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.imageio.**
-dontwarn javax.sound.**
-dontwarn sun.misc.Unsafe
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
