package io.github.lsposed.fuckthevideo

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.lsposed.fuckthevideo.hook.DemoHook

/**
 * LSPosed 模块入口。
 *
 * 任何实现 [IXposedHookLoadPackage] 的类,都会被 LSPosed 扫描并注册为 hook 入口。
 * 这里用 [DemoHook] 演示一个最常见的拦截点;实际项目里通常按目标拆成多个 Hook 类,
 * 在本类里根据包名分发给它们。
 */
class FuckTheVideoModule : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 可以在这里做包名路由
        when (lpparam.packageName) {
            // "com.target.app" -> TargetHook().handleLoadPackage(lpparam)
            else -> DemoHook().handleLoadPackage(lpparam)
        }
    }
}
