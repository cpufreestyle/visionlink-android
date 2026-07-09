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

# ============ ML Kit Text Recognition 保护 ============

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-keep class com.google.android.gms.vision.text.** { *; }
-dontwarn com.google.mlkit.**

# ============ MediaPipe 保护 ============

-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }
-keep class com.google.mediapipe.tasks.vision.** { *; }
-keep class com.google.mediapipe.tasks.vision.objectdetector.** { *; }
-keep class com.google.mediapipe.tasks.vision.handlandmarker.** { *; }
-keep class com.google.mediapipe.tasks.vision.core.** { *; }
-keep class com.google.mediapipe.tasks.components.** { *; }
-keep class com.google.mediapipe.tasks.core.** { *; }
-dontwarn com.google.mediapipe.**

# ============ CameraX 保护 ============

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.*

# ============ ONNX Runtime 保护 ============

-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ============ SpeechBrain / PyTorch 模型保护 ============

-keep class org.pytorch.** { *; }
-dontwarn org.pytorch.**

# ============ CXR-L SDK 保护 ============

# 保护 Rokid CXR-L SDK
-keep class com.rokid.cxr.** { *; }
-keep interface com.rokid.cxr.** { *; }
-keep class com.rokid.cxr.link.** { *; }
-keep class com.rokid.cxr.link.callbacks.** { *; }
-keep class com.rokid.cxr.link.utils.** { *; }
-keep class com.rokid.cxr.Caps { *; }
-keep class com.rokid.cxr.Caps$* { *; }

# 保护 Rokid Sprite AI App 外部接口
-keep class com.rokid.sprite.aiapp.externalapp.** { *; }
-keep class com.rokid.sprite.aiapp.externalapp.auth.** { *; }
-keep class com.rokid.sprite.aiapp.externalapp.example.** { *; }
-dontwarn com.rokid.**

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

# ============ CrashReporter 保护 ============

-keep class com.visionlink.android.utils.CrashReporter { *; }
-keep class com.visionlink.android.utils.CrashReporter$Companion { *; }

# 移除日志 (Release 构建) — 保留 e 和 w 用于错误诊断

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
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

# R8 缺失类抑制 (javax.lang.model 来自注解处理器，不在 Android 运行时)
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
