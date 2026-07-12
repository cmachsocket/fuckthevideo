# FuckTheVideo — LSPosed 模块模板

干净的 LSPosed(Xposed 兼容)模块脚手架。无需 root 即可**编译**,运行需要 LSPosed 框架(基于 Magisk / KernelSU 的 Zygisk)。

## 编译

**前置**

- Android Studio Hedgehog (2023.1.1) 或更新
- Android SDK Platform 34 + Build-Tools 34.0.0+
- JDK 17

**首次构建**

```bash
# 任选其一:
# 1) 用本机 gradle
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug

# 2) 直接用 Android Studio 打开本目录,Sync 后 Build > Make Project
#    (Studio 会自动下载 wrapper jar)
```

产物:`app/build/outputs/apk/debug/app-debug.apk`

## 上手机

1. 安装 APK 到手机
2. 打开 LSPosed Manager,确认模块已加载
3. 作用域勾选要 hook 的 app(默认 `*` = 全部)
4. 重启作用域内的 app,模块生效

## 项目结构

```
fuckthevideo/
├── app/
│   ├── build.gradle.kts               # app 模块
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml        # meta-data: xposedmodule / scope / minversion
│       ├── java/io/github/lsposed/fuckthevideo/
│       │   ├── FuckTheVideoModule.kt  # 入口(IXposedHookLoadPackage)
│       │   └── hook/
│       │       └── DemoHook.kt        # 示例 hook:拦 Activity.onCreate
│       └── res/values/
│           ├── strings.xml
│           └── arrays.xml             # xposed_scope 作用域
├── settings.gradle.kts
├── build.gradle.kts                   # project 级别
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml             # 版本目录
│   └── wrapper/
│       └── gradle-wrapper.properties
├── local.properties.template
└── README.md
```

## 改作用域

编辑 `app/src/main/res/values/arrays.xml`:

```xml
<string-array name="xposed_scope" translatable="false">
    <item>com.ss.android.ugc.aweme</item>   <!-- 抖音 -->
    <item>com.tencent.mm</item>             <!-- 微信 -->
    <!-- 全部应用 -->
    <!-- <item>*</item> -->
</string-array>
```

## 加自己的 Hook

1. 新建 `app/src/main/java/io/github/lsposed/fuckthevideo/hook/YourHook.kt`,实现 `IXposedHookLoadPackage`
2. 在 `FuckTheVideoModule.kt` 里 `new` 后分发给它

```kotlin
class YourHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.target.app") return

        XposedHelpers.findAndHookMethod(
            "com.target.app.SomeClass",
            lpparam.classLoader,
            "someMethod",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    p.args[0] = "intercepted"
                }
            })
    }
}
```

## 关于 LSPosed API v2

本模板用的是 Xposed 兼容写法(`IXposedHookLoadPackage` + `findAndHookMethod`)。LSPosed 还支持自家注解式 API v2(`@HookClass` / `@HookMethod` 等),需要引入注解处理器 + LSPosed 自家依赖,迁移成本不高,感兴趣再说。

## CI/CD — 自动 Release

`.github/workflows/release.yml` 已配置好,**推 tag 就出 APK**。

### 触发

匹配 `v*` 的 tag:

```bash
git tag v0.1.0              # 正式版
git tag v0.2.0-beta1        # pre-release(自动识别)
git push origin v0.1.0
```

几分钟后在仓库的 **Releases** 页就能下载 APK。

### 流程

1. checkout 拉全 git 历史
2. JDK 17 + Android SDK 34 + Build-Tools 34.0.0
3. **Bootstrap Gradle wrapper** — 仓库只提交了 `gradle-wrapper.properties`,没 commit wrapper jar 和 `gradlew` 脚本。CI 会先下 gradle 8.7 二进制,用 `gradle wrapper` 命令把缺的 wrapper artifacts 生成出来,缓存到下次复用
4. 从 tag 算出 `versionName`(`v0.1.0` → `0.1.0`)和 `versionCode`(epoch 后 7 位)
5. 自动写回 `app/build.gradle.kts`
6. `./gradlew :app:assembleRelease` 编译
7. 收集 `app-release.apk`
8. 取上一个 tag 到 HEAD 的全部 commit,自动生成 changelog
9. 创建 GitHub Release,上传 APK 到 assets(全文件持久可下)

### 签名

模板默认用 Android SDK 自带的 **debug keystore**(同一台开发机、GitHub runner 都是用 SDK 的 `~/.android/debug.keystore`)。Release APK 用这个签名能装能跑,只是不能上架;LSPosed Manager 加载时也不校验签名。

如果想换成自己的 keystore:

1. 生成:`keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias fuckthevideo`
2. Base64:`base64 -w0 release.jks > release.jks.b64`
3. 仓库 **Settings → Secrets and variables → Actions** 添加:
   - `KEYSTORE_BASE64` — 上面 b64 内容
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
4. 在 `app/build.gradle.kts` 里加个 `signingConfigs.create("release") { ... }`(从 `secrets` 读),并把 `signingConfig = signingConfigs.getByName("debug")` 改成 `"release"`
5. workflow 里加一步:`echo "$KEYSTORE_BASE64" | base64 -d > release.jks`,并在 `assembleRelease` 步骤设置 env

要我直接帮你接上正式签名的话,告诉我 keystore / alias / 密码准备好没有。

## License

MIT
