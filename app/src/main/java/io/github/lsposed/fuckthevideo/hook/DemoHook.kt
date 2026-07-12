package com.example.hidebottomtabs;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.HashSet;
import java.util.Set;

public class MainHook implements IXposedHookLoadPackage {

    // 三个目标App的包名
    private static final String PACKAGE_TAOBAO = "com.taobao.taobao";
    private static final String PACKAGE_JD = "com.jingdong.app.mall";
    private static final String PACKAGE_PDD = "com.xunmeng.pinduoduo";

    // 每个App对应的目标Tab的 content-desc
    private static final String TAB_TAOBAO = "视频";
    private static final String TAB_JD = "逛";
    private static final String TAB_PDD = "多多视频";

    // 是否启用“彻底移除”模式（true=从父容器移除，false=仅隐藏）
    // 如果隐藏后出现空白占位，可改为 true 试试
    private static final boolean ENABLE_REMOVE_MODE = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String packageName = lpparam.packageName;

        // 只处理这三个App
        if (!packageName.equals(PACKAGE_TAOBAO) &&
            !packageName.equals(PACKAGE_JD) &&
            !packageName.equals(PACKAGE_PDD)) {
            return;
        }

        XposedBridge.log("HideBottomTabs 模块已加载，目标包名: " + packageName);

        // 获取当前包名对应的目标Tab描述
        final Set<String> targetDescs = getTargetDescs(packageName);
        if (targetDescs.isEmpty()) {
            return;
        }

        // Hook Activity 的 onResume 方法
        // 选择 onResume 是因为此时界面已完全绘制，View树已构建完成
        XposedHelpers.findAndHookMethod(
            Activity.class,
            "onResume",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    View rootView = activity.getWindow().getDecorView();
                    // 开始递归遍历并隐藏目标Tab
                    traverseAndHide(rootView, targetDescs, packageName);
                }
            }
        );
    }

    /**
     * 根据包名返回需要隐藏的 content-desc 集合
     */
    private Set<String> getTargetDescs(String packageName) {
        Set<String> descs = new HashSet<>();
        switch (packageName) {
            case PACKAGE_TAOBAO:
                descs.add(TAB_TAOBAO);
                break;
            case PACKAGE_JD:
                descs.add(TAB_JD);
                break;
            case PACKAGE_PDD:
                descs.add(TAB_PDD);
                break;
        }
        return descs;
    }

    /**
     * 递归遍历View树，找到 content-desc 匹配的目标并处理
     */
    private void traverseAndHide(View view, Set<String> targetDescs, String packageName) {
        if (view == null) {
            return;
        }

        // 检查当前View的 contentDescription
        CharSequence desc = view.getContentDescription();
        if (desc != null && targetDescs.contains(desc.toString())) {
            XposedBridge.log("[" + packageName + "] 找到目标Tab: " + desc);

            if (ENABLE_REMOVE_MODE) {
                // 模式1：彻底从父容器移除（能消除空白，但可能影响布局动画）
                removeViewFromParent(view);
            } else {
                // 模式2：仅隐藏（保留空间，可能留下空白）
                view.setVisibility(View.GONE);
                XposedBridge.log("[" + packageName + "] 已将Tab设置为 GONE");
            }
            return; // 找到后不再继续遍历此分支
        }

        // 如果当前View是ViewGroup，递归遍历所有子View
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = group.getChildAt(i);
                traverseAndHide(child, targetDescs, packageName);
            }
        }
    }

    /**
     * 从父容器中彻底移除该View
     * 注意：移除后如果父容器重新布局，其他Tab可能会自动填满空间
     */
    private void removeViewFromParent(View view) {
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            group.removeView(view);
            XposedBridge.log("已将目标Tab从父容器中彻底移除");
        } else {
            XposedBridge.log("警告：目标Tab没有父容器，无法移除");
        }
    }
}
}
