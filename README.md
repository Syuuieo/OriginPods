# OriginPods

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/Syuuieo/OriginPods)](https://github.com/Syuuieo/OriginPods/releases/latest)

OriginPods 是面向 **vivo OriginOS（支持 OriginOS 6 / OriginOS 7）** 的第三方蓝牙耳机系统集成模块。它让受支持的 **OPPO Enco**、**OnePlus Buds** 与 **realme Buds** 系列耳机原生接入 OriginOS **连接中心（ConnectCenter）卡片**、**系统蓝牙设置**与 **VivoTws 原生耳机详情页**，并在不接管 Android 音频路由的前提下补全左/右耳与充电盒电量、降噪模式切换、播放声音查找等系统级体验。

> [!WARNING]
> OriginPods 依赖 Root、LSPosed 与 OriginOS 系统私有接口。安装前请确认具备系统恢复手段；系统大版本更新可能会暂时影响 Hook 兼容性。本项目与 vivo、iQOO、OPPO、OnePlus、realme 及相关品牌无关。

## 能做什么

### OriginOS 系统集成

- **连接中心（ConnectCenter）原生卡片**
  - 兼容 **OriginOS 6**（如连接中心 `1.5.4.0`）与 **OriginOS 7**。
  - 在控制中心与设备卡片中展示耳机名称、**左耳 / 右耳 / 充电盒**实时电量与充电状态。
  - 原生渲染四态降噪图标与标签（**降噪 / 自适应 / 通透 / 关闭**），切换模式不断开蓝牙连接；缺失系统图标资源时自动矢量兜底绘制。
  - 接通卡片中的 **「查找 - 播放声音」**（`vdfs_find_earphone`），点击直接向耳机下发硬件响铃与停止响铃指令。
- **系统设置与原生 VivoTws 详情页**
  - 在系统蓝牙设备列表中将受支持耳机识别为原生 TWS 设备入口。
  - 桥接 `VivoTws` 详情页的状态与设置接口，支持实时同步左/右耳与充电盒电量，并在系统原生耳机设置页内操作降噪等级、游戏低延迟、音效、佩戴检测与查找耳机。
- **独立应用界面（Miuix）**
  - 采用基于 Miuix 的双栏界面（**主页 / 设置**）与二级页面（**支持的设备 / 关于**），控制操作全部回归系统原生界面。
  - **支持的设备**：支持查看并按型号名称、品牌（OPPO / OnePlus / realme）或 Product ID 实时搜索已适配型号。
  - **关于与更新**：支持直接从 GitHub Releases 检查新版本，展示系统作用域清单、开源协议与致谢信息。
  - 支持在设置中开启 **「隐藏桌面图标」**（隐藏后仍可从 LSPosed 管理器 → 模块中打开）。

### 会话与协议边界

- OriginPods **不会**替换 Android 的 A2DP/HFP 音频链路，不会转发音频流，也不会持续扫描蓝牙。
- 单一私有 RFCOMM 控制通道（同时支持标准与历史 OPPO SPP UUID）由系统蓝牙进程（`com.android.bluetooth`）统一维护，通过跨进程广播向连接中心、`VivoTws` 与模块界面同步状态，避免多进程抢占连接。
- 通过内置型号目录与 `0x8103` Product ID 响应动态映射各型号的降噪控制位，并保留官方控制 App（如欢律 HeyMelody）运行时退避机制。

## 兼容性概览

| 适配范围 | 匹配方式 | 电量能力 | 降噪与控制 |
|---|---|---|---|
| OPPO Enco Free4 | 实机验证（OriginOS 6 / OriginOS 7） | 左耳、右耳、充电盒 | 降噪（含多档深度）、自适应、通透、关闭、查找响铃、固件/功能开关 |
| OPPO Enco 系列 | 目录匹配 + `0x8103` Product ID | 左耳、右耳、充电盒 | 降噪、自适应/通透、关闭（按型号协议位动态映射） |
| OnePlus Buds 系列 | 目录匹配 + `0x8103` Product ID | 左耳、右耳、充电盒 | 降噪、自适应/通透、关闭（按型号协议位动态映射） |
| realme Buds 系列 | 目录匹配 + `0x8103` Product ID | 左耳、右耳、充电盒 | 降噪、自适应/通透、关闭（按型号协议位动态映射） |

## 系统要求

- **系统**：vivo OriginOS 6 / OriginOS 7（Android 13+，`minSdk = 33`）
- **框架**：已安装并激活的 **LSPosed**（`libxposed` API 101+）
- **LSPosed 作用域**（开启右上角「显示系统应用」后勾选）：
  - `com.android.bluetooth`（蓝牙服务，必选）
  - `com.vivo.connbase.connectcenter`（连接中心，必选）
  - `com.android.settings`（系统设置，必选）
  - `com.android.vivo.tws.vivotws`（原生耳机详情页，推荐）

## 安装与使用

1. 前往 [GitHub Releases](https://github.com/Syuuieo/OriginPods/releases/latest) 下载并安装最新版 `OriginPods` 正式签名版 APK。
2. 打开 **LSPosed 管理器 → 模块 → OriginPods**，启用模块并勾选上述作用域。
3. **重启手机**使系统进程加载 Hook。
4. 在系统蓝牙中连接受支持的 OPPO / OnePlus / realme 耳机，即可在连接中心卡片与系统设置蓝牙详情页中直接使用。

## 构建说明

```powershell
# 运行单元测试并构建正式版 APK（体积优化 + R8 + 正式签名）
.\gradlew.bat :protocol:testDebugUnitTest :integration:testDebugUnitTest :system-module:assembleRelease
```

## 开源协议

本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源。

## 致谢

- **[HyperEars](https://github.com/silverpoetry/HyperEars)**：OriginPods 的整体模块架构、协议/适配器分层设计、蓝牙单会话桥接思路以及 Miuix 前端界面风格均参考自 HyperEars，在此致以诚挚感谢！
- **[OppoPods](https://github.com/Leaf-lsgtky/OppoPods)**：参考 OPPO / 一加 / realme 设备白名单目录与协议字段解析，实现全系适配。
- **[Miuix](https://github.com/compose-miuix-ui/miuix)**：为模块提供流畅的 Compose UI 组件库。
- **[libxposed](https://github.com/libxposed/api)**：提供现代化的 Xposed API 101+ 模块开发接口。
