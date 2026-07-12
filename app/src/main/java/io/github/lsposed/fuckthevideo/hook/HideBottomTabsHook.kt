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
 * 侦探模式:撤销 bottom-local 优化,退回全树 DFS。
 *
 * 性能问题先放一边 — 命中错的位置更重要。每命中一个 candidate,
 * 打 [TAG]+view.class+desc+bounds+parent 信息到 logcat。
 *
 * 工作流:
 *   1. 装模块进手机
 *   2. 启动京东 / 淘宝,等底部 tab 显示出来
 *   3. `adb logcat -s HideBottomTabs` 看候选
 *   4. 把 logcat + UI dump 给我,我精校 spec
 *
 * 注意 onResume 阶段别反复 DFS 拖慢 — 用 [WeakHashMap] 每个 Activity
 * 只扫一次,避免拖慢启动时间。
 */
class HideBottomTabsHook(
    private val module: XposedModule,
    private val specs: List<TabSpec>,
    private val packageName: String,
    private val strategy: HideStrategy = HideStrategy.REMOVE,
) {
    companion object {
        private const val TAG = "HideBottomTabs"
        /** setTag key,防止重复挂同一 view */
        private const val VIEW_TAG_PROCESSED = 0x7f0a0001
        /** Log.d verbosity flag — false 时只打命中前的候选,warning 才出 */
        const val DEBUG_CANDIDATES = true
        /** 每个 Activity 实例只扫一次:onResume 反复触发不算 */
        const val MAX_SCANS_PER_PROCESS = 5
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScanned = java.util.WeakHashMap<Activity, Boolean>()
    private val processScanCount = java.util.concurrent.atomic.AtomicInteger(0)

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
        // 没到 quota 才 post
        if (processScanCount.incrementAndGet() > MAX_SCANS_PER_PROCESS) {
            Log.d(TAG, "[$packageName] process-wide scan quota reached, skip")
            return
        }
        mainHandler.post { scanForActivity(activity) }
    }

    private fun scanForActivity(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.window?.decorView as? ViewGroup ?: run {
            Log.w(TAG, "[$packageName] no decorView")
            return
        }
        Log.i(TAG, "[$packageName] === scan #${processScanCount.get()} begin ===")
        scanRecursive(root, depth = 0)
        Log.i(TAG, "[$packageName] === scan #${processScanCount.get()} end ===")
    }

    /**
     * 全树 DFS,每到一处就把 view 的关键属性打印出来。
     * 即使没有 spec 命中,也按"candidates-inspected"输出节选,方便排查。
     */
    private fun scanRecursive(view: View, depth: Int) {
        if (view is ViewGroup && view.id != View.NO_ID) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }
                .getOrNull()
            // 每命中一次 id-bearing ViewGroup,无论是否 spec 命中,都 log
            if (DEBUG_CANDIDATES && shouldLogClass(view.javaClass)) {
                Log.d(
                    TAG,
                    "[$packageName]   ${
                        "  ".repeat(depth.coerceAtMost(8))
                    }${view.javaClass.simpleName}#${name} " +
                        "bounds=${formatBounds(view)} " +
                        "desc='${view.contentDescription}' " +
                        "clickable=${view.isClickable} " +
                        "parentIdx=${(view.parent as? ViewGroup)?.let { it.indexOfChild(view) }}"
                )
            }
        }

        for (spec in specs) {
            if (spec.matches(view)) {
                Log.w(
                    TAG,
                    "[$packageName] ★ HIT ★ class=${view.javaClass.simpleName} " +
                        "spec=$spec bounds=${formatBounds(view)} desc='${view.contentDescription}'"
                )
                applyHide(view)
                // 命中后继续扫 — 同 spec 可能在其他 sibling 上
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                scanRecursive(view.getChildAt(i), depth + 1)
            }
        }
    }

    private fun shouldLogClass(klass: Class<*>): Boolean {
        val simple = klass.simpleName
        return simple.contains("FrameLayout") ||
            simple.contains("LinearLayout") ||
            simple.contains("RecyclerView") ||
            simple.contains("ConstraintLayout") ||
            simple.contains("RelativeLayout")
    }

    private fun formatBounds(view: View): String {
        val r = IntArray(2)
        view.getLocationInWindow(r)
        // android.view.View 标准的 getLocationInWindow 输出 (x,y),但我们想看全 bounds
        return "[${view.left},${view.top} ${view.right},${view.bottom}]"
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
