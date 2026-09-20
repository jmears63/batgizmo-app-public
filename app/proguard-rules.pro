# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Native code in nativeusb.cpp looks up this class/method by name
# (FindClass / GetStaticMethodID). Do not rename or strip them.
-keep class org.batgizmo.app.LiveDataBridge {
    public static void onDataBufferReady(long, int);
}

# Native audio_out.cpp calls Function1.invoke(Object) on the playback
# progress callback (Kotlin (Int) -> Unit). Keep the interface method name.
-keep class kotlin.jvm.functions.Function1 {
    public java.lang.Object invoke(java.lang.Object);
}