package io.github.lsposed.fuckthevideo.hook

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 示例 hook:拦截所有 Activity.onCreate。
 *
 * libxposed 风格 —— OkHttp interceptor chain:
 *   chain.proceed()   调到下一个 interceptor,最终调到原方法
 *   chain.thisObject  被 hook 的对象实例
 *   chain.args        参数数组(可改)
 *   chain.result      返回值(只读,after 才可见)
 *
 * 替换真实目标的写法:
 * ```
 * module.hook(targetClass.getDeclaredMethod("someMethod", String::class.java))
 *     .setPriority(PRIORITY_DEFAULT)
 *     .intercept { chain ->
 *         chain.args[0] = "intercepted"   // before
 *         val r = chain.proceed()          // proceed
 *         Log.d(TAG, "got: $r")
 *         r                                // 必须返回原方法返回类型
 *     }
 * ```
 */
class DemoHook(private val module: XposedModule) {

    companion object {
        private const val TAG = "FuckTheVideo"
    }

    fun apply() {
        // hookAll:按签名 hook 一个类的所有匹配方法
        module.hookAll(
            Activity::class.java,
            "onCreate",
            Bundle::class.java,
        ).intercept { chain ->
            val activity = chain.thisObject
            Log.d(TAG, "${activity.javaClass.name}#onCreate")
            // void 方法:return null 即可
            null
        }
    }
}
