package io.github.lsposed.fuckthevideo

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lsposed.fuckthevideo.hook.DemoHook

/**
 * Vector / libxposed 模块入口。
 *
 * 入口发现规则见 [java_init.list]。
 * 框架流程:
 *   1. attachFramework(XposedInterface)
 *   2. onModuleLoaded():本进程被加载时
 *   3. onPackageLoaded():每个包加载时 (Q+)
 *   4. onPackageReady():ClassLoader 可用,适合 hook
 *
 * ⚠️ libxposed:101.0.0 是 Java 接口,在 Kotlin 里:
 *   - getter 自动转 property:`param.processName` 等价于 `param.getProcessName()`
 *   - SAM 接口(Kotlin lambda → Java SAM):`intercept { chain -> ... }`
 *
 * 编译时只有 sources.jar(无 classes.jar),运行时由 Vector 框架注入实现。
 */
class FuckTheVideoModule : XposedModule() {

    companion object {
        private const val TAG = "FuckTheVideo"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // frameworkName / frameworkVersionCode / apiVersion 是 XposedInterface
        // 上 getter 转 Kotlin property
        Log.i(
            TAG,
            "onModuleLoaded: pid=${android.os.Process.myPid()} " +
                "process=${param.processName} " +
                "systemServer=${param.isSystemServer} " +
                "framework=$frameworkName " +
                "fwVersion=$frameworkVersion " +
                "fwCode=v$frameworkVersionCode " +
                "api=$apiVersion"
        )
    }

    // @RequiresApi(Q) 来自父接口 XposedModuleInterface 的 @RequiresApi(Q),
    // Kotlin override 自动继承,无需重复声明
    override fun onPackageLoaded(param: PackageLoadedParam) {
        Log.d(TAG, "onPackageLoaded: ${param.packageName} (first=${param.isFirstPackage})")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.isFirstPackage) {
            Log.i(TAG, "onPackageReady (first): ${param.packageName}")
            DemoHook(this).apply()
        }
    }
}
