# AI 跑步伴侣 ProGuard 规则
# 保留序列化数据类
-keep class com.healthyagent.running.core.model.** { *; }
-keep class com.healthyagent.running.ai.client.** { *; }

# Kotlin 序列化
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
