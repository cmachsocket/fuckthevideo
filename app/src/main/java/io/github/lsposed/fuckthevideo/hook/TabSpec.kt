package io.github.lsposed.fuckthevideo.hook

import android.view.View
import android.view.ViewGroup

/**
 * Describes how to locate a single bottom tab node.
 *
 * Strategies:
 * - [ByDesc]: content-desc **contains** [desc] on the view itself.
 *   Substring match, not exact — apps A/B-test tab names (JD's bottom
 *   "逛" tab is "逛魅族" one week, "逛2元" the next). Use the invariant
 *   core word ("逛", "视频") to survive renames.
 *
 * - [ByIndexInParent]: parent container's resource-id entry name ==
 *   [parentResourceName] AND this view is the [childIndex]-th child of
 *   that parent. For JD the bottom tab bar is a LinearLayout with
 *   resource-id entry name "fl", and the 2nd child (index 1) is the
 *   "逛" tab cell. The cell's contentDescription is set from XML at
 *   inflation time, BEFORE our setContentDescription hook is installed,
 *   so ByDesc on the cell never fires. ByIndexInParent uses the parent's
 *   resource-id (which is stable across A/B tests) and a fixed child
 *   index, so it's immune to desc renames.
 *
 * - [ByDescendantOf]: parent has resource-id = X, and any descendant of
 *   this view has content-desc containing Y. Retained for future use.
 *
 * Bottom-region filter (top > screenHeight * 0.67) is NOT here — it's
 * enforced in [HideBottomTabsHook.scan] so we can "continue" recursion
 * for non-matching nodes without hiding a top banner entry.
 */
sealed class TabSpec {
    data class ByDesc(val desc: String) : TabSpec()

    data class ByIndexInParent(
        val parentResourceName: String,
        val childIndex: Int,
    ) : TabSpec()

    data class ByDescendantOf(
        val parentResourceName: String,
        val descendantContentDesc: String,
    ) : TabSpec()

    /**
     * Whether [view] itself matches this spec.
     * The caller is responsible for the bottom-region screenHeight filter.
     */
    fun matches(view: View): Boolean {
        return when (this) {
            is ByDesc -> view.contentDescription?.toString()?.contains(desc) == true
            is ByIndexInParent -> {
                val parent = view.parent as? ViewGroup ?: return false
                val parentName = runCatching {
                    parent.resources.getResourceEntryName(parent.id)
                }.getOrNull()
                val idx = parent.indexOfChild(view)
                parentName == parentResourceName && idx == childIndex
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
    }

    private fun View.anyDescendantMatches(predicate: (String) -> Boolean): Boolean {
        if (predicate(this.contentDescription?.toString() ?: "")) return true
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                val child = getChildAt(i) ?: continue
                if (child.anyDescendantMatches(predicate)) return true
            }
        }
        return false
    }
}
