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

    /**
     * Hook 时机改用 `View.onAttachedToWindow` 而不是 `Activity.onResume`。
     *
     * 为什么不用 onResume:
     * 京东 App 升级后加了 libjdhook.so(基于 shadowhook 1.1.1),在 native 层
     * inline hook 了 Activity.onResume 的 vtable entry — LSPosed 的 method
     * handle hook 在 native vtable 层面被绕过,扫描完全不被调用。
     * 淘宝也可能用了同类机制(shadowhook / YAHFA / Epic)。
     *
     * 为什么 onAttachedToWindow work:
     * - 这是 View 级别的 hook,在 view 被加入 window 时触发
     * - 京东淘宝的 native hook 框架一般不动这里(它们的目标是 Activity 生命周期 / sendSignal)
     * - 底部 tab 的 root 必然要 onAttachedToWindow 一次才能显示
     * - SKIP TOP 过滤(top < BOTTOM_FRACTION 屏幕高度)能快速剪枝,
     *   顶部 banner 的 NavigationButton 不会触发 scan root 的逻辑
     *
     * 频率问题:每个 view add 都会触发,但只对京东/淘宝/拼多多生效(scope 限制),
     * SKIP TOP 过滤后绝大多数 view 立即 return false,实际开销可忽略。
     */
    private val onAttachedToWindow: Method by lazy {
        View::class.java.getDeclaredMethod("onAttachedToWindow")
    }

    /**
     * 装一个 [View.onAttachedToWindow] hook。
     *
     * hook 内部任何异常都不能 kill 宿主 App — 吞了,只 log。
     */
    fun apply() {
        try {
            Log.i(TAG, "[$packageName] apply() installing hook on ${onAttachedToWindow}")
            module.hook(onAttachedToWindow).intercept { chain ->
                val obj = chain.thisObject
                // 看 hook 触发没,以及 thisObject 是啥
                Log.d(TAG, "[$packageName] onAttachedToWindow fired, thisObject=${obj?.javaClass?.name}")
                try {
                    doApplyAttached(obj as? View)
                } catch (t: Throwable) {
                    Log.e(TAG, "[$packageName] onAttachedToWindow hook crashed", t)
                }
                null
            }
            Log.i(TAG, "[$packageName] apply() hook installed")
        } catch (t: Throwable) {
            Log.e(TAG, "[$packageName] module.hook() crashed", t)
        }
    }

    /**
     * onAttachedToWindow 入口:view 已经在 window tree 里。
     *
     * 因为这个 hook 频繁触发(每个 view add 都一次),不能像 doApply 那样拿
     * Activity + screenHeight 然后扫整棵树。换策略:每次触发只判断**自己**
     * 是不是要藏的目标,藏了就 return 不递归。
     *
     * 边界情况:
     * - 命中后 view.setTag(VIEW_TAG_PROCESSED) 防 fragment 重建时重复打
     * - target 一定是某个具体节点(FrameLayout/View),不是 root
     */
    private fun doApplyAttached(view: View?) {
        if (view == null) return
        // 已处理过的 view 直接跳过 — 防止 fragment 重建时重复 hide
        if (view.getTag(VIEW_TAG_PROCESSED) == true) return

        // 关键:京东等 App 在 onAttachedToWindow 时 view 的 contentDescription 还没设,
        // 是 attach 后某个时间点才设置(异步 inflate / 数据绑定)。dump 能看到是因为
        // dump 时已经过了几百 ms。
        // 解法:post 到下一帧再检查,那时 desc/id 等属性都已设好。
        view.post {
            // 可能 view 在 post 期间被 remove 了
            if (view.parent == null) return@post
            if (view.getTag(VIEW_TAG_PROCESSED) == true) return@post
            checkAndHide(view)
        }
    }

    private fun checkAndHide(view: View) {
        // Debug: 报告任何含'逛'的 view 或 parent.id=fl
        val desc = view.contentDescription?.toString() ?: ""
        val parentIdName = if (view.parent is View) {
            runCatching { (view.parent as View).resources.getResourceEntryName((view.parent as View).id) }.getOrNull()
        } else null
        if (desc.contains("逛") || parentIdName == scanRootResourceId) {
            Log.d(TAG, "[$packageName] candidate view: cls=${view.javaClass.simpleName} desc=$desc bounds=[${view.left},${view.top}][${view.right},${view.bottom}] parentId=$parentIdName")
        }

        val matchedSpec = specs.firstOrNull { it.matches(view) } ?: return
        // SKIP TOP 过滤:top 太靠上说明是 banner/入口位,不是底部 tab
        val screenHeight = screenHeightCache ?: run {
            // 从 view 自身拿到所属 window 的 display height
            val h = runCatching {
                val wm = view.context.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as? android.view.WindowManager
                wm?.currentWindowMetrics?.bounds?.height()
                    ?: view.resources.displayMetrics.heightPixels
            }.getOrNull() ?: return
            screenHeightCache = h
            h
        }
        if (view.top < screenHeight * BOTTOM_FRACTION) {
            // 只对真正命中过底部 tab 的 view 打 SKIP TOP log,顶部 banner 一票否决,避免日志洪水
            Log.d(TAG, "[$packageName] ★ SKIP TOP ★ ${view.javaClass.simpleName} bounds=[${view.left},${view.top}][${view.right},${view.bottom}] desc=${view.contentDescription}")
            return
        }
        Log.d(TAG, "[$packageName] ★ HIT ★ ${view.javaClass.simpleName} bounds=[${view.left},${view.top}][${view.right},${view.bottom}] desc=${view.contentDescription} spec=${matchedSpec.javaClass.simpleName}")
        applyHide(view)
    }

    @Volatile
    private var screenHeightCache: Int? = null

    private fun doApply(activity: Activity?) {
        if (activity == null || activity.packageName != packageName) return
        val screenHeight = activity.resources.displayMetrics.heightPixels
        if (scanRootResourceId != null) {
            val roots = findAllViewsByResourceName(activity, scanRootResourceId)
            if (roots.isEmpty()) {
                // id 没找到 — 这活动不是带 id/fl 的页面,跳过
                Log.d(TAG, "[$packageName] scanRootResourceId=$scanRootResourceId not found in ${activity.javaClass.simpleName}, skip")
                return
            }
            // 多个同名 id root(京东 App 升级后顶部 banner NavigationGroup 也叫 id/fl,
            // 跟底部 tab LinearLayout 同名),按 top 倒序扫:底部那个先命中。
            // SKIP TOP 过滤会在顶部 NavigationButton 上 false,然后 fallthrough 到
            // 下一个 sibling;倒序保证底部优先 — 找到就 break,省一次多余 scan。
            val sortedRoots = roots.sortedByDescending { it.top }
            Log.d(TAG, "[$packageName] found ${roots.size} id=$scanRootResourceId roots: " +
                sortedRoots.joinToString { "${it.javaClass.simpleName}@${formatBounds(it)}" })
            var hid = false
            for (root in sortedRoots) {
                if (scan(root, screenHeight)) {
                    hid = true
                    break
                }
            }
            // 第一轮没藏到才 post 重扫 — 处理 fragment 异步 inflate。
            // 藏到了就不用再扫,省一半 + 避免对 JD NavigationGroup 多一次 layout pass 触发 NPE。
            if (!hid) {
                val firstRoot = sortedRoots.first()
                firstRoot.post {
                    for (root in sortedRoots) {
                        if (scan(root, screenHeight)) break
                    }
                }
            }
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
     * 找所有 entry name 相同的 view。
     *
     * 为什么不用 `activity.findViewById(resId)`:京东 App 升级后,顶部 banner 的
     * NavigationGroup 现在也叫 `id/fl`,跟底部 tab 的 LinearLayout id 重名。
     * `findViewById` 返第一个 inflate 进去的(顶部 banner),scan 在顶部跑就永远
     * 摸不到底部 tab — 因为顶部"逛"被 SKIP TOP 过滤掉,顶部 NavigationGroup
     * 又不包含底部 tab(它们是两棵独立的 view tree 子树)。
     *
     * 这里 DFS 整棵 decorView,把所有 id=fl 的 view 都收集起来,doApply 按 top
     * 倒序后挨个 scan。decorView 一般上千节点,但 id=fl 节点少(京东目前 2 个),
     * DFS 总开销远小于 scan 一棵无关子树。
     */
    private fun findAllViewsByResourceName(activity: Activity, entryName: String): List<View> {
        val resId = activity.resources.getIdentifier(entryName, "id", packageName)
        if (resId == 0) return emptyList()
        val root = activity.window?.decorView ?: return emptyList()
        val out = mutableListOf<View>()
        collectById(root, resId, out)
        return out
    }

    private fun collectById(view: View, targetId: Int, out: MutableList<View>) {
        if (view.id == targetId) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                collectById(child, targetId, out)
            }
        }
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
