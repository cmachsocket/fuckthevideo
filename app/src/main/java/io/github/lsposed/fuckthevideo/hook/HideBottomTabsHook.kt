package io.github.lsposed.fuckthevideo.hook

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 业务 hook:从 App 主页底部 Tab 里隐藏指定节点。
 *
 * 之前版本是 9dd2b23 提交的 MainHook.java(老 Xposed API),已翻译为 Kotlin + libxposed。
 *
 * 改善:
 * 1. 多个 spec 同时支持(京东没 desc,只能 ByParent 定位)
 * 2. 多种 strategy(GONE / REMOVE / ZERO_SIZE)— 默认 REMOVE
 * 3. **bottom-area 局部扫描** — 只看 decorView 最后几个 children,不再全树递归
 * 4. 推到下一帧再压一次,防止 fragment 重置
 * 5. onCreate + onResume 双 hook,早 hook 早 layout,后 hook 兜底
 */
class HideBottomTabsHook(
    private val module: XposedModule,
    private val specs: List<TabSpec>,
    private val packageName: String,
    private val scanRootResourceId: String? = null,
    private val strategy: HideStrategy = HideStrategy.REMOVE,
) {
    companion object {
        private const val TAG = "HideBottomTabs"
        /** 底部扫描深度 — 装饰视图最后几个 children 几乎一定包含 bottombar */
        private const val BOTTOM_SCAN_DEPTH = 4
        /**
         * 底部区域阈值(屏幕高度的分数)— 只有 top >= screenHeight * BOTTOM_FRACTION 才考虑藏。
         * 0.67 = 只在屏幕下 1/3 区域藏 tab,顶层导航 / banner 就算 desc 命中也不会被误删。
         * 调高(0.7 / 0.75)更严格,调低(0.5)更宽松。
         */
        private const val BOTTOM_FRACTION = 0.67
        /** setTag 的 key,标记本轮已处理过的 view,避免 fragment 重建时被反复打 */
        private const val VIEW_TAG_PROCESSED = 0x7f0a0001
    }

    private val onResume: Method by lazy {
        Activity::class.java.getDeclaredMethod("onResume")
    }

    /**
     * 只装一个 [Activity.onResume] hook。
     * 老 Xposed 时代 (9dd2b23 MainHook.java) 也是这个时机。
     * onCreate 阶段 view tree 还没 layout,不该 scan。onResume 之后
     * 一定 laid out,所以这是唯一靠谱的入口。
     */
    fun apply() {
        module.hook(onResume).intercept { chain ->
            chain.proceed()
            // hook 内部任何异常都不能 kill 宿主 App — 吞了,只 log
            try {
                doApply(chain.thisObject as? Activity)
            } catch (t: Throwable) {
                Log.e(TAG, "[$packageName] doApply crashed", t)
            }
            null
        }
    }

    private fun doApply(activity: Activity?) {
        if (activity == null || activity.packageName != packageName) return
        val screenHeight = activity.resources.displayMetrics.heightPixels
        if (scanRootResourceId != null) {
            val root = findViewByResourceName(activity, scanRootResourceId)
            if (root == null) {
                // id 没找到 — 这活动不是带 id/fl 的页面,跳过
                Log.d(TAG, "[$packageName] scanRootResourceId=$scanRootResourceId not found in ${activity.javaClass.simpleName}, skip")
                return
            }
            Log.d(TAG, "[$packageName] entry locked to ${root.javaClass.simpleName} id=$scanRootResourceId")
            val hid = scan(root, screenHeight)
            // 第一轮没藏到才 post 重扫 — 处理 fragment 异步 inflate。
            // 藏到了就不用再扫,省一半 + 避免对 JD NavigationGroup 多一次 layout pass 触发 NPE。
            if (!hid) root.post { scan(root, screenHeight) }
        } else {
            val root = activity.window?.decorView ?: return
            val hid = scanBottomArea(root, screenHeight)
            if (!hid) root.post { scanBottomArea(root, screenHeight) }
        }
    }

    private fun findViewByResourceName(activity: Activity, entryName: String): View? {
        val resId = activity.resources.getIdentifier(entryName, "id", packageName)
        return if (resId != 0) activity.findViewById(resId) else null
    }

    /**
     * 只扫描 rootView 的最后 [BOTTOM_SCAN_DEPTH] 个 children,
     * 避免对整个 decorView 树做 DFS — 京东/淘宝的 decorView 动辄
     * 上千节点,扫一遍体感明显卡顿。底部 tab 一般在最末 1-3 个 children。
     */
    private fun scanBottomArea(root: View, screenHeight: Int): Boolean {
        val parent = root as? ViewGroup ?: return false
        val n = parent.childCount
        if (n == 0) return false
        val from = maxOf(0, n - BOTTOM_SCAN_DEPTH)
        for (i in (n - 1) downTo from) {
            val child = parent.getChildAt(i) ?: continue
            if (scan(child, screenHeight)) return true
        }
        return false
    }

    /**
     * DFS 扫描整个子树。
     *
     * 关键点:
     * 1. 用 `while (i < view.childCount)` 而不是 `for (i in 0 until view.childCount)`。
     *    后者会把 childCount 缓存到寄存器,applyHide(REMOVE)改了 childCount 后
     *    `view.getChildAt(oldCount-1)` 会返回 null → scan(null) → NPE → JD NavigationGroup 卡死。
     * 2. 用 BOTTOM_FRACTION 过滤 — 京东顶部 NavigationButton(类 banner、推荐入口)
     *    的内部 desc 也含"逛",y=53 在屏幕顶部,不能藏。只藏底部 1/3 才安全,
     *    否则用户在非首页(逛发现页、逛店铺页)正常浏览的 view 会被误删。
     */
    private fun scan(view: View, screenHeight: Int): Boolean {
        // 先检查 view 自身是否命中 spec
        val matchedSpec = specs.firstOrNull { it.matches(view) }
        if (matchedSpec != null) {
            // 底部区域过滤:必须在屏幕下 1/3。京东顶部 NavigationButton(banner、入口位)
            // 内部 desc 也含 "逛"/"消息" 等关键字,会被 desc-based spec 误命中 →
            // bounds 过滤是最后一道防线。
            if (view.top < screenHeight * BOTTOM_FRACTION) {
                Log.d(TAG, "[$packageName] ★ SKIP TOP ★ ${view.javaClass.simpleName} bounds=${formatBounds(view)} desc=${view.contentDescription} (top=${view.top} < ${screenHeight * BOTTOM_FRACTION})")
                return false
            }
            Log.d(TAG, "[$packageName] ★ HIT ★ ${view.javaClass.simpleName} bounds=${formatBounds(view)} desc=${view.contentDescription} spec=${matchedSpec.javaClass.simpleName}")
            applyHide(view)
            return true
        }
        // ViewGroup 还要递归(京东 BYDescendantOf 目标在 inner,BY_PARENT 在 outer 兄弟)
        if (view is ViewGroup) {
            var i = 0
            while (i < view.childCount) {
                val child = view.getChildAt(i) ?: break
                if (scan(child, screenHeight)) return true
                i++
            }
        }
        return false
    }

    private fun formatBounds(view: View): String =
        "[${view.left},${view.top}][${view.right},${view.bottom}]"

    private fun applyHide(view: View) {
        // 同一个 view 在一次扫描里重复命中,只处理一次
        if (view.getTag(VIEW_TAG_PROCESSED) == true) return
        view.setTag(VIEW_TAG_PROCESSED, true)

        Log.d(TAG, "[$packageName] hide ${view.javaClass.simpleName} strategy=$strategy")
        when (strategy) {
            HideStrategy.GONE -> view.visibility = View.GONE
            HideStrategy.REMOVE -> removeFromParent(view)
            HideStrategy.ZERO_SIZE -> zeroOutSize(view)
        }
    }

    private fun removeFromParent(view: View) {
        val parent = view.parent as? ViewGroup
        if (parent != null) {
            parent.removeView(view)
            parent.requestLayout()
            Log.d(TAG, "[$packageName] REMOVE done, parent.requestLayout()")
        } else {
            Log.w(TAG, "[$packageName] no ViewGroup parent, fallback to GONE")
            view.visibility = View.GONE
        }
    }

    private fun zeroOutSize(view: View) {
        val lp = view.layoutParams
        if (lp != null) {
            lp.width = 0
            lp.height = 0
            view.layoutParams = lp
        } else {
            view.layoutParams = ViewGroup.LayoutParams(0, 0)
        }
        view.visibility = View.GONE
        view.requestLayout()
        Log.d(TAG, "[$packageName] ZERO_SIZE done")
    }

}
