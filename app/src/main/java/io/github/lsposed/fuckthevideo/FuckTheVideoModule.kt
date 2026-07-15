package io.github.lsposed.fuckthevideo

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
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
        const val TAG = "FuckTheVideo"
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

    // ---- libxposed lifecycle ----------------------------------------------

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, "onModuleLoaded: ${param.processName}")
        log(Log.INFO, "framework: $frameworkName($frameworkVersionCode) API $apiVersion")

        val hasProp: (Long) -> Boolean = { prop -> frameworkProperties and prop != 0L }
        log(Log.INFO, "system supported: " + hasProp(PROP_CAP_SYSTEM))
        log(Log.INFO, "remote supported: " + hasProp(PROP_CAP_REMOTE))
        log(Log.INFO, "api protection: " + hasProp(PROP_RT_API_PROTECTION))
    }

    @Suppress("UNUSED_PARAMETER")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        // Early hook point: classloader is available but the app may not
        // have onCreate'd yet. We don't install any hook here because all
        // our hooks target system classes (View, ViewGroup, ActivityThread)
        // which are always available regardless of app classloader state.
        // Kept as a no-op log so we can see in logcat that onPackageReady
        // is preceded by onPackageLoaded. No @RequiresApi guard — our
        // minSdk is 27 and the framework version we test on is 16 (Q+),
        // and the libxposed API only delivers this callback on Q+ anyway.
        log(Log.INFO, "onPackageLoaded: ${param.packageName} classLoader=${param.defaultClassLoader}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log(Log.INFO, "onPackageReady: ${param.packageName} isFirst=${param.isFirstPackage} classLoader=${param.classLoader}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            log(Log.INFO, "app acf: ${param.appComponentFactory}")
        }

        if (param.packageName == SELF_PKG) return
        val target = TARGETS.firstOrNull { it.pkg == param.packageName }
        if (target == null) {
            log(Log.DEBUG, "ignore ${param.packageName} (not in scope)")
            return
        }
        if (!param.isFirstPackage) {
            // We only install in the main process. Secondary processes
            // (e.g. JD privileged_dong_process0) don't host the bottom
            // tab — it's in the main activity's view tree or rendered
            // natively from the main process. The exception is PDD's
            // :titan process, but we can't reach it via the host's
            // classloader anyway; the main process hooks are kept as
            // a best-effort.
            log(Log.INFO, "skip non-first package ${param.packageName}")
            return
        }

        installForTarget(target, param.classLoader)
    }

    // ---- per-target install -----------------------------------------------

    private fun installForTarget(target: Target, appClassLoader: ClassLoader) {
        log(Log.INFO, "hooking ${target.pkg} specs=${target.specs} strategy=${target.strategy}")
        val hook = HideBottomTabsHook(
            specs = target.specs,
            packageName = target.pkg,
            strategy = target.strategy,
        )
        val app = moduleContext()
        if (app != null) {
            log(Log.INFO, "using ActivityLifecycleCallbacks")
            hook.attachTo(app)
        } else {
            log(Log.WARN, "ActivityLifecycleCallbacks unavailable, fallback to onResume hook + scheduled scan")
            hook.attachViaOnResumeHook(this)
            // LSPosed's hook install is async and races with app activity
            // startup. Splash → main transition can complete before our
            // hook is even registered. Schedule scans at 1/3/5/10/20/30s
            // that don't rely on hooks — they iterate
            // ActivityThread.mActivities and scan whatever activity is
            // currently visible. Long delays are needed because some apps
            // inflate their bottom tab bar lazily (we observed 679-view
            // sync scan vs 1661-view uiautomator dump taken 12s later for
            // one app).
            scheduleScans(hook)
        }
        // App-specific tab hooks. These use param.classLoader to load
        // classes from the host app. They run in addition to the
        // generic HideBottomTabsHook paths because the bottom tab in
        // modern apps is a Java view (e.g. JD's NavigationGroup extends
        // LinearLayout) but its contentDescription is set in XML so the
        // generic setContentDescription hook never fires. The app-
        // specific hooks target a known tab cell class and reflect on its
        // label field directly.
        when (target.pkg) {
            "com.jingdong.app.mall" -> installJDNavigationGroupHide(appClassLoader)
        }
    }

    /**
     * JD-specific: hook every NavigationGroup constructor. When a
     * NavigationGroup is created, schedule a post on the main thread
     * that walks its children, looks for NavigationButton with label
     * "逛" (this week's A/B variant: "逛2元", "逛魅族" etc.) and sets
     * its visibility to GONE. We hide one specific tab cell instead of
     * the whole NavigationGroup so the rest of the bar reflows
     * correctly.
     *
     * Verified in JD 13+ decompilation (classes2.dex):
     *   com.jingdong.common.unification.navigationbar.newbar.NavigationGroup
     *     extends LinearLayout implements OnClickListener
     *     field `buttons: List<NavigationButton>` — all tab cells
     *     PRIVATE method `initButtons(INavigationShow, Activity)` — populate buttons
     *   com.jingdong.common.unification.navigationbar.newbar.NavigationButton
     *     extends FrameLayout
     *     field `label: String` PRIVATE — the tab's text
     */
    private fun installJDNavigationGroupHide(classLoader: ClassLoader) {
        val navGroupClass = runCatching {
            Class.forName(
                "com.jingdong.common.unification.navigationbar.newbar.NavigationGroup",
                true,
                classLoader,
            )
        }.getOrNull() ?: run {
            log(Log.WARN, "installJDNavigationGroupHide: NavigationGroup class not found in classLoader")
            return
        }
        log(Log.INFO, "installJDNavigationGroupHide: found ${navGroupClass.name}")

        // Hook every public constructor. After the constructor returns
        // the ViewGroup exists but its children (NavigationButton) may
        // not be inflated yet — `initButtons` runs later from
        // JDNavigationFragment.onViewCreated. We post a delayed check
        // so the view tree is fully laid out before we touch it.
        for (ctor in navGroupClass.declaredConstructors) {
            runCatching {
                hook(ctor).intercept { chain ->
                    chain.proceed()
                    val view = chain.thisObject as? android.view.View ?: return@intercept null
                    val handler = Handler(Looper.getMainLooper())
                    // Multiple delays because tab cells can be re-added
                    // after orientation / config change / push tab swap.
                    for (delayMs in listOf(300L, 1500L, 5000L)) {
                        handler.postDelayed({ hideJDTabs(view) }, delayMs)
                    }
                    null
                }
            }.onFailure { t ->
                log(Log.WARN, "installJDNavigationGroupHide: ctor hook failed", t)
            }
        }
    }

    private fun hideJDTabs(navGroup: android.view.View) {
        if (navGroup !is android.view.ViewGroup) return
        val targetKeywords = listOf("逛")
        for (i in 0 until navGroup.childCount) {
            val child = runCatching { navGroup.getChildAt(i) }.getOrNull() ?: continue
            val className = child.javaClass.name
            if (!className.contains("NavigationButton")) continue
            val label = runCatching {
                val field = child.javaClass.getDeclaredField("label")
                field.isAccessible = true
                field.get(child) as? String
            }.getOrNull() ?: continue
            if (targetKeywords.any { label.contains(it) }) {
                log(Log.INFO, "hideJDTabs: HIT ${className.substringAfterLast('.')} label=$label -> GONE")
                child.visibility = android.view.View.GONE
                val lp = child.layoutParams
                if (lp != null) { lp.width = 0; lp.height = 0; child.layoutParams = lp }
                child.requestLayout()
            } else {
                log(Log.DEBUG, "hideJDTabs: keep ${className.substringAfterLast('.')} label=$label")
            }
        }
    }

    private fun scheduleScans(hook: HideBottomTabsHook) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = Runnable { scheduledScan(hook) }
        for (delayMs in listOf(1000L, 3000L, 5000L, 10000L, 20000L, 30000L)) {
            handler.postDelayed(runnable, delayMs)
        }
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
            log(Log.WARN, "scheduledScan: mActivities.size=${map.size}")
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
                log(Log.WARN, "scheduledScan: got activity=${activity.javaClass.simpleName} pkg=${activity.packageName} finishing=$finishing destroyed=$destroyed attached=${activity.window != null}")
                if (activity.packageName == hook.packageName && !finishing && !destroyed) {
                    hook.runScheduledScan(activity)
                }
            }
        }.onFailure { t ->
            log(Log.WARN, "scheduledScan crashed", t)
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
                    log(Log.WARN, "moduleContext: got app via $name -> ${app.packageName}")
                    return app
                }
            }
            // Android 16 removed these static fields. Try
            // currentActivityThread().currentApplication() — the standard
            // public-but-hidden way to get the host process Application.
            val currentAT = runCatching {
                threadClass.getMethod("currentActivityThread").invoke(null)
            }.getOrNull() ?: run {
                log(Log.WARN, "moduleContext: no currentActivityThread method either")
                return null
            }
            log(Log.WARN, "moduleContext: got currentActivityThread=${currentAT.javaClass.simpleName}")
            val currentApplication = runCatching {
                val m = threadClass.getMethod("currentApplication")
                m.isAccessible = true
                m.invoke(currentAT)
            }.onFailure { t ->
                log(Log.WARN, "moduleContext: currentApplication invoke failed", t)
            }.getOrNull()
            log(Log.WARN, "moduleContext: currentApplication raw=${currentApplication?.javaClass?.simpleName}")
            val app = currentApplication as? Application
            if (app != null && app.packageName != SELF_PKG) {
                log(Log.WARN, "moduleContext: got app via currentApplication -> ${app.packageName}")
                return app
            }
            log(Log.WARN, "moduleContext: app is null or self, packageName=${app?.packageName}")
            null
        }.getOrElse { t ->
            log(Log.WARN, "moduleContext crashed", t)
            null
        }
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * Mirror of the example's log() helper. Centralises logcat emission so
     * we can swap the sink (e.g. file-only in production) in one place.
     */
    private fun log(priority: Int, msg: String, t: Throwable? = null) {
        if (t != null) {
            Log.println(priority, TAG, "$msg\n${Log.getStackTraceString(t)}")
        } else {
            Log.println(priority, TAG, msg)
        }
    }
}
