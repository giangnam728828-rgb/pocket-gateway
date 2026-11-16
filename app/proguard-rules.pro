# 序列化数据类保留
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor / OkHttp / Kotlinx 协程
-dontwarn kotlinx.**
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
