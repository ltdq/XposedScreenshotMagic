# XposedScreenshotMagic

一个专为 HyperOS (Android 14+) 设计的 Xposed 模块，用于在截图和录屏中隐藏特定的系统窗口和应用窗口。

## 功能特性

本模块通过 Hook 系统层级，自动识别并隐藏以下类型的窗口，使其在截图或录屏时不可见（显示为透明或背景）：

1.  **输入法 (IME)**：隐藏键盘窗口。
2.  **截图 UI**：隐藏系统截图时的闪烁边框或预览窗口。
3.  **侧边栏 (Sidebar)**：隐藏 HyperOS/MIUI 的全局侧边栏 (DockAssistantView)。
4.  **小窗与画中画**：
    *   隐藏自由窗口 (Freeform / 小窗模式)
    *   隐藏画中画 (PiP) 窗口
    *   **深度隐藏**：不仅隐藏应用内容，还会尝试隐藏系统绘制的窗口边框 (Task Surface)。

## 环境要求

*   **系统版本**：Android 14 (API 34) 及以上
*   **系统类型**：Xiaomi HyperOS (澎湃OS)
*   **框架**：LSPosed (推荐)

> **注意**：模块内置了系统判断逻辑，非 HyperOS 或 Android 14 以下系统将自动停止工作以避免冲突。

## 安装与使用

1.  下载并安装模块 APK。
2.  在 LSPosed 管理器中激活模块。
3.  **作用域**：勾选 `系统框架` (Android System / `android`)。
4.  重启手机（或软重启系统服务）以生效。

## 技术原理

本模块不依赖传统的 `FLAG_SECURE`，而是通过更底层的 `SurfaceControl` 操作实现：

*   Hook `com.android.server.wm.WindowStateAnimator.createSurfaceLocked`。
*   识别目标窗口的 `WindowingMode` (Freeform/Pinned) 和窗口类型。
*   直接对窗口的 `SurfaceControl` 应用 `SKIP_SCREENSHOT (0x40)` 标志。
*   针对小窗模式，进一步向上查找并隐藏对应的 `Task` Surface，以确保去除系统边框。

## 免责声明

*   本模块涉及系统底层修改，可能存在兼容性风险。
*   请确保在有救砖能力的情况下使用。
*   仅供学习和研究使用，请勿用于非法用途。
*   本项目借助AI生成，开源仅供学习。
*   请勿用于考试作弊，如违规产生的一切后果与本项目无关。
