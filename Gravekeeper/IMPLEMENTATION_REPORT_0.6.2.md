# 0.6.2 实施报告

本版按共享机制整顿，不针对单个现象追加补丁。

1. 权限能力状态源：新增 AccessibilityCapability，统一检查 GuardAccessibilityService 是否启用并统一打开系统设置；首次教程只自动跳转一次；教程子页显示实时授权状态；主开关执行硬门禁；权限撤销后恢复等待状态；诊断报告复用同一状态源。

2. 动画与帧稳定：跨 Activity 转场不再同步执行全屏 content.draw() Bitmap 快照，改用系统窗口合成空间转场；窗口选择兼容的最高同分辨率刷新模式；控件轨道沿用单一 progress、可中断 settling 和统一 MotionSpec。

3. 全局入口行：entry 与 pageLink 使用确定行高、固定箭头语义槽位和独立文本中心线；白名单入口统一右箭头。

4. 父页恢复与白名单首帧：MainActivity.onResume 不再无条件清空缓存并重建当前页；白名单列表延后到首个布局帧构建，避免阻塞转场。

5. 更多页开关：二档选项复用基础设置 SegmentControl 和同一 progress 动画；主题、字体、对比度重建延迟到动画稳定帧。

版本升级为 0.6.2/versionCode 16；0.6.1 可靠回滚基线保留在 backups/Gravekeeper-0.6.1-pre-permission-and-ui-unification-verified-2026-08-13。
