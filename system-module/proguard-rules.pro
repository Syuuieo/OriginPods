# LSPosed entry point (referenced reflectively via module.prop)
-keep public class com.originpods.app.hook.OriginPodsXposedEntry {
    public <init>();
    public void onPackageReady(...);
}

# libxposed API / service
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }
