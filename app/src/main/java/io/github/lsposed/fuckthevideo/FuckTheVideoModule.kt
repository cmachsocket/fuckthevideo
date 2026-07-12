package io.github.lsposed.fuckthevideo

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lsposed.fuckthevideo.hook.HideBottomTabsHook
import io.github.lsposed.fuckthevideo.hook.HideStrategy
import io.github.lsposed.fuckthevideo.hook.TabSpec

/**
 * Vector / libxposed 模块入口。
 *
 * 入口发现规则见 [app/src/main/resources/META-INF/xposed/java_init.list]。
 *
 * 业务目标:在淘宝 / 京东 / 拼多多的主页底部 Tab 栏里隐藏"视频" Tab。
 * 京东的 Tab 没有 contentDescription,只能按父容器 resource-id + child index 定位。
 *
 * 注意 setTag(0x7f0a0001) 用作"已处理"标记 — 这是个 shared hack,
 * 不能用 androidx.annotation.IdRes 因为没引入 androidx.annotation 依赖。
 */
class FuckTheVideoModule : XposedModule() {

    companion object {
        private const val TAG = "FuckTheVideo"
        private const val SELF_PKG = "io.github.lsposed.fuckthevideo"

        private data class Target(val pkg: String, val specs: List<TabSpec>)

        /**
         * 业务白名单 — 京东额外加了 ByParent 兜底(只靠 ByDesc 出现 GONE 不彻底问题)
         */
        private val TARGETS = listOf(
            Target(
                pkg = "com.taobao.taobao",
                specs = listOf(TabSpec.ByDesc("视频")),
            ),
            Target(
                pkg = "com.jingdong.app.mall",
                specs = listOf(
                    TabSpec.ByParent(
                        parentResourceName = "com.jingdong.app.mall:id/fl",
                        childIndex = 1,
                    ),
                    TabSpec.ByDesc("视频"),
                ),
            ),
            Target(
                pkg = "com.xunmeng.pinduoduo",
                specs = listOf(TabSpec.ByDesc("多多视频")),
            ),
        )

        /** 全局 hide 策略。REMOVE 最彻底,但 fragment 切回若重建,可能再显 */
        private val GLOBAL_STRATEGY = HideStrategy.REMOVE
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName == SELF_PKG) return

        val target = TARGETS.firstOrNull { it.pkg == param.packageName }
        if (target == null) {
            Log.d(TAG, "ignore ${param.packageName} (not in scope)")
            return
        }

        Log.i(
            TAG,
            "hooking ${param.packageName} " +
                "specs=${target.specs} strategy=$GLOBAL_STRATEGY"
        )
        HideBottomTabsHook(
            module = this,
            specs = target.specs,
            packageName = target.pkg,
            strategy = GLOBAL_STRATEGY,
        ).apply()
    }
}
