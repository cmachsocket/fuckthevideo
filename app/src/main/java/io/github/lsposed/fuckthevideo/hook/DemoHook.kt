package io.github.lsposed.fuckthevideo.hook

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 示例 hook:拦截所有 Activity.onCreate。
 *
 * ⚠️ libxposed API 与老 Xposed 差异:
 *   - 只有 `hook(Executable)`,**没有** `hookAll(Class, name, params...)` 这种便利方法
 *   - 必须先反射拿到 Method 或 Constructor,再传入 hook()
 *   - deoptimize() 可让系统方法(inline)能被 hook 到
 *
 * Chain 上的方法是:
 *   chain.thisObject       被 hook 对象实例(可为 null,静态方法)
 *   chain.args             不可变 List<Object>
 *   chain.getArg<T>(i)     类型安全取值
 *   chain.proceed()        调用原方法,同 this+args
 *   chain.proceed(args)    用新参数调用
 *   chain.proceedWith(this)            新 this + 原参数
 *   chain.proceedWith(this, args)      新 this + 新参数
 *
 * 替换真实目标的写法:
 * ```
 * module.hook(targetClass.getDeclaredMethod("someMethod", String::class.java))
 *     .setPriority(XposedInterface.PRIORITY_HIGHEST)
 *     .intercept { chain ->
 *         val arg0 = chain.args[0] as String
 *         chain.proceed(arrayOf("intercepted"))  // 改参数
 *     }
 * ```
 */
class DemoHook(private val module: XposedModule) {

    companion object {
        private const val TAG = "FuckTheVideo"

        // 反射缓存,避免每次加载都 getDeclaredMethod
        private val activityOnCreate: Method by lazy {
            Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        }
    }

    fun apply() {
        module.hook(activityOnCreate).intercept { chain ->
            val activity = chain.thisObject as? Activity
            if (activity != null) {
                Log.d(TAG, "${activity.javaClass.name}#onCreate")
            }
            // void 方法:return null,框架忽略返回值
            null
        }
    }
}
