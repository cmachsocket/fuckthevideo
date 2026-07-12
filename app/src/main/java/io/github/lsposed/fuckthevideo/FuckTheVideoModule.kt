package io.github.lsposed.fuckthevideo

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lsposed.fuckthevideo.hook.HideBottomTabsHook

/**
 * Vector / libxposed 模块入口。
 *
 * 入口发现规则见 [app/src/main/resources/META-INF/xposed/java_init.list]。
 *
 * 业务目标:在淘宝 / 京东 / 拼多多的主页底部 Tab 栏里隐藏"视频 / 逛 / 多多视频"。
 * 实际上 hook 在 Activity.onResume,里面根据 page 包名匹配,匹配上则
 * 遍历 rootView 找到 content-desc = 视频 / 逛 / 多多视频 的 Tab 并 GONE。
 */
class FuckTheVideoModule : XposedModule() {

    companion object {
        private const val TAG = "FuckTheVideo"
        private const val SELF_PKG = "io.github.lsposed.fuckthevideo"

        private data class Target(val pkg: String, val tabs: Set<String>)

        /** 业务白名单 — 翻译自 9dd2b23 initial commit 的 MainHook.java */
        private val TARGETS = listOf(
            Target(pkg = "com.taobao.taobao",      tabs = setOf("视频")),
            Target(pkg = "com.jingdong.app.mall",  tabs = setOf("逛")),
            Target(pkg = "com.xunmeng.pinduoduo",  tabs = setOf("多多视频")),
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // 仅主 package 一次,后续 hook 在 Activity.onResume 持续触发
        if (!param.isFirstPackage) return
        // 不 hook 自己
        if (param.packageName == SELF_PKG) return

        val target = TARGETS.firstOrNull { it.pkg == param.packageName }
        if (target == null) {
            Log.d(TAG, "ignore ${param.packageName} (not in scope)")
            return
        }

        Log.i(TAG, "hooking ${param.packageName}, target descs=${target.tabs}")
        HideBottomTabsHook(
            module = this,
            targetDescriptions = target.tabs,
            packageName = target.pkg,
        ).apply()
    }
}
