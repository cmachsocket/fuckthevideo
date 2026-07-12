# ---------- libxposed (Vector) 模块专项 ----------

# 1. 反射加载的入口类必须保留(允许 R8 重命名内部字段,但不能丢)
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 2. java_init.list 在 R8 后会引用到重命名后的类名,这个规则告诉 R8
#    在写入 java_init.list 时把类名同步替换
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 3. 注解包来自 libxposed 编译器,运行时不需要
-dontwarn io.github.libxposed.annotation.**

# 4. 我们自己写的 hook 类可以正常混淆,只要不裸继承 XposedModule
# (若需要 keep 完整签名,再加)
