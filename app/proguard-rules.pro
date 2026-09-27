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

# ---------------------------------------------------------------------------
# KeySync release hardening
# ---------------------------------------------------------------------------

# Keep line numbers so a release stack trace is actionable. Without this every
# crash report is a bare "SourceFile" with no line number.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Strip verbose/debug logging from release builds. The input hot path logs on
# every key event and every pointer-down; building those strings is pure cost in
# a shipped APK. Warnings and errors are deliberately kept.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}