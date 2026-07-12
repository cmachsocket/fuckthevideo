package io.github.lsposed.fuckthevideo.hook

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 业务 hook:从三个 App 的主页底部 Tab 栏里隐藏目标 Tab。
 *
 * 来源:9dd2b23 提交的 MainHook.java,翻译/迁移到 libxposed + Kotlin。
 *
 * 原始实现(老 Xposed API):
 * ```
 * public class MainHook implements IXposedHookLoadPackage {
 *     // 在 taobao / jd / pdd hook Activity.onResume,after 里遍历
 *     // rootView 找到 content-description 匹配的 View,setVisibility(V.GONE)
 * }
 * ```
 *
 * 等价实现(OkHttp chain 风格):
 *   intercept { chain ->
 *       chain.proceed()
 *       traverseAndHide(activity.window.decorView)
 *   }
 */
class HideBottomTabsHook(
    private val module: XposedModule,
    private val targetDescriptions: Set<String>,
    private val packageName: String,
    /** 是否从父容器彻底移除。false = 仅 setVisibility(GONE),保留占位 */
    private val removeInstead: Boolean = false,
) {
    companion object {
        private const val TAG = "HideBottomTabs"
    }

    private val onResume: Method by lazy {
        Activity::class.java.getDeclaredMethod("onResume")
    }

    fun apply() {
        module.hook(onResume).intercept { chain ->
            // proceed 原方法先跑(让 Activity 真正进入 resumed)
            chain.proceed()
            // after 部分:遍历 rootView,隐藏目标 Tab
            val activity = chain.thisObject as? Activity ?: return@intercept null
            if (activity.packageName != packageName) return@intercept null
            val root = activity.window?.decorView ?: return@intercept null
            traverseAndHide(root)
            null
        }
    }

    /**
     * 递归遍历 View 树,找 content-desc 命中 targetDescriptions 的 View,
     * 按 [removeInstead] 选择 GONE 或 removeView。
     */
    private fun traverseAndHide(view: View) {
        if (view == null) return

        val desc = view.contentDescription
        if (desc != null && targetDescriptions.contains(desc.toString())) {
            Log.d(TAG, "[$packageName] matched: $desc")
            if (removeInstead) {
                removeFromParent(view)
            } else {
                view.visibility = View.GONE
                Log.d(TAG, "[$packageName] set GONE")
            }
            return
        }

        if (view is ViewGroup) {
            var i = 0
            while (i < view.childCount) {
                traverseAndHide(view.getChildAt(i))
                i++
            }
        }
    }

    private fun removeFromParent(view: View) {
        val parent = view.parent
        if (parent is ViewGroup) {
            parent.removeView(view)
            Log.d(TAG, "[$packageName] removed from parent")
        } else {
            Log.w(TAG, "[$packageName] no ViewGroup parent, cannot remove")
        }
    }
}
