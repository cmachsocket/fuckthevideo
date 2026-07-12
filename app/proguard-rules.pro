# ---------- LSPosed 模块专项 ----------

# 1. 保留所有 hook 入口类(LSPosed 通过反射加载)
-keep class io.github.lsposed.fuckthevideo.** { *; }

# 2. 保留 Xposed API stub —— 防止编译器把编译期引用抹掉
-keep class de.robv.android.xposed.** { *; }
-keep class de.robv.android.xposed.callbacks.** { *; }
-keep class de.robv.android.xposed.XC_MethodHook { *; }
-keep class de.robv.android.xposed.XposedHelpers { *; }
-dontwarn de.robv.android.xposed.**

# 3. Hook 反射的目标类不要在 app 内,但为安全
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
