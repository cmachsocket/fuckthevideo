package io.github.lsposed.fuckthevideo.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 业务 hook:从 App 主页底部 Tab 里隐藏指定节点。
 *
 * ## 性能问题
 *
 * 老 hook(9dd2b23 MainHook.java → 上一版 Hook.kt)每次 onResume 都全树 DFS,
 * 京东 decorView 1500+ 节点,淘宝 300+,扫一遍 5-15ms,体感卡顿。
 *
 * ## 改进要点
 *
 * 1. **每个 Activity 实例只扫一次**
 *    用 [WeakHashMap] 记录"这个 Activity 已经被 hide 过了",后续 onResume
 *    直接 return。弱引用,Activity 死了 GC 自动回收,不需要手动清理。
 *
 * 2. **post 到主线程队列末,不在 hook 同帧抢 layout 时间**
 *    hook 回调立即返回(0 cost),真正扫描放到主线程下个消息。
 *
 * 3. **bottombar 局部扫描 — 最后 N 个 children 直接检查**
 *    拼多多 / 淘宝 / 京东等主流 App,bottombar 几乎一定位于 decorView
 *    最后 1-3 个 FrameLayout 里。我们先扫这 [BOTTOM_DEPTH] 个,命中即收,
 *    跳过整树 DFS。落空时才 fallback 到 DFS。复杂度从 O(n) 降到 O(depth)。
 *
 * 4. **三种 hide 策略可选,REMOVE 默认**
 *    [HideStrategy.GONE] 仅折叠占位 / [HideStrategy.REMOVE] 父层 removeView
 *    + requestLayout / [HideStrategy.ZERO_SIZE] 把尺寸清 0。
 *
 * 这样 Tap TheVideo 进入主页时,实际扫的节点量 < 100,体感卡顿消失。
 */
class HideBottomTabsHook(
    private val module: XposedModule,
    private val specs: List<TabSpec>,
    private val packageName: String,
    private val strategy: HideStrategy = HideStrategy.REMOVE,
) {
    companion object {
        private const val TAG = "HideBottomTabs"
        /** setTag 用的 key,标记本轮已处理过的 view(防 fragment 重 inf 时重复处理) */
        private const val VIEW_TAG_PROCESSED = 0x7f0a0001
        /** 底部扫描深度 — decorView 最后几个 children */
        private const val BOTTOM_DEPTH = 4
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScanned = java.util.WeakHashMap<Activity, Boolean>()

    private val onResume: Method by lazy {
        Activity::class.java.getDeclaredMethod("onResume")
    }

    fun apply() {
        module.hook(onResume).intercept { chain ->
            val activity = chain.thisObject as? Activity
            chain.proceed()
            if (activity != null && activity.packageName == packageName) {
                scheduleScan(activity)
            }
            null
        }
    }

    private fun scheduleScan(activity: Activity) {
        synchronized(activityScanned) {
            if (activityScanned[activity] == true) return
            activityScanned[activity] = true
        }
        mainHandler.post { scanForActivity(activity) }
    }

    private fun scanForActivity(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.window?.decorView as? ViewGroup ?: return

        if (scanLocalBottom(root)) {
            Log.d(TAG, "[$packageName] bottom-local hit, done")
            return
        }
        // fallback:整树 DFS — 现在仅当本地未命中才执行
        Log.d(TAG, "[$packageName] bottom-local miss, fallback DFS")
        scanRecursive(root)
    }

    /**
     * 只看 decorView 最后 [BOTTOM_DEPTH] 个 children:
     * - 如果其中任意一个 spec 命中,hide 它,return true。
     * - 否则扫描完这 BOTTOM_DEPTH 个树(仍 DFS 但只 DFS 这几棵子树),看
     *   它们内部有没有命中 spec,return 最终命中与否。
     *
     * 复杂度严格 ≤ O(每个 bottom-children 树的节点数),
     * 主屏 bottombar 通常 < 200 节点,远比整个 decorView (1500+) 小。
     */
    private fun scanLocalBottom(root: ViewGroup): Boolean {
        val n = root.childCount
        if (n == 0) return false
        val from = maxOf(0, n - BOTTOM_DEPTH)
        var anyHit = false
        for (i in (n - 1) downTo from) {
            val child = root.getChildAt(i) ?: continue
            if (scanRecursiveReturningHit(child)) anyHit = true
        }
        return anyHit
    }

    /** 子树 DFS 一遍;如果有任何命中,return true;否则 false。不在此处打日志避免重复输出。 */
    private fun scanRecursiveReturningHit(view: View): Boolean {
        for (spec in specs) {
            if (spec.matches(view)) {
                applyHide(view)
                return true
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (scanRecursiveReturningHit(view.getChildAt(i))) return true
            }
        }
        return false
    }

    /** DFS 一遍,见命中就 hid,继续扫完所有,适合 fallback 路径 */
    private fun scanRecursive(view: View) {
        for (spec in specs) {
            if (spec.matches(view)) {
                applyHide(view)
                // 命中后不 return — 同一 spec 可能在多个兄弟节点上(罕见),继续
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                scanRecursive(view.getChildAt(i))
            }
        }
    }

    private fun applyHide(view: View) {
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
        } else {
            view.visibility = View.GONE
        }
    }

    private fun zeroOutSize(view: View) {
        val lp = view.layoutParams ?: ViewGroup.LayoutParams(0, 0)
        lp.width = 0
        lp.height = 0
        view.layoutParams = lp
        view.visibility = View.GONE
        view.requestLayout()
    }
}
