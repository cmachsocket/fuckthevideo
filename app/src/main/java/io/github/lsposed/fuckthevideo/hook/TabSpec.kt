package io.github.lsposed.fuckthevideo.hook

import android.view.View
import android.view.ViewGroup

/**
 * 描述单个底部 Tab 节点的两种定位方式。
 *
 * 大多数 App(Taobao、PDD、JD 等)的 Tab 是 FrameLayout + content-desc,
 * 用 [ByDesc] 一招鲜。
 *
 * 但有些 App(JD/拼多多某些版本)只给外层容器设了 resource-id,
 * 没有 contentDescription —— 此时只能精确定位
 * "parent resource-id = X 的第 N 个 child"。
 */
sealed class TabSpec {
    /** 内容描述精确匹配(淘宝 / PDD 默认走这个) */
    data class ByDesc(val desc: String) : TabSpec()

    /** 父容器 resource-id + 该 child 在父中的下标(0-based)(旧版京东走这个) */
    data class ByParent(val parentResourceName: String, val childIndex: Int) : TabSpec()

    /**
     * 父容器是 [parentResourceName] 的某个直接 child,
     * 且该 child 的 descendants(包含自身)中至少有一个节点的
     * content-desc **包含** [descendantContentDesc] 子串。
     *
     * 用 contains 而不是精确匹配的原因:
     * - 京东"视频" Tab 历史上叫过 "逛"、"逛2元"、可能还会改。
     *   但"逛"是产品定位核心词,大概率保留;锁定"逛"后改名自动跟上。
     * - 外层容器 FrameLayout 本身通常没 desc,desc 在内层 View 上,
     *   ByDesc 命中的是内层 View(藏错),ByDescendantOf 命中的是
     *   id/fl 的直接 child FrameLayout(藏对整个 Tab)。
     *
     * 用法:
     *   ByDescendantOf("com.jingdong.app.mall:id/fl", "逛")
     */
    data class ByDescendantOf(
        val parentResourceName: String,
        val descendantContentDesc: String,
    ) : TabSpec()

    /**
     * 判断给定 view 是否对应这个 spec。
     *
     * 注意:view 是被检查的"叶或中间节点",不包含顶层 rootView。
     * 这里匹配 view 本身,而不是它的 children(它本身被 hide,chidlren 也跟着不见)。
     */
    fun matches(view: View): Boolean = when (this) {
        is ByDesc -> view.contentDescription?.toString() == desc
        is ByParent -> {
            val parent = view.parent as? ViewGroup ?: return false
            val parentName = runCatching {
                parent.resources.getResourceEntryName(parent.id)
            }.getOrNull()
            parentName == parentResourceName && parent.indexOfChild(view) == childIndex
        }
        is ByDescendantOf -> {
            val parent = view.parent as? ViewGroup ?: return false
            val parentName = runCatching {
                parent.resources.getResourceEntryName(parent.id)
            }.getOrNull()
            parentName == parentResourceName
                && parent.indexOfChild(view) >= 0
                && view.anyDescendantMatches { it.contains(descendantContentDesc) }
        }
    }

    /**
     * 在 view 自己 + 整棵子树里找 content-desc 满足 [predicate] 的节点。
     * contentDescription 为 null 的节点降级为空串,contains 永远不命中 — 安全。
     */
    private fun View.anyDescendantMatches(predicate: (String) -> Boolean): Boolean {
        if (predicate(this.contentDescription?.toString() ?: "")) return true
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                if (getChildAt(i).anyDescendantMatches(predicate)) return true
            }
        }
        return false
    }
}

/**
 * 隐藏策略 — 同一段 hook 代码,可按 App 选不同打法:
 *
 * - [GONE]:仅设 visibility,GONE 不会让 LinearLayout 的 weight 平分,
 *   但会让 click 事件被 sibling 截断,所以"看起来还在但点不动"。
 *   对京东这种 absolute FrameLayout 没用。
 *
 * - [REMOVE]:从父 ViewGroup removeView 再 requestLayout。
 *   sibling 立即重排,Tab 真消失。**默认推荐**。
 *
 * - [ZERO_SIZE]:view 还在父里但宽高 0。某些 App 用 REMOVE 后
 *   fragment 切回又重新 inflate 时会再显出来,这时候 ZERO_SIZE 反而稳。
 */
enum class HideStrategy {
    GONE,
    REMOVE,
    ZERO_SIZE,
}
