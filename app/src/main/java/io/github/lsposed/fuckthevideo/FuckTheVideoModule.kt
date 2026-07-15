package io.github.lsposed.fuckthevideo

import android.app.Activity
import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.lsposed.fuckthevideo.hook.HideBottomTabsHook
import io.github.lsposed.fuckthevideo.hook.HideStrategy
import io.github.lsposed.fuckthevideo.hook.TabSpec

/**
 * Vector / libxposed module entry.
 *
 * Entry discovery: [app/src/main/resources/META-INF/xposed/java_init.list]
 *
 * Business goal: hide "video" tab in Taobao / JD / Pinduoduo main page bottom bar.
 *
 * Per-app strategy:
 * - Taobao: TabHost (legacy widget) — REMOVE crashes on next focus switch
 *   because TabHost holds internal references to its tab views. Use ZERO_SIZE.
 * - JD: NavigationGroup is absolute-positioned FrameLayout — GONE doesn't
 *   reflow siblings. Use REMOVE + scanRootResourceId="fl" to lock scan root.
 * - Pinduoduo: bottom tab LinearLayout with no stable id — use REMOVE + full
 *   tree DFS, bottom-region filter excludes top banner entries.
 *
 * Two attach paths:
 * 1. Try ActivityLifecycleCallbacks (needs Application via static field
 *    reflection, which usually bypasses LSPosed's reflection check).
 * 2. Fall back to hooking Activity.onResume directly.
 *
 * KNOWN LIMITATIONS (verified on OnePlus OP60EDL1, Android 16, JD 13+ /
 * PDD 7+ / Taobao latest as of 2026-07):
 * - JD bottom tab is native-rendered (TNViewGroup/TNImage/TNLottieView).
 *   The tab cells never enter the main process's Java View tree. None
 *   of the four attach paths in [HideBottomTabsHook] can reach them.
 * - PDD renders UI in a `:titan` secondary process. The main process
 *   decorView's view tree is empty in the bottom region, so the
 *   scheduled scan and the layout scan never find a match.
 * - Taobao: not yet verified end-to-end on this device; based on the
 *   same code paths, expect a similar situation as PDD.
 *
 * The module's only reliable effect is correctly SKIPPING the top banner
 * entries (which have the same contentDescription as the bottom tab and
 * are set dynamically). It does NOT actually hide any bottom tab on
 * the three target apps as currently shipped. The hooks are kept in
 * place in case the app architecture changes (e.g. falls back to a
 * normal Java view hierarchy in an older version or via a config flag).
 */
class FuckTheVideoModule : XposedModule() {

    companion object {
        private const val TAG = "FuckTheVideo"
        private const val SELF_PKG = "io.github.lsposed.fuckthevideo"

        private data class Target(
            val pkg: String,
            val strategy: HideStrategy = HideStrategy.REMOVE,
            val specs: List<TabSpec>,
        )

        private val TARGETS = listOf(
            Target(
                pkg = "com.taobao.taobao",
                strategy = HideStrategy.REMOVE,
                specs = listOf(TabSpec.ByDesc("视频")),
            ),
            Target(
                pkg = "com.jingdong.app.mall",
                strategy = HideStrategy.REMOVE,
                // JD's bottom "逛" tab cell is the 2nd child (index 1) of
                // LinearLayout with resource-id entry name "fl". The cell's
                // contentDescription is set from XML at inflation time and
                // renamed dynamically (this week "逛魅族", last week "逛2元"),
                // so ByDesc can't catch it. ByIndexInParent uses the stable
                // parent resource-id and fixed child index.
                specs = listOf(TabSpec.ByIndexInParent(parentResourceName = "fl", childIndex = 1)),
            ),
            Target(
                pkg = "com.xunmeng.pinduoduo",
                strategy = HideStrategy.REMOVE,
                specs = listOf(TabSpec.ByDesc("多多视频")),
            ),
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        Log.i(TAG, "onPackageReady pkg=${param.packageName} isFirstPackage=${param.isFirstPackage}")
        if (!param.isFirstPackage) return
        if (param.packageName == SELF_PKG) return

        val target = TARGETS.firstOrNull { it.pkg == param.packageName }
        if (target == null) {
            Log.d(TAG, "ignore ${param.packageName} (not in scope)")
            return
        }

        Log.i(TAG, "hooking ${param.packageName} specs=${target.specs} strategy=${target.strategy}")
        val hook = HideBottomTabsHook(
            specs = target.specs,
            packageName = target.pkg,
            strategy = target.strategy,
        )
        val app = moduleContext()
        if (app != null) {
            Log.i(TAG, "using ActivityLifecycleCallbacks")
            hook.attachTo(app)
        } else {
            Log.w(TAG, "ActivityLifecycleCallbacks unavailable, fallback to onResume hook + scheduled scan")
            hook.attachViaOnResumeHook(this)
            // LSPosed's hook install is async and races with app activity
            // startup. Splash → main transition can complete before our hook
            // is even registered. Schedule scans at 1/3/5/10/20/30s that
            // don't rely on hooks — they iterate the ActivityThread.mActivities
            // map and scan whatever activity is currently visible. Long
            // delays are needed because some apps inflate their bottom tab
            // bar lazily (we observed 679-view sync scan vs 1661-view
            // uiautomator dump taken 12s later for one app).
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val runnable = Runnable { scheduledScan(hook) }
            handler.postDelayed(runnable, 1000)
            handler.postDelayed(runnable, 3000)
            handler.postDelayed(runnable, 5000)
            handler.postDelayed(runnable, 10000)
            handler.postDelayed(runnable, 20000)
            handler.postDelayed(runnable, 30000)
        }
        // attachTo 的 onActivityResumed 已经会调 attachLayoutScan；fallback 路径
        // 在第一次 scheduledScan 找到 activity 时也会调。这里不再额外调
        // attachLayoutScanOnFirstActivity —— mActivities 在 onPackageReady 时
        // 总是空的，原方案扫不到任何东西。
    }

    /**
     * Schedule-scan that runs in this process and finds the currently visible
     * activity by walking the ActivityThread's activities map directly. Does
     * NOT rely on any hook — the activity has already onCreate'd by the time
     * this runs.
     */
    private fun scheduledScan(hook: HideBottomTabsHook) {
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val currentAT = threadClass.getMethod("currentActivityThread").invoke(null) ?: return@runCatching
            val mActivities = runCatching { threadClass.getDeclaredField("mActivities") }.getOrNull()
                ?: return@runCatching
            mActivities.isAccessible = true
            val map = mActivities.get(currentAT) as? Map<*, *> ?: return@runCatching
            Log.w(TAG, "scheduledScan: mActivities.size=${map.size}")
            for ((_, record) in map) {
                if (record == null) continue
                val recordClass = record::class.java
                val activity = runCatching {
                    // Try getActivity() method first (older Android)
                    val m = recordClass.getMethod("getActivity")
                    m.isAccessible = true
                    m.invoke(record) as? Activity
                }.recoverCatching {
                    // Fall back to a field named "activity" (newer Android)
                    val f = recordClass.getDeclaredField("activity")
                    f.isAccessible = true
                    f.get(record) as? Activity
                }.getOrNull() ?: continue
                val finishing = runCatching { activity.isFinishing }.getOrDefault(true)
                val destroyed = runCatching { activity.isDestroyed }.getOrDefault(true)
                Log.w(TAG, "scheduledScan: got activity=${activity.javaClass.simpleName} pkg=${activity.packageName} finishing=$finishing destroyed=$destroyed attached=${activity.window != null}")
                if (activity.packageName == hook.packageName && !finishing && !destroyed) {
                    hook.runScheduledScan(activity)
                }
            }
        }.onFailure { t ->
            Log.w(TAG, "scheduledScan crashed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Resolve the host process Application instance via static field reflection
     * on android.app.ActivityThread. [ActivityThread.currentApplication] is
     * blocked by LSPosed's reflection guard, but reading static fields usually
     * bypasses it. Returns null if every candidate is missing — caller then
     * falls back to hooking Activity.onResume directly.
     */
    private fun moduleContext(): Application? {
        return runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            // Try known Application static field names first.
            for (name in listOf("mApplication", "sCurrentApplication", "sApplication")) {
                val field = runCatching { threadClass.getDeclaredField(name) }.getOrNull() ?: continue
                field.isAccessible = true
                val app = field.get(null) as? Application
                if (app != null && app.packageName != SELF_PKG) {
                    Log.w(TAG, "moduleContext: got app via $name -> ${app.packageName}")
                    return app
                }
            }
            // Android 16 removed these static fields. Try
            // currentActivityThread().currentApplication() — the standard
            // public-but-hidden way to get the host process Application.
            val currentAT = runCatching {
                threadClass.getMethod("currentActivityThread").invoke(null)
            }.getOrNull() ?: run {
                Log.w(TAG, "moduleContext: no currentActivityThread method either")
                return null
            }
            Log.w(TAG, "moduleContext: got currentActivityThread=${currentAT.javaClass.simpleName}")
            val currentApplication = runCatching {
                val m = threadClass.getMethod("currentApplication")
                m.isAccessible = true
                m.invoke(currentAT)
            }.onFailure { t ->
                Log.w(TAG, "moduleContext: currentApplication invoke failed: ${t.javaClass.simpleName}: ${t.message}")
            }.getOrNull()
            Log.w(TAG, "moduleContext: currentApplication raw=${currentApplication?.javaClass?.simpleName}")
            val app = currentApplication as? Application
            if (app != null && app.packageName != SELF_PKG) {
                Log.w(TAG, "moduleContext: got app via currentApplication -> ${app.packageName}")
                return app
            }
            Log.w(TAG, "moduleContext: app is null or self, packageName=${app?.packageName}")
            null
        }.getOrElse { t ->
            Log.w(TAG, "moduleContext crashed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }
}
