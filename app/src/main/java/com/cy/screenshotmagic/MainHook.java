package com.cy.screenshotmagic;

import android.os.Build;
import android.view.WindowManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "ScreenshotMagic";
    private static final int SKIP_SCREENSHOT = 0x40; // SurfaceControl.SKIP_SCREENSHOT

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("android")) {
            return;
        }

        if (Build.VERSION.SDK_INT <= 34) { // Android 14 QPR2+，介于HyperOS一般只会在QPR0，所以这里用<=34来排除旧版本
            XposedBridge.log(TAG + ": Android version is too old (< 15), skipping hooks.");
            return;
        }

        if (!isHyperOS()) {
            XposedBridge.log(TAG + ": Not HyperOS, skipping hooks.");
            return;
        }

        XposedBridge.log(TAG + ": Loaded android package (System Server)");

        // Hook WindowStateAnimator.createSurfaceLocked 以直接应用 SurfaceControl 标志
        try {
            Class<?> wsaClass = XposedHelpers.findClass("com.android.server.wm.WindowStateAnimator", lpparam.classLoader);
            
            XposedBridge.hookAllMethods(wsaClass, "createSurfaceLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object animator = param.thisObject;
                    applySkipScreenshot(animator, lpparam.classLoader);
                }
            });
            
            XposedBridge.log(TAG + ": Hooked WindowStateAnimator.createSurfaceLocked");
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook WindowStateAnimator: " + t.getMessage());
        }
    }

    private void applySkipScreenshot(Object animator, ClassLoader classLoader) {
        try {
            // 1. 识别窗口
            Object mWin = XposedHelpers.getObjectField(animator, "mWin");
            if (mWin == null) return;

            Object mAttrs = XposedHelpers.getObjectField(mWin, "mAttrs"); // WindowManager.LayoutParams
            CharSequence title = (CharSequence) XposedHelpers.callMethod(mAttrs, "getTitle");
            String packageName = (String) XposedHelpers.callMethod(mWin, "getOwningPackage");
            int type = XposedHelpers.getIntField(mAttrs, "type");

            if (title == null) title = "";
            if (packageName == null) packageName = "";

            // 获取窗口模式 (WindowingMode)
            // 1=Fullscreen, 2=Pinned(PIP), 5=Freeform, 6=MultiWindow
            int windowingMode = 0;
            try {
                windowingMode = (int) XposedHelpers.callMethod(mWin, "getWindowingMode");
            } catch (Throwable ignore) {}

            // 调试日志：打印所有窗口信息，帮助用户识别漏掉的窗口
            XposedBridge.log(TAG + ": Inspecting Window -> Title: [" + title + "] | Package: [" + packageName + "] | Type: [" + type + "] | Mode: [" + windowingMode + "]");

            boolean shouldHide = false;
            String t = title.toString().toLowerCase();

            // 1. 输入法 (IME)
            if (type == WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
                shouldHide = true;
            }
            // 2. 截图 UI
            else if (type == 2036 || packageName.equals("com.miui.screenshot")) {
                shouldHide = true;
            }
            // 3. 侧边栏 (Sidebar)
            // 仅针对 HyperOS/MIUI 安全中心 (侧边栏 DockAssistantView)
            else if (packageName.equals("com.miui.securitycenter")) {
                 // Type 2026 = DockAssistantView (侧边栏)
                 // Type 2003 = FloatingWindow (小窗容器) - 保留以防万一，但 Task 隐藏应已覆盖
                 if (type == 2026 || type == 2003 || 
                     t.contains("dock") || t.contains("floatingwindow")) {
                     shouldHide = true;
                 }
            }

            // 4. 基于窗口模式的判断 (核心逻辑)
            // Mode 5 = Freeform (自由窗口/小窗)
            // Mode 2 = Pinned (画中画)
            // 这是隐藏小窗内容及其系统边框的关键
            if (windowingMode == 5 || windowingMode == 2) {
                shouldHide = true;
            }

            if (shouldHide) {
                // 2. 获取 SurfaceControl
                // 在 Android 14 中，mSurfaceControl 通常在 WindowState (mWin) 中，但有时也在 Animator 中
                Object surfaceControl = XposedHelpers.getObjectField(mWin, "mSurfaceControl");
                if (surfaceControl == null) {
                    surfaceControl = XposedHelpers.getObjectField(animator, "mSurfaceControl");
                }

                if (surfaceControl != null) {
                    // 3. 使用 Transaction 应用 SKIP_SCREENSHOT 标志
                    // 直接创建一个新的 Transaction 并立即应用，以确保标志生效。
                    Class<?> transactionClass = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", classLoader);
                    Class<?> surfaceControlClass = XposedHelpers.findClass("android.view.SurfaceControl", classLoader);
                    Object transaction = XposedHelpers.newInstance(transactionClass);
                    
                    try {
                        // 仅针对 HyperOS/MIUI 适配 (2参数): setFlags(sc, flags)
                        java.lang.reflect.Method setFlagsMethod = XposedHelpers.findMethodExact(transactionClass, "setFlags", surfaceControlClass, int.class);

                        setFlagsMethod.invoke(transaction, surfaceControl, SKIP_SCREENSHOT);

                        XposedHelpers.callMethod(transaction, "apply");
                        XposedHelpers.callMethod(transaction, "close");
                        
                        XposedBridge.log(TAG + ": Applied SKIP_SCREENSHOT (0x40) to " + title + " (" + packageName + ")");

                        // ---------------------------------------------------------
                        // 针对小窗模式 (Freeform/Pinned) 的额外处理：隐藏 Task 层级
                        // ---------------------------------------------------------
                        // 如果只隐藏 App Window，系统绘制的边框 (Decoration) 可能依然可见。
                        // 我们需要找到该 Window 所属的 Task，并隐藏 Task 的 SurfaceControl。
                        if (windowingMode == 5 || windowingMode == 2) {
                            try {
                                // 尝试获取 Task: WindowState -> ActivityRecord -> Task
                                // 或者直接 WindowState.getTask() (取决于 Android 版本)
                                Object task = XposedHelpers.callMethod(mWin, "getTask");
                                if (task != null) {
                                    Object taskSurface = XposedHelpers.getObjectField(task, "mSurfaceControl");
                                    if (taskSurface != null) {
                                        // 复用 transaction 对象 (需要重新创建，因为之前已经 close 了)
                                        Object taskTransaction = XposedHelpers.newInstance(transactionClass);
                                        
                                        setFlagsMethod.invoke(taskTransaction, taskSurface, SKIP_SCREENSHOT);
                                        
                                        XposedHelpers.callMethod(taskTransaction, "apply");
                                        XposedHelpers.callMethod(taskTransaction, "close");
                                        XposedBridge.log(TAG + ": Applied SKIP_SCREENSHOT to Task of " + title);
                                    }
                                }
                            } catch (Throwable t_task) {
                                XposedBridge.log(TAG + ": Failed to hide Task: " + t_task);
                            }
                        }
                        // ---------------------------------------------------------

                    } catch (Throwable e) {
                        XposedBridge.log(TAG + ": Failed to apply flags: " + e);
                    }
                } else {
                    XposedBridge.log(TAG + ": SurfaceControl is null for " + title);
                }
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error applying flag: " + t.toString());
            XposedBridge.log(t);
        }
    }

    private boolean isHyperOS() {
        String osName = getSystemProperty("ro.mi.os.version.name", "");
        return !osName.isEmpty();
    }

    private String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> clz = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = clz.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
