# FuckTheVideo — Vector / libxposed 模块模板

干净的 **Vector** 模块脚手架(作者 JingMatrix,LSPosed 精神继承者,新版 Zygisk 框架)。
用现代 libxposed API(`io.github.libxposed:api`)。

> Vector: <https://github.com/JingMatrix/Vector>
> libxposed API: <https://github.com/libxposed/api>

## 编译

**前置**

- Android Studio Hedgehog (2023.1.1) 或更新
- Android SDK Platform 34 + Build-Tools 34.0.0+
- JDK 17
- (调试时)Vector 模块(基于 Magisk / KernelSU 的 Zygisk)

**首次构建**

```bash
# 任选其一:
# 1) 用本机 gradle
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug

# 2) 直接 Android Studio 打开,Sync 后 Build > Make Project
```

产物:`app/build/outputs/apk/debug/app-debug.apk`

## 上 Vector 装到手机

1. **APK 装到手机**(任意方式,adb push / 文件管理器都行)
2. 在 **Magisk / KernelSU → 模块** 装 Vector(没装会显示"未安装 Vector")
3. 装完后,**在 Vector 里把 FuckTheVideo 模块开关打开**,作用域勾选要 hook 的 app(默认 `*`)
4. 在 **模块的 app 图标**(可能没图标)或在 `am start` 里,无需启动 launcher Activity —— **你不需要主动打开模块 app**,Vector 框架会注入到作用域进程的 ClassLoader 加载它
5. 重启作用域内的 app

> ℹ️ 新版 libxposed 设计下,**模块 app 自身不会被自己 hook**。

## 项目结构

```
fuckthevideo/
├── app/
│   ├── build.gradle.kts               # app 模块,buildSdk 34 + JDK 17
│   ├── proguard-rules.pro             # libxposed 入口保留规则
│   └── src/main/
│       ├── AndroidManifest.xml        # 只剩 label + description
│       ├── java/io/github/lsposed/fuckthevideo/
│       │   ├── FuckTheVideoModule.kt  # 入口:继承 XposedModule
│       │   └── hook/
│       │       └── DemoHook.kt        # 示例 hook:拦 Activity.onCreate (OkHttp chain 风格)
│       ├── resources/META-INF/xposed/  ← libxposed 配置在这里
│       │   ├── java_init.list         # 入口类全限定名(framework 通过这个发现模块)
│       │   ├── scope.list             # 作用域:每行一个包名,或 *
│       │   └── module.prop            # minApiVersion / targetApiVersion / staticScope
│       └── res/values/
│           └── strings.xml
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml             # libxposed-api: 102.0.0
│   └── wrapper/gradle-wrapper.properties   # gradle 8.7
└── README.md
```

## 关键差异:libxposed vs 老 Xposed API

| 老 Xposed API | libxposed API |
| --- | --- |
| `de.robv.android.xposed:api:82` | `io.github.libxposed:api:102` |
| `IXposedHookLoadPackage` 接口 | 继承 `XposedModule` 抽象类 |
| `<meta-data xposedmodule / xposedscope / xposedminversion>` | `META-INF/xposed/{java_init,scope,module.prop}.list` |
| `XC_MethodHook.before/after(params)` | OkHttp 链式 `intercept { chain -> chain.proceed() }` |
| `XposedHelpers.findAndHookMethod` | `module.hookAll(class, name, params...)` |
| `XSharedPreferences` | `module.getRemotePreferences()` / `openRemoteFile()` |
| Resource hook ✅ | Resource hook ❌(2024 后移除) |

## 改作用域

编辑 `app/src/main/resources/META-INF/xposed/scope.list`,一行一个包名:

```
com.ss.android.ugc.aweme
com.tencent.mm
# 全部应用
# *
```

文件版作用域比 manifest `<meta-data>` 灵活,不需重打包就能快速调整(其实也得重打,因为资源是打进 APK 的)。

## 加自己的 Hook

一个真实例子(hook 抖音某个方法):

```kotlin
// src/main/java/.../hook/AntiAdHook.kt
class AntiAdHook(private val module: XposedModule) {
    fun apply() {
        // 拿到目标类
        val cls = Class.forName("com.ss.android.ugc.aweme.feed.ui.FeedFragment", true, module.appClassLoader)
        val method = cls.getDeclaredMethod("onResume")

        module.hook(method)
            .setPriority(PRIORITY_HIGHEST)
            .setExceptionMode(ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                Log.d("AntiAd", "FeedFragment.onResume called")
                null
            }
    }
}
```

在 `FuckTheVideoModule.kt` 里调用:

```kotlin
override fun onPackageReady(param: PackageReadyParam) {
    if (param.packageName == "com.ss.android.ugc.aweme") {
        AntiAdHook(this).apply()
    }
}
```

## Hook API 速查

```kotlin
// OkHttp chain 风格
chain.thisObject     // 被 hook 的对象
chain.args           // 参数数组(可写)
chain.proceed()      // 调用原方法,默认参数
chain.proceed(newArgs)   // 用新参数
chain.proceedWith(newThis)              // 新 this + 老参数
chain.proceedWith(newThis, newArgs)     // 新 this + 新参数
chain.getArg<Int>(0)         // 类型安全取值
chain.getResult()    // 调用结果
chain.processName    // 进程名(作用域过滤好用)
chain.thisClass      // 被 hook 的类
```

```kotlin
// Hooker 配置
module.hook(method)
    .setPriority(PRIORITY_LOWEST)            // 链式优先级
    .setExceptionMode(ExceptionMode.PROTECTIVE)
    .intercept { chain -> /* ... */ null }

module.hookAll(class, methodName, paramType1, paramType2)   // 批量 hook 同签名
module.deoptimize(method)               // 关键:System method inline 时需 deopt
module.getInvoker(method).setType(Invoker.Type.ORIGIN).invoke(...)   // 绕开 hooks 调用原方法

// 远程资源
module.getRemotePreferences("settings_name")
module.openRemoteFile("data.bin").use { ... }
```

参考:[libxposed/api javadoc](https://libxposed.github.io/api/)、[wiki](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)。

## 关于 Vector 兼容

**Vector 同时支持老 Xposed API 和 libxposed API**。如果你想共用老 API(比如要复用第三方老模块的资源或 API 习惯),保留旧依赖,加两个 entry:

- `META-INF/xposed/java_init.list` 里加:
  ```
  io.github.lsposed.fuckthevideo.FuckTheVideoModule
  com.example.legacy.LegacyModule
  ```

老 API 在 Vector 里通过一个 `LegacyFrameworkDelegate` 桥接,要加 `compileOnly "de.robv.android.xposed:api:82"` + 私有 `api.xposed.info` 仓库。

## CI/CD

`.github/workflows/release.yml` 已配。推 `v*` tag 自动构建 + 创建 Release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

几分钟后 Releases 页面下载 APK。详见 workflow 注释。

### 签名

`app/build.gradle.kts` 里 release 默认用 SDK debug keystore(能装能跑,不能上架)。
换正式签名:

1. `keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias fuckthevideo`
2. Base64: `base64 -w0 release.jks > release.jks.b64`
3. GitHub 仓库 **Settings → Secrets** 加 4 个 secrets: `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`
4. `app/build.gradle.kts` 里加 `signingConfigs.create("release") { ... }`,把 `signingConfig = signingConfigs.getByName("debug")` 改成 `getByName("release")`
5. workflow 加一步 `echo "$KEYSTORE_BASE64" | base64 -d > release.jks` 并设置 env

## License

MIT
