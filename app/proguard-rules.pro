# VisionLink Android - ProGuard/R8 混淆规则
#
# 原则：只保留 JNI 按名绑定 / 反射加载所需的类；
# AndroidX、OkHttp、协程等自带 consumer rules 的库不再手动 keep，
# 否则 R8 无法裁剪，APK 显著变大。

# ============ 通用 ============

# native 方法按名绑定 JNI，不能被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# 崩溃堆栈可读性
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature
-keepattributes *Annotation*

# 移除日志 (Release 构建) — 保留 e 和 w 用于错误诊断
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ============ LiteRT-LM / LiteRT (JNI 按名绑定) ============

-keep class com.google.ai.edge.litertlm.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn org.tensorflow.lite.**

# ============ MediaPipe Tasks Vision (JNI + 按名反射) ============

-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ============ ML Kit Text Recognition (自带 consumer rules，仅抑制警告) ============

-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text.**

# ============ ONNX Runtime (JNI 按名绑定) ============

-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ============ Rokid CXR-L SDK (反射调用) ============

-keep class com.rokid.cxr.** { *; }
-keep interface com.rokid.cxr.** { *; }
-keep class com.rokid.cxr.Caps { *; }
-keep class com.rokid.cxr.Caps$* { *; }
-keep class com.rokid.sprite.aiapp.externalapp.** { *; }
-dontwarn com.rokid.**

# ============ OkHttp / Okio (自带 consumer rules，仅抑制可选依赖警告) ============

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============ 优化 ============

-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# ============ R8 缺失类抑制 (javax.lang.model 来自注解处理器，不在 Android 运行时) ============

-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
