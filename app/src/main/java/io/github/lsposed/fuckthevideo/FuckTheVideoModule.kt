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
 * **当前版 status:侦探模式**
 * 全树 DFS + verbose log,等用户 dump UI tree 后精校 spec。
 *
 * - 淘宝:用 `content-desc="视频"` 命中错位置(命中了一个巧合元素),
 *   真正视频 Tab 不在我们 DFS 路径上(bottom-local 假设错)。
 *   改成全树搜,把每一个候选的 bounds+desc+id 打日志。
 *
 * - 京东:`com.jingdong.app.mall:id/fl` + childIndex=1 没反应,
 *   说明 layout 实际位置不是 childIndex=1,或 parent 资源名错了。
 *   全树 DFS 把所有 `com.jingdong.app.mall:id/fl` 的 childIndex 全打出来。
 */
class FuckTheVideoModule : XposedModule() {

    companion object {
        private const val TAG = "FuckTheVideo"
        private const val SELF_PKG = "io.github.lsposed.fuckthevideo"

        private data class Target(val pkg: String, val specs: List<TabSpec>)

        /**
         * 占位 spec — 真实的命中位置以 logcat 为准。
         * 暂时保留 `ByDesc` + `ByParent` 双兜底,期望它能至少命中一个,
         * 给 log 输出调试。如果两个都不命中,说明京东 / 淘宝的 bottombar 不在
         * 我们当前能找到的路径里,我们再改 spec 类型(比如 ByBounds)。
         */
        private val TARGETS = listOf(
            Target(
                pkg = "com.taobao.taobao",
                specs = listOf(TabSpec.ByDesc("视频")),
            ),
            Target(
                pkg = "com.jingdong.app.mall",
                specs = listOf(
                    TabSpec.ByDesc("视频"),
                    TabSpec.ByParent(
                        parentResourceName = "com.jingdong.app.mall:id/fl",
                        childIndex = 1,
                    ),
                ),
            ),
            Target(
                pkg = "com.xunmeng.pinduoduo",
                specs = listOf(TabSpec.ByDesc("多多视频")),
            ),
        )

        /** 全局 hide 策略。REMOVE 最彻底 */
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
            "hooking ${param.packageName} specs=${target.specs} strategy=$GLOBAL_STRATEGY"
        )
        HideBottomTabsHook(
            module = this,
            specs = target.specs,
            packageName = target.pkg,
            strategy = GLOBAL_STRATEGY,
        ).apply()
    }
}
