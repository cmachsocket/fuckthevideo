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
            doApply(chain.thisObject as? Activity)
            null
        }
    }

    private fun doApply(activity: Activity?) {
        if (activity == null || activity.packageName != packageName) return
        val root = scanRootResourceId
            ?.let { findViewByResourceName(activity, it) }
            ?: activity.window?.decorView
            ?: return
        if (scanRootResourceId != null) {
            // 锁入口:从指定 resource-id 直接 DFS,跳过 decorView 末 N children 的启发式
            scan(root)
            root.post { scan(root) }
        } else {
            // 默认:走 decorView 末 [BOTTOM_SCAN_DEPTH] 个 children 的启发式
            scanBottomArea(root)
            root.post { scanBottomArea(root) }
        }
    }

    private fun findViewByResourceName(activity: Activity, name: String): View? {
        val resId = activity.resources.getIdentifier(name, "id", packageName)
        return if (resId != 0) activity.findViewById(resId) else null
    }

    /**
     * 只扫描 rootView 的最后 [BOTTOM_SCAN_DEPTH] 个 children,
     * 避免对整个 decorView 树做 DFS — 京东/淘宝的 decorView 动辄
     * 上千节点,扫一遍体感明显卡顿。底部 tab 一般在最末 1-3 个 children。
     */
    private fun scanBottomArea(root: View) {
        val parent = root as? ViewGroup ?: return
        val n = parent.childCount
        if (n == 0) return
        val from = maxOf(0, n - BOTTOM_SCAN_DEPTH)
        for (i in (n - 1) downTo from) {
            scan(parent.getChildAt(i))
        }
    }

    private fun scan(view: View) {
        // 先检查 view 自身是否命中 spec
        if (specs.any { it.matches(view) }) {
            applyHide(view)
            return
        }
        // ViewGroup 还要递归(京东 BY_DESC 的目标在 inner,BY_PARENT 在 outer 兄弟)
        if (view is ViewGroup) {
            // 没命中自身,继续 children — 但不重复扫这个 group(已经检过自己)
            for (i in 0 until view.childCount) {
                scan(view.getChildAt(i))
            }
        }
    }

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
