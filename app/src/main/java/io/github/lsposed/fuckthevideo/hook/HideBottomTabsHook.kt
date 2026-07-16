package io.github.lsposed.fuckthevideo.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Method

// Shared tag for marking a view as already-processed by the module.
// Used by both HideBottomTabsHook (in its scan paths) and
// FuckTheVideoModule (for app-specific tab cell hooks). Defined as
// a top-level constant so both files can reference it directly
// without going through a companion object.
const val VIEW_TAG_PROCESSED: Int = 0x7f0a0001

/**
 * Business hook: hide specified bottom tab nodes in target app main page.
 *
 * Four attach paths (all attempted; on real devices each may or may not
 * reach the target depending on app architecture — see LIMITATIONS):
 * 1. [attachTo] registers ActivityLifecycleCallbacks (needs Application
 *    via static field reflection, which usually bypasses LSPosed's
 *    reflection check). On every onActivityResumed, does a one-shot
 *    scan and attaches a long-lived layout listener.
 * 2. [attachViaOnResumeHook] hooks View.setContentDescription. Fires
 *    the moment any view in the host app has its contentDescription
 *    set. We DEFER the actual hide to a layout pass and only then
 *    check bottom-region position. This handles the case where the
 *    same contentDescription appears in both the top banner and the
 *    bottom tab (e.g. JD's "逛" entry): at hook time the view's bounds
 *    are [0,0][0,0]; after layout we can tell top from bottom.
 * 3. [runScheduledScan] (from FuckTheVideoModule) enumerates
 *    ActivityThread.mActivities and scans each activity's decorView
 *    plus WindowManagerGlobal.mViews. This catches tab views that are
 *    added DYNAMICALLY (not at inflation time) and so don't trigger
 *    the setContentDescription hook.
 * 4. [attachLayoutScan] hooks decorView.viewTreeObserver's
 *    OnGlobalLayoutListener for re-scan on every layout pass. Backup
 *    for tabs whose content-desc is set in XML (setContentDescription
 *    hook never fires for those).
 *
 * Why REMOVE (not GONE): JD's NavigationGroup positions children with
 * absolute LayoutParams — GONE doesn't reflow siblings, so the tab
 * area still renders a phantom.
 *
 * KNOWN LIMITATIONS (verified on OnePlus OP60EDL1, Android 16, JD 13+ /
 * PDD 7+ / Taobao latest as of 2026-07):
 * - JD bottom tab is native-rendered (TNViewGroup/TNImage/TNLottieView).
 *   The tab cells never enter the Java View tree, so setContentDescription
 *   never fires for them (XML static), and onGlobalLayout listener on the
 *   main process's decorView is not triggered by the native render
 *   pipeline. Bottom tab cannot be hidden via any Java view hook.
 * - PDD runs UI rendering in a `:titan` secondary process; the main
 *   process's decorView view tree is empty in the bottom region. Same
 *   outcome: no match, no hide.
 * - For top banner entries (where the same contentDescription appears
 *   at the top of the screen and is set dynamically), the
 *   setContentDescription hook fires correctly and we skip them via the
 *   bottom-region check. No false positives.
 *
 * The module is therefore effective ONLY when an app's bottom tab is
 * inflated as part of the main process's activity decorView and has its
 * contentDescription set dynamically (rare in modern apps). For the
 * three target apps, this condition is not met.
 *
 * All log calls go to file (host app's cache dir) AND best-effort
 * logcat. logd per-PID quota of 300 lines is filled by host apps' own
 * verbose output (JD prints "No package ID ff found for resource ID
 * 0xffffffff" hundreds of times per second).
 */
class HideBottomTabsHook(
    private val specs: List<TabSpec>,
    val packageName: String,
    private val strategy: HideStrategy = HideStrategy.REMOVE,
) {
    companion object {
        private const val TAG = "HideBottomTabs"
        private const val BOTTOM_FRACTION = 0.67
    }

    // ---- attach paths ------------------------------------------------------

    fun attachTo(app: Application) {
        writeLog("attachTo: registering ActivityLifecycleCallbacks")
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName == packageName) {
                    scanAllWindows(activity, "ActivityLC.onResumed")
                    // attachLayoutScan 注册 decorView 的 OnGlobalLayoutListener，
                    // 每次布局都重扫底部区域。这是处理「contentDescription 在 XML 里
                    // 静态设置」这种场景的主力：setContentDescription 钩子不会触发，
                    // 但 tab cell 仍然在 View 树里被 layout。
                    attachLayoutScan(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun attachViaOnResumeHook(module: XposedModule) {
        writeLog("attachViaOnResumeHook: registering hooks")
        // Hook View.setContentDescription. Fires the moment any view in
        // the host app has its contentDescription set. We DEFER the actual
        // hide to a layout pass — see scheduleDeferHide.
        //
        // KNOWN LIMITATION: this hook only fires for setContentDescription
        // calls in Java code. If the bottom tab's content-desc is set in
        // XML (the common case for stable tab names), the hook never fires
        // for it. We also keep [attachLayoutScan] as a backup, but on the
        // three target apps the bottom tab is either native-rendered (JD)
        // or in a secondary process (PDD `:titan`), so the layout scan
        // doesn't fire either. This hook therefore only matches TOP banner
        // entries (which the bottom-region filter correctly skips).
        val setContentDescription: Method = View::class.java.getDeclaredMethod(
            "setContentDescription", CharSequence::class.java
        )
        module.hook(setContentDescription).intercept { chain ->
            chain.proceed()
            try {
                val desc = chain.args.getOrNull(0) as? CharSequence ?: return@intercept null
                val descStr = desc.toString()
                if (descStr.isEmpty()) return@intercept null
                val view = chain.thisObject as? View ?: return@intercept null
                if (view.context?.packageName != packageName) return@intercept null
                val matchedByDesc = specs.any { spec ->
                    when (spec) {
                        is TabSpec.ByDesc -> descStr.contains(spec.desc)
                        else -> false
                    }
                }
                if (!matchedByDesc) return@intercept null
                writeLog("setContentDescription match: desc=$descStr view=${view.javaClass.simpleName}")
                val tabCell = findTabContainer(view) ?: view
                scheduleDeferHide(tabCell)
            } catch (t: Throwable) {
                writeLog("setContentDescription hook crashed: ${t.message}")
            }
            null
        }
        writeLog("attachViaOnResumeHook: hooks registered")
    }

    /**
     * Layout-driven scan: hook the activity's decorView layout changes and
     * on every layout pass re-scan the bottom region for matching tab cells.
     * Backup for tabs whose contentDescription is set in XML (the common
     * case for stable tab names like "视频" / "逛" / "多多视频"): the
     * setContentDescription hook never fires for them, but their tab cell
     * is in the Android view tree and gets laid out.
     *
     * Optimization: we first do a CHEAP walk to find any view in the
     * bottom region (top > 0.67*screenH). Only when the topmost such
     * view's top coordinate changes (suggesting a new tab cell was laid
     * out) do we do the FULL scan with spec matching. This keeps the
     * listener overhead low (~one cheap walk per layout pass) while still
     * catching the moment the bottom tab is fully inflated.
     *
     * Stops after either a successful hit OR 200 layout passes (~3-10s of
     * activity). Bounds the work to a few hundred calls.
     *
     * KNOWN LIMITATION: for apps that render UI in a secondary process
     * (e.g. PDD `:titan`) or natively (e.g. JD `TNViewGroup`), the main
     * process's decorView OnGlobalLayoutListener does NOT fire for the
     * bottom region. We register the listener successfully but the
     * callback is never invoked. The full scan therefore never runs.
     * Single-process apps with a Java view hierarchy that gets laid out
     * normally will work.
     */
    fun attachLayoutScan(activity: Activity) {
        val screenHeight = runCatching {
            activity.window?.decorView?.context?.resources?.displayMetrics?.heightPixels
        }.getOrNull() ?: return
        val decor = activity.window?.decorView ?: return
        val observer = runCatching { decor.viewTreeObserver }.getOrNull() ?: return
        writeLog("attachLayoutScan: hooking decorView layout, screenH=$screenHeight")
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            private var tickCount = 0
            private var lastTop: Int = -1
            override fun onGlobalLayout() {
                tickCount++
                val root = activity.window?.decorView ?: run {
                    runCatching { observer.removeOnGlobalLayoutListener(this) }
                    return
                }
                val topView = findAnyViewInBottomRegion(root, screenHeight)
                if (topView != lastTop) {
                    lastTop = topView
                    val hit = scanViewTreeForBottomRegion(root, screenHeight, "layoutScan[tick=$tickCount]")
                    if (hit) {
                        writeLog("attachLayoutScan: HIT at tick=$tickCount, removing listener")
                        runCatching { observer.removeOnGlobalLayoutListener(this) }
                    }
                }
                if (tickCount > 200) {
                    writeLog("attachLayoutScan: timeout (200 ticks), removing listener")
                    runCatching { observer.removeOnGlobalLayoutListener(this) }
                }
            }
        }
        runCatching { observer.addOnGlobalLayoutListener(listener) }
    }

    /**
     * Cheap walk: return the top of the first view in the bottom region
     * (top in [0.67*screenH, screenH-20]). -1 if none. Used as a
     * change-detector by the layout listener.
     */
    private fun findAnyViewInBottomRegion(root: View, screenH: Int): Int {
        val threshold = (screenH * BOTTOM_FRACTION).toInt()
        return runCatching {
            val stack = ArrayDeque<View>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                if (v.top in threshold..(screenH - 20)) return v.top
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        runCatching { v.getChildAt(i) }.getOrNull()?.let { stack.addLast(it) }
                    }
                }
            }
            -1
        }.getOrDefault(-1)
    }

    /**
     * Full scan returning whether a hide was applied. Used by the
     * layout-driven scan path.
     */
    private fun scanViewTreeForBottomRegion(root: View, screenHeight: Int, source: String): Boolean {
        return runCatching {
            val counter = intArrayOf(0)
            val matched = scanInternal(root, screenHeight, source, counter)
            if (counter[0] % 50 == 0) {
                writeLog("scan $source visited=${counter[0]} matched=$matched")
            }
            matched
        }.getOrDefault(false)
    }

    /**
     * Walk up the parent chain from a matched view and find the tab cell
     * to remove. Verified for JD bottom tab:
     *   LinearLayout id=fl            <- bottom tab bar
     *     FrameLayout                  <- tab cell (1 per tab)
     *       RelativeLayout
     *         View content-desc="逛"  <- matched view
     *
     * So `view.parent.parent` is the tab cell. We use 2-level climb —
     * more stable than 3-level which can overshoot and return the tab
     * bar itself.
     */
    private fun findTabContainer(view: View): View? {
        val parent1 = view.parent as? View
        val parent2 = parent1?.parent as? View
        return parent2 ?: parent1
    }

    /**
     * Schedule a deferred hide. The view's bounds are [0,0][0,0] at hook
     * time (not laid out). We attach a [View.OnLayoutChangeListener] which
     * fires on every layout change of THIS specific view (unlike
     * OnGlobalLayoutListener which is on the view tree and self-removes
     * after the first fire). This is critical because some apps (e.g.
     * JD) re-position the tab cell from top region (splash) to bottom
     * region (main page) AFTER the first layout pass — the listener
     * must survive that transition.
     *
     * Decision per layout:
     *   - top == 0    → not laid out yet, wait
     *   - top < 0.67*H → top banner / splash / status bar area, skip
     *   - top >= 0.67*H → bottom tab area, hide
     */
    private fun scheduleDeferHide(view: View) {
        if (view.getTag(VIEW_TAG_PROCESSED) == true) return
        writeLog("defer: attaching OnLayoutChangeListener to ${view.javaClass.simpleName} top=${view.top} bounds=[${view.left},${view.top}][${view.right},${view.bottom}]")
        val listener = View.OnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, _ ->
            writeLog("defer fire: ${v.javaClass.simpleName} top=$top oldTop=$oldTop bounds=$top..$bottom")
            if (v.getTag(VIEW_TAG_PROCESSED) == true) return@OnLayoutChangeListener
            if (top == 0) return@OnLayoutChangeListener  // not laid out yet
            val screenH = runCatching {
                v.context.resources.displayMetrics.heightPixels
            }.getOrNull() ?: return@OnLayoutChangeListener
            val threshold = (screenH * BOTTOM_FRACTION).toInt()
            when {
                top < threshold -> {
                    writeLog("defer: top=$top<threshold=$threshold, SKIP (top region)")
                }
                else -> {
                    writeLog("defer: top=$top>=threshold=$threshold, HIDE")
                    applyHide(v)
                }
            }
        }
        try {
            view.addOnLayoutChangeListener(listener)
        } catch (t: Throwable) {
            writeLog("defer: addOnLayoutChangeListener crashed: ${t.message}, falling back to immediate")
            applyHide(view)
        }
    }

    // ---- scan orchestration -----------------------------------------------

    // 跟踪是否已经为当前进程挂过 layout 扫描，避免 fallback 路径下
    // 6 个 scheduled scan 重复挂同一个 listener。
    @Volatile private var layoutScanAttached = false

    fun runScheduledScan(activity: Activity) {
        try {
            scanAllWindows(activity, "scheduled")
            if (!layoutScanAttached) {
                layoutScanAttached = true
                attachLayoutScan(activity)
            }
        } catch (t: Throwable) {
            writeLog("runScheduledScan crashed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Scan ALL windows of the host app: decorView + WMG.mViews.
     * JD's bottom tab is in a separate window not reachable from the
     * activity's decorView, so we also walk [WindowManagerGlobal.mViews].
     */
    private fun scanAllWindows(activity: Activity, source: String) {
        try {
            val screenHeight = runCatching {
                activity.window?.decorView?.context?.resources?.displayMetrics?.heightPixels
            }.getOrNull() ?: return
            val decor = activity.window?.decorView
            if (decor != null) {
                scanViewTree(decor, screenHeight, "$source.decorView")
            }
            scanWindowManagerGlobalViews(screenHeight, source)
        } catch (t: Throwable) {
            writeLog("scanAllWindows crashed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun scanWindowManagerGlobalViews(screenHeight: Int, source: String) {
        runCatching {
            val wmGlobalClass = Class.forName("android.view.WindowManagerGlobal")
            val wmGlobal = runCatching {
                wmGlobalClass.getMethod("getInstance").invoke(null)
            }.getOrNull() ?: return@runCatching
            val mViewsField = runCatching {
                wmGlobalClass.getDeclaredField("mViews")
            }.getOrNull() ?: return@runCatching
            mViewsField.isAccessible = true
            val mViews = mViewsField.get(wmGlobal) as? List<*> ?: return@runCatching
            writeLog("WMG.mViews.size=${mViews.size}")
            for ((i, raw) in mViews.withIndex()) {
                val v = raw as? View ?: continue
                val ctxPkg = runCatching { v.context?.packageName }.getOrNull()
                if (ctxPkg == packageName) {
                    writeLog("WMG[$i] ${v.javaClass.simpleName} pkg=$ctxPkg")
                    scanViewTree(v, screenHeight, "$source.wmg[$i]")
                }
            }
        }.onFailure { t ->
            writeLog("scanWindowManagerGlobalViews crashed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun scanViewTree(root: View, screenHeight: Int, source: String) {
        try {
            val counter = intArrayOf(0)
            val matched = scanInternal(root, screenHeight, source, counter)
            writeLog("scan $source visited=${counter[0]} matched=$matched")
        } catch (t: Throwable) {
            writeLog("scan $source crashed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Full tree DFS, no resource-id root lock. Every [ViewGroup.getChildAt]
     * is null-checked so a mid-flight view removal or stale reference
     * never propagates an NPE that would crash the host app.
     */
    private fun scanInternal(view: View, screenHeight: Int, source: String, counter: IntArray): Boolean {
        counter[0]++
        if (view.getTag(VIEW_TAG_PROCESSED) == true) return false

        val matchedSpec = runCatching { specs.firstOrNull { it.matches(view) } }.getOrNull()
        if (matchedSpec != null) {
            val threshold = (screenHeight * BOTTOM_FRACTION).toInt()
            when {
                view.top == 0 -> scheduleDeferHide(view)
                view.top < threshold -> { /* top region, skip */ }
                else -> applyHide(view)
            }
        }
        if (view is ViewGroup) {
            var i = 0
            while (i < view.childCount) {
                val child = runCatching { view.getChildAt(i) }.getOrNull()
                if (child == null) { i++; continue }
                if (scanInternal(child, screenHeight, source, counter)) return true
                i++
            }
        }
        return false
    }

    /**
     * Write a single line to the host app's cache dir. logd's per-PID
     * quota drops most of our output when the host app spams logs, so we
     * persist critical scan results to disk and pull them separately.
     */
    private fun writeLog(line: String) {
        runCatching { Log.w(TAG, "[$packageName] $line") }
        runCatching {
            val cacheDir = File("/data/data/$packageName/cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, "fuckthevideo_scan.log")
            file.appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    private fun applyHide(view: View) {
        if (view.getTag(VIEW_TAG_PROCESSED) == true) return
        view.setTag(VIEW_TAG_PROCESSED, true)
        Log.w(TAG, "[$packageName] hide ${view.javaClass.simpleName} strategy=$strategy")
        when (strategy) {
            HideStrategy.GONE -> view.visibility = View.GONE
            HideStrategy.REMOVE -> removeFromParent(view)
            HideStrategy.ZERO_SIZE -> zeroOutSize(view)
        }
    }

    private fun removeFromParent(view: View) {
        runCatching {
            val parent = view.parent as? ViewGroup ?: return@runCatching
            parent.removeView(view)
            parent.requestLayout()
        }.onFailure { t ->
            Log.w(TAG, "[$packageName] REMOVE failed, fallback to GONE: ${t.message}")
            view.visibility = View.GONE
        }
    }

    private fun zeroOutSize(view: View) {
        runCatching {
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
        }.onFailure { t ->
            Log.w(TAG, "[$packageName] ZERO_SIZE failed, fallback to GONE: ${t.message}")
            view.visibility = View.GONE
        }
    }
}

enum class HideStrategy {
    GONE,
    REMOVE,
    ZERO_SIZE,
}
