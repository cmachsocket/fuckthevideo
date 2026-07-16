# FuckTheVideo

LSPosed / Vector module. Hide the "视频" tab in the bottom bar of
淘宝 (Taobao), 京东 (JD), and 拼多多 (Pinduoduo).

依赖: Vector (LSPosed).

## 用法

1. 安装 APK 后到 LSPosed / Vector 管理器里启用本模块
2. 勾选作用域: `com.taobao.taobao` / `com.jingdong.app.mall` / `com.xunmeng.pinduoduo`
3. 强制停止目标 app 后重新打开

> `adb install -r` 会重置模块的 enable / scope 状态, 装完要重新启用 + 重新加 scope。

## 工作原理 (v0.3.8)

三个 app 用了完全不同的 tab 容器, 所以每个 app 一个独立的 hook, 都基于
`param.classLoader` (从 `onPackageReady` 拿到) 加载 app 自己的类:

| App    | Hook 类                                                       | 策略       | 匹配字段                  |
|--------|--------------------------------------------------------------|------------|--------------------------|
| 淘宝   | `com.taobao.tao.navigation.TBFragmentTabHost`               | ZERO_SIZE  | DFS 找 TextView "视频"  |
| 京东   | `com.jingdong.common.unification.navigationbar.newbar.NavigationGroup` | REMOVE     | 反射 `label` 字段 = "逛"  |
| 拼多多 | `com.xunmeng.pinduoduo.ui_home_activity.widget.MainFrameContainerView` | REMOVE     | DFS 找 TextView "多多视频" |

为什么不用 `setContentDescription` 这种通用 hook: 见 [FuckTheVideoModule.kt](./app/src/main/main/java/io/github/lsposed/fuckthevideo/FuckTheVideoModule.kt) 头注释, 主要是因为:
- 京东底部 tab 是 native 渲染的 (TNViewGroup), 不在 Java 视图树里
- 拼多多底部 tab 在 `:titan` 副进程里, 主进程看不到
- 淘宝 NavigationTabIndicatorView 的 content-desc 在 XML 静态设置, 通用 setContentDescription 钩子在 layout 完成前 fire, 后续 defer-hide 因 top=0 误判为顶部区域被跳过

## 已验证

| 版本    | App    | 状态                          |
|---------|--------|------------------------------|
| v0.3.5  | 京东   | ✅ "逛" 隐藏, 5 tab → 4 tab  |
| v0.3.7  | 拼多多 | ✅ "多多视频" 隐藏, 5 → 4    |
| v0.3.8  | 淘宝   | ✅ "视频" ZERO_SIZE, logcat 见 HIT |

## 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& "C:\Users\cmach_socket\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat" assembleDebug
```

产物: `app/build/outputs/apk/debug/app-debug.apk`
