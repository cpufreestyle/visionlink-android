# VisionLink Android - ProGuard 混淆规则
# 保护 LiteRT-LM 和 LiteRT 的 native 库

# ============ 基础规则 ============

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ============ LiteRT-LM 保护 ============

# 保护 LiteRT-LM 的 native 方法
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保护 LiteRT-LM 的 JNI 接口
-keep class com.google.ai.edge.litertlm.LiteRTLM { *; }
-keep class com.google.ai.edge.litertlm.LiteRTLMOptions { *; }
-keep class com.google.ai.edge.litertlm.LiteRTLMInference { *; }

# ============ LiteRT (TFLite) 保护 ============

-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# 保护 TFLite 的 native 库
-keep,allowobfuscation class org.tensorflow.lite.Interpreter
-keep,allowobfuscation class org.tensorflow.lite.InterpreterFactory
-keep,allowobfuscation class org.tensorflow.lite.InterpreterApi

# ============ CameraX 保护 ============

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.*

# ============ CXR-M SDK 保护 (如果集成) ============

# 取消注释当集成真实 CXR-M SDK 时
# -keep class com.Rokid.cxrm.** { *; }
# -keep interface com.Rokid.cxrm.** { *; }

# ============ Kotlin 协程 ============

-keepclassmembers class kotlinx.coroutines.* {
    <fields>;
    <methods>;
}

# ============ JSON 序列化 ============

-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class org.json.** { *; }

# ============ 资源保留 ============

-keep class **.R$* { *; }
-keepclassmembers class **.R$* {
    public static <fields>;
}

# ============ 视图绑定 ============

-keepclassmembers class * implements android.view.ViewBinding {
    public static * inflate(android.view.LayoutInflater);
    public static * inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static * bind(android.view.View);
}

# ============ 异常保留 (调试用) ============

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============ 移除日志 (Release 构建) ============

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# ============ 优化 ============

-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# ============ 警告抑制 ============

-dontwarn com.google.ai.edge.litertlm.**
-dontwarn org.tensorflow.lite.**
-dontwarn androidx.camera.**
-dontwarn okio.**
