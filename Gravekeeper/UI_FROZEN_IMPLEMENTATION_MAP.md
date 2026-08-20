# Gravekeeper Android 冻结 UI 实现映射

本文件是 Android 正式实现的强制审计入口。任何已有组件的尺寸、颜色、边缘、阴影、字体、对齐、按压和动画，必须先读取 `UI_APPROVED_COMPONENT_SAMPLES.md` 与下表对应的正式 HTML 源；不得把冻结参数当成仅供参考的风格，也不得用未经记录的近似参数覆盖它们。

## 映射

| 冻结编号 | 正式源文件 | Android 实现入口 |
|---|---|---|
| UI-001 | `main-power-switch.html`、`main-interface-v2.html` | `UiKit.PowerSwitch`、`MainActivity.mainPage()` |
| UI-002 | `platform-strength-control.html` | `UiKit.SegmentControl`、`UiKit.controlRow()`、`UiKit.surface()` |
| UI-003 / UI-004 / UI-009 | `normal-settings-optical-center.html` | `MainActivity.normalSettingsPage()`、`UiKit.controlRow()`、`UiKit.entry()`、`UiKit.divider()` |
| UI-005 | `normal-settings-optical-center.html` | `UiKit.capsule()`、`UiKit.pageLink()`、`UiKit.ChevronView` |
| UI-006 | `whitelist-account-controls.html` | `WhitelistAccountsActivity`、`UiKit.CompactToggle` |
| UI-007 | `dialog-style-system.html`、`dialog-motion.html` | `UiKit.dialog()`、`UiKit.inputValidated()`、`UiKit.error()` |
| UI-008 | `advanced-settings-row-centering.html`、`normal-advanced-transition.html` | `MainActivity.advancedSettingsPage()`、`PageHost` |
| UI-010 | `main-interface-v2.html`、`main-interface-animation.html` | `MainActivity.mainPage()`、`MainActivity.navItem()`、`PageHost` |
| UI-011 | `precise-value-stepper.html` | `DeveloperOptionsActivity` 的精确数值控件 |
| UI-012 | `plain-text-surface.html` | `UiKit.plainTextSurface()`、信息与教程占位子页 |
| UI-013 | `permission-status-action.html` | 权限教程状态与操作区；占位期不提前添加未确认内容 |
| UI-014 | `bulk-term-editor.html` | `DeveloperOptionsActivity` 的基础词汇表编辑页 |
| UI-015 | `read-only-diagnostics.html` | `DeveloperOptionsActivity` 的诊断键值页 |
| UI-016 | `first-launch-flow-animation.html` | `FirstLaunchActivity` |
| UI-017 | `more-page-dark-soft.html` | `MainActivity.morePage()`、通用信息子页、主题实现 |
| UI-018 | 开发者紧凑分组规则 | `DeveloperOptionsActivity` 所有直接调节控件分组 |
| UI-019 | 教程根页正式预览 | `MainActivity.tutorialPage()`、教程占位子页 |

## 执行顺序

1. 先确定组件编号与正式源文件
2. 逐项抄录冻结结构和参数，不进行主观“优化”或颜色近似
3. 只有正式源没有定义的内容，才可继承同类组件的既有参数
4. 新增且无法继承的交互或视觉模式必须先记录缺口并取得确认
5. 编译后在真实 Android 渲染中检查浅色、深色、标准字体、放大字体、按压、拖动和页面转场
6. 业务设置必须连接真实状态源；视觉改动不得修改检测逻辑或 `0.5.4-alpha` 白名单语义

## 当前复核记录

- UI-001：已恢复独立的关闭橙红、开启亮蓝、中性实体滑块和 `320ms / 240ms` 动画基线
- UI-002 / UI-003 / UI-004：已恢复 K1 冻结色值、中性 K3 材质及四档 `1:4`、三档 `1:3`、二档 `2:1` 的名称区／轨道区比例
- UI-003 / UI-009：正常设置内“白名单账户”的标题和说明各自以 S1 中心轴居中，左箭头独立定位且不参与宽度计算
- 白名单业务：未修改二级页结构、匹配字段或 `0.5.4-alpha` 逻辑

### 2026-08-12 原生轨道屏幕呈现色冻结

- 颜色基准以 Android 最终屏幕呈现为准，不再把早期 HTML 中较深、较高饱和的语义源色直接绘制到 Canvas
- 关闭橙：主体 `#CE8353`，边缘 `#BE6938`
- 轻度黄：主体 `#DEBE77`，边缘 `#C29E52`
- 标准／开启蓝：主体 `#86ACF7`，边缘 `#6990E0`
- 严格绿：主体 `#80B897`，边缘 `#5B9774`
- “更多 → 界面主题 → 浅色”：独立主体 `#E2EDF7`，边缘 `#B7CCE0`；不得按普通三档的第二项误用轻度黄
- 深色模式仍使用已确认的中性暗槽，不直接铺浅色主题的彩色轨道
- 黄色实机证据：`build/reports/gk-light-yellow.png`；可见主体像素约 `#DEBE77`
- 绿色实机证据：`build/reports/gk-strict-green.png`；可见主体像素约 `#80B897`
- 浅色主题实机证据：`build/reports/gk-more-light.png` 与 `build/reports/gk-more-light.xml`；XML 已确认选中状态为“浅色”
- 以上状态共享同一套凹槽、圆角裁剪、内阴影、底缘高光与中性滑块材质；只替换状态底色和对应边缘色
