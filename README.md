<p align="center">
  <img src="../../ui/brand/gravekeeper-icon/gravekeeper-icon-master.png" width="120" alt="Gravekeeper Icon">
</p>

<h1 align="center">守目人 Gravekeeper</h1>

<p align="center">
  <strong>完全离线的短视频与直播健康营销防护工具</strong>
</p>

<p align="center">
  <a href="#功能特性">功能</a> ·
  <a href="#工作原理">原理</a> ·
  <a href="#屏幕截图">截图</a> ·
  <a href="#快速开始">开始使用</a> ·
  <a href="#构建">构建</a> ·
  <a href="#隐私与安全">隐私</a> ·
  <a href="#技术栈">技术</a> ·
  <a href="#许可证">许可</a>
</p>

---

守目人是一款运行在 Android 11+ 设备上的本地保护工具。当用户浏览抖音、快手等短视频和直播平台时，应用通过无障碍服务实时分析屏幕内容，利用端侧 AI 模型识别保健品营销、夸大功效宣传和制造健康焦虑等风险内容，并根据用户设置进行提醒或自动划走。

**所有分析均在设备本地完成。** 不联网、不上传、不存储屏幕数据。

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/first_launch_page_02_protection_strength.png" width="240" alt="多档保护强度">
  <img src="app/src/main/res/drawable-nodpi/first_launch_page_05_hidden_entry.png" width="240" alt="极简无痕隐形">
  <img src="app/src/main/res/drawable-nodpi/first_launch_page_07_local_offline.png" width="240" alt="零联网本地安全">
</p>

---

## 功能特性

### 智能识别

- **端侧多模型融合** — 自研 MobileNetV3-Small 视觉模型 + 字符级文本分类器 + 18 特征融合策略，全部在手机本地运行
- **多内容形态识别** — 区分短视频、直播和未知页面类型，分别使用独立的检测参数和阈值
- **健康营销信号检测** — 综合画面、文字、价格、购物车、下单提示、健康词汇、老年相关词汇、负向语境、全球购风险等多维信号
- **负向语境保护** — 自动降低科普、辟谣、打假、监管和劝阻购买等内容的误判风险

### 保护策略

- **多档保护强度** — 按平台（抖音/快手）和内容类型（短视频/直播）独立配置敏感等级
- **提醒或自动划走** — 风险内容可通过通知提醒，或按策略自动执行向上滑动
- **直播账户白名单** — 为信任的直播账户设置豁免，白名单内容不触发保护

### 隐形与低功耗

- **隐藏运行** — 可隐藏桌面图标，从最近任务列表中移除，静默守护
- **低功耗模式** — 支持降低检测频率、低电量暂停、连续失败保护
- **智能触发** — 仅在目标应用前台时激活，离开、锁屏或屏幕关闭时自动暂停

### 通知与控制

- **常驻状态通知** — 在通知栏显示保护运行状态，支持快捷停止
- **完整的用户界面** — 浅色/深色主题、字体放大、对比度增强、逐步教程

---

## 工作原理

```
屏幕画面 + OCR 文字
        │
        ├──▶ MobileNetV3-Small 视觉模型（192×416 RGB）
        │      判断页面类型、营销强度、健康领域、老年指向
        │
        ├──▶ ML Kit 中文 OCR ──▶ 字符 n-gram 哈希 + 文本分类器
        │      分析销售、健康、老年人群相关性
        │
        ├──▶ 规则引擎
        │      价格、购物车、下单提示、白名单、画面状态
        │
        └──▶ 18 特征融合（逻辑回归 + 时间聚合）
               │
               ▼
         风险判定 → 通知提醒 / 自动划走
```

- **视觉模型**：输入 `float32[1,3,416,192]`，输出页面类型、营销强度、五个健康领域标签和老年指向的概率
- **文本模型**：字符级 1–4 gram 哈希特征（262144 维）+ 三个独立的 `LogisticRegression` 分类器，INT8 量化
- **融合模型**：`StandardScaler → LogisticRegression`，接收视觉分数、文本分数和 16 个规则特征，输出最终概率

详细模型说明、训练数据和运行时契约请参阅 [模型仓库](https://huggingface.co/AvalonskyAfar/KeepersEye-1)。

---

## 屏幕截图

| 首次启动 | 主界面 | 设置页面 |
|:---:|:---:|:---:|
| <img src="app/src/main/res/drawable-nodpi/first_launch_page_03_customization.png" width="200"> | <img src="app/src/main/res/drawable-nodpi/first_launch_page_04_live_whitelist.png" width="200"> | <img src="app/src/main/res/drawable-nodpi/first_launch_page_06_target_app_rest.png" width="200"> |

---

## 快速开始

### 下载安装

1. 从 [Releases](../../releases) 下载最新 APK
2. 安装到 Android 11+（API 30）设备
3. 首次打开，跟随教程授予无障碍权限
4. 打开保护总开关，开始使用

### 系统要求

| 要求 | 值 |
|------|------|
| Android 版本 | 11+（API 30） |
| 架构 | arm64-v8a |
| 需要的权限 | 无障碍服务、通知权限 |
| 网络要求 | **无** — 完全离线运行 |

---

## 构建

### 环境要求

- Android Studio（或 Gradle 命令行）
- JDK 17
- Android SDK 36

### 编译

```bash
# 进入软件目录
cd software/Gravekeeper

# Debug 构建
./gradlew :app:assembleDebug

# Release 构建
./gradlew :app:assembleRelease
```

构建产物位于 `app/build/outputs/apk/`。

### 运行测试

```bash
./gradlew :app:testDebugUnitTest
```

### 主要依赖

| 组件 | 版本 | 说明 |
|------|------|------|
| LiteRT | 2.1.4 | Google AI Edge 端侧推理运行时 |
| ML Kit Text Recognition (Chinese) | 16.0.1 | Google 中文 OCR |
| JUnit | 4.13.2 | 单元测试框架（仅开发期） |

---

## 隐私与安全

守目人将用户隐私保护作为核心设计原则：

- **完全离线** — 不申请联网权限，不连接任何远程服务器
- **仅内存处理** — 屏幕内容仅在设备内存中参与即时分析，不写入截图文件
- **零数据上传** — 不上传截图、OCR 文字、账户名称、视频内容、判断结果或性能数据
- **权限最小化** — 仅使用无障碍服务（读取屏幕和执行手势）、通知权限和使用统计权限
- **随时可撤销** — 用户可随时关闭保护、撤销无障碍权限或卸载应用

应用不声明 `INTERNET`、`ACCESS_NETWORK_STATE`、前台服务、唤醒锁、开机自启或屏幕录制权限。

> **注意：** 这是本地辅助工具，不是医疗、法律或金融决策系统。模型可能在新界面、低质量画面、遮挡画面和健康科普内容中产生误判。自动划走功能应由用户自行开启并承担相应风险。

---

## 技术栈

| 层次 | 技术 | 用途 |
|------|------|------|
| 视觉模型 | MobileNetV3-Small (PyTorch → LiteRT) | 画面分析：页面类型、营销强度、健康领域、老年指向 |
| 文字识别 | Google ML Kit Text Recognition v2 (Chinese) | 设备端中文 OCR |
| 文本分类 | Scikit-learn HashingVectorizer + LogisticRegression (INT8) | 字符级文本特征分析 |
| 融合决策 | Scikit-learn StandardScaler + LogisticRegression | 18 特征标准化融合 |
| 应用框架 | Android Java (View 体系) | 界面、无障碍服务、生命周期 |
| 推理运行时 | Google AI Edge LiteRT 2.1.4 | 端侧视觉模型推理 |
| 构建工具 | Gradle + Android Gradle Plugin | 编译、混淆、签名 |

---

## 项目结构

```
gravekeeper-open-source/
├── software/Gravekeeper/    Android 应用源码（Gradle 工程）
├── model/                   模型文件、训练工具、评估数据
├── collector/               屏幕截图采集工具
├── ui/                      设计资源、截图快照、品牌素材
└── compliance/              合规与第三方检查清单
```

---

## 特别感谢

- **[api.uniprep.world](https://api.uniprep.world/)** — 在项目全程免费提供 AI 编程 API 服务。从数据标注、模型训练到 Android 工程落地，该 API 在预算极其有限的情况下为项目完成提供了关键支持。

- **[Google AI Edge LiteRT](https://github.com/google-ai-edge/LiteRT)** — 提供端侧模型推理运行时

- **[Google ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2)** — 提供设备端中文 OCR 能力

- 所有参与数据采集、人工标注、复核、真机测试和问题排查的贡献者

---

## 许可证

| 内容 | 许可证 |
|------|--------|
| 应用代码 | 见仓库根目录许可证文件 |
| 模型与训练产物 | [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/deed.zh-hans) |
| 第三方组件 | 各自遵守上游许可证 |

使用本工程或发布衍生版本时，请分别确认应用代码、模型、第三方依赖和数据的许可证与再分发权利。

---

<p align="center">
  <sub>守护你的视野，远离健康营销干扰。</sub>
</p>
