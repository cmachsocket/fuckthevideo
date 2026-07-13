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

        private data class Target(
            val pkg: String,
            val scanRootResourceId: String? = null,
            val strategy: HideStrategy = HideStrategy.REMOVE,
            val specs: List<TabSpec>,
        )

        /**
         * 业务白名单
         *
         * - 淘宝 / 拼多多:外层 FrameLayout 自带 content-desc,ByDesc 一招鲜,默认入口(decorView 末 4 children)
         * - 京东:外层 FrameLayout 无 desc,desc 在内层 View 上,改用 ByDescendantOf
         *         锁定 "逛" 子串(产品定位核心词,改名 "逛2元"、"逛3元" 都能命中)
         *         并锁入口 id/fl,跳过 decorView 启发式
         *
         * strategy 字段:
         * - 默认 REMOVE,现代 layout 干净掋掉 tab
         * - 淘宝用 TabHost(老式 widget),REMOVE 会导致 TabHost 内部 TabSpec.view 悬空、
         *   下次 focus 切换 tab 时 NPE 闪退。改用 ZERO_SIZE(只把 tab 框拉成 0x0,
         *   保持 view 在 parent children 里,TabHost 不知情)
         */
        private val TARGETS = listOf(
            Target(
                pkg = "com.taobao.taobao",
                strategy = HideStrategy.ZERO_SIZE,  // ← TabHost 安全,避免 REMOVE 崩宿主
                specs = listOf(TabSpec.ByDesc("视频")),
            ),
            Target(
                pkg = "com.jingdong.app.mall",
                scanRootResourceId = "fl",  // ← entry name (不带 prefix),getIdentifier 只认 entry
                specs = listOf(
                    TabSpec.ByDescendantOf(
                        parentResourceName = "fl",  // ← entry name 同上,getResourceEntryName 也只返 entry
                        descendantContentDesc = "逛",  // contains 匹配,抗改名
                    ),
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
            scanRootResourceId = target.scanRootResourceId,
            strategy = target.strategy,  // per-Target 覆盖全局默认
        ).apply()
    }
}
