package io.github.lsposed.fuckthevideo

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.fuckthevideo.hook.DemoHook

/**
 * Vector / libxposed 模块入口。
 *
 * 入口发现规则见 [META-INF/xposed/java_init.list]。
 * Vector 框架会:
 *   1. 实例化本类
 *   2. 调用 onModuleLoaded():每个进程(作用域名下 app 各一次)
 *   3. 调用 onPackageLoaded():API 30+,仅第一次拉包时
 *   4. 调用 onPackageReady():ClassLoader 已加载好,适合做 hook
 *
 * 整个类只创建一次,在每个作用域进程里复用。
 */
class FuckTheVideoModule : XposedModule() {

    companion object {
        private const val TAG = "FuckTheVideo"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // 进程加载时的初始化 —— 不要在这里 hook,ClassLoader 还没好
        Log.i(TAG, "onModuleLoaded: pid=${android.os.Process.myPid()} " +
                "process=${param.processName} " +
                "framework=$frameworkName v$frameworkVersionCode " +
                "api=$apiVersion")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // API 30+,在每个作用域包进程里调用一次
        Log.d(TAG, "onPackageLoaded: ${param.packageName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // ClassLoader 已就绪 —— 这是 hook 的主战场
        if (param.isFirstPackage) {
            // 仅在进程的主 package 第一次 ready 时 hook;其他包进程(如 system_server)按需
            Log.i(TAG, "onPackageReady (first): ${param.packageName}")
            DemoHook(this).apply()
        } else {
            Log.d(TAG, "onPackageReady: ${param.packageName}")
        }
    }
}
