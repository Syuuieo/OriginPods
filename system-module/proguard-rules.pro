# LSPosed entry point and hooks
-keep class com.originpods.app.hook.OriginPodsXposedEntry { *; }
-keep class com.originpods.app.hook.** { *; }
-keep interface com.originpods.app.hook.ProcessHook { *; }

# libxposed API / service
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.originpods.integration.**$$serializer { *; }
-keepclassmembers class com.originpods.integration.** { *** Companion; }
-keepclasseswithmembers class com.originpods.integration.** { kotlinx.serialization.KSerializer serializer(...); }
