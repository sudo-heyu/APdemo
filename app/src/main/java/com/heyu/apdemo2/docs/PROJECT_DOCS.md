# APdemo2 项目文档

## 相关文档

- [WiFi 连接实现细节](connection/WIFI_CONNECTION.md) - 使用无障碍服务（AccessibilityService）实现系统级 WiFi 切换
- [HTTP 接口文档](http/http_frontend.md) - 后端 API 接口规范
- [漫游实现分析](roaming/ROAMING_IMPLEMENTATION_ANALYSIS.md) - 漫游算法与日志系统分析
- [新AP选择模型](new_ap_selection/README.md) - Video Only 模型特征、转换与集成指南

---

## 1. 项目概述

APdemo2 是一个 Android WiFi 智能切换应用，核心功能包括：
1. **WiFi 扫描与评分** - 扫描周围热点并获取服务器评分
2. **一键连接** - 使用无障碍服务实现 WiFi 切换
3. **智能漫游** - 基于 LightGBM 模型的自动 AP 切换算法
4. **漫游日志** - 完整的算法执行日志记录与实时查看

---

## 2. 核心功能模块

### 2.1 WiFi 扫描与数据收集 ✅ 已实现

- **扫描机制**: 通过 `WifiManager` 进行周期性单次 WiFi 扫描
- **扫描流程**:
    - 每次触发一次 `wifiManager.startScan()`，读取一次结果
    - 同 SSID 多个 BSSID 取信号最强的
    - 扫描完成后同步评分到服务器，触发漫游评估
    - 两次扫描之间按轮询间隔定期向后端请求评分更新
- **弱信号 AP 保护机制** (20秒生命期):
    - 新扫描到的 AP：记录 `lastSeenTime`，加入列表
    - 已存在的 AP：更新信号强度等信息，刷新时间戳
    - 未扫到的 AP：距离上次出现 < 20秒则保留，≥ 20秒则移除
    - 目的：防止弱信号 AP 频繁出现/消失导致列表抖动
- **可配置参数**:
    - **扫描间隔**: 默认 35 秒，用户可在设置中调整
    - **后端请求间隔**: 默认 10 秒，用户可在设置中调整
- **后台保活**: 前台服务 + WakeLock + AlarmManager 确保后台运行
- **降级模式**: 服务器不可用时，使用默认评分（50分）仍然执行漫游评估

### 2.2 点击连接（无障碍服务方式）✅ 已实现

- **连接方式**: 使用 `WifiAccessibilityService`（无障碍服务）
- **连接流程**:
    1. 用户点击列表中的 AP
    2. `ScanForegroundService` 准备无障碍连接目标（不立即打开设置）
    3. `WifiFragment` 打开系统 WiFi 设置页面
    4. 无障碍服务自动识别目标 SSID，自动点击"连接"按钮并输入密码
    5. 系统完成 WiFi 切换后回调通知 UI 更新
- **状态管理**:
    - 密码自动保存，下次连接无需输入
    - 支持断开当前连接（仅清除本地状态，无法强制断开系统连接）
    - 无障碍服务未开启时，首次启动会弹窗引导用户前往系统设置开启

**相关代码**:
- `connection/PasswordStore.kt` - 密码本地存储
- `service/ScanForegroundService.kt` - 连接准备与触发（`connectToNetwork()`）
- `service/WifiAccessibilityService.kt` - 无障碍服务自动连接实现

### 2.3 智能漫游 ⚠️ 框架完成，连接层受限

- **算法架构**:
    - **Borda 排名**: 根据 RSSI 和众包评分进行初步筛选
    - **Pairwise 比较**: 使用 LightGBM ONNX 模型比较 AP 对
    - **决策逻辑**: 冷却期检查（默认5s，可配置）、最优 AP 判断
- **模型文件**:
    - `assets/ap_roaming_model_video.onnx` - Video Only 专用模型（10维特征）
    - [转换文档](new_ap_selection/README.md) - 模型转换与集成指南
- **密码过滤**: 漫游评估前过滤掉没有保存密码的加密 AP，只把可连接的 AP 传入选网算法
- **降级模式**: 服务器不可用时使用默认评分执行漫游评估

- **当前状态**:
    - ✅ 算法框架实现完成（Borda + Pairwise）
    - ✅ 自动漫游开关 UI（toolbar 按钮）
    - ✅ 漫游和手动连接统一使用无障碍服务
    - ✅ 密码过滤：跳过未保存密码的加密 AP
    - ✅ 服务器失败降级：使用默认评分仍执行评估
    - ✅ 新模型集成：Video Only 模型（10维特征，无 biz 特征）
    - ✅ 漫游策略固化为 ML：配置界面移除 ML/Score 切换，默认始终使用 ML 选网
    - ⚠️ 无障碍服务需要用户在系统设置中手动开启，未开启时无法自动漫游

**相关代码**:
- `roaming/ApSelectionManager.kt` - 选网主逻辑
- `roaming/ApRoamingModel.kt` - ONNX 模型推理（Video Only 版本）
- `roaming/ApPairwisePredictor.kt` - 预测器接口
- `service/ScanForegroundService.kt` - 漫游评估与触发（`evaluateAndTriggerRoaming()`、`triggerRoamingConnection()`）

### 2.4 漫游日志 ✅ 已实现

- **功能**:
    - ✅ 算法执行过程完整日志（候选AP、Borda排名、Pairwise比较、决策结果）
    - ✅ 日志文件本地存储（1MB 自动清空）
    - ✅ 主页面 toolbar 书本图标快速查看日志
    - ✅ 帮助页面也可查看日志
    - ✅ **实时更新**：打开日志对话框时新日志实时追加显示
    - ✅ 日志清空功能

**相关代码**:
- `roaming/RoamingLogManager.kt` - 日志管理器（含 `OnLogListener` 实时监听机制）
- `ui/MainActivity.kt` - 主页面日志查看入口（toolbar 书本图标）
- `ui/HelpFragment.kt` - 帮助页面日志查看

---

## 3. 待实现功能清单

### 🟡 中优先级（体验优化）

| 功能 | 描述 | 影响文件 |
|------|------|----------|
| 日志导出 | 支持分享/导出日志文件 | `RoamingLogManager.kt` |
| 漫游状态 UI | 在 WiFi 页面显示当前漫游状态 | `WifiFragment.kt` |
| 连接质量监控 | 收集连接后的实际质量数据 | 新增模块 |

### 🟢 低优先级（增强功能）

| 功能 | 描述 | 影响文件 |
|------|------|----------|
| 日志分文件存储 | 按日期分文件，避免自动清空 | `RoamingLogManager.kt` |
| 冷却期策略优化 | 区分手动/自动切换的冷却策略 | `ScanForegroundService.kt` |
| 模型特征完善 | 使用真实的连接状态而非简化判断 | `ApSelectionManager.kt` |
| WifiSuggestions 替代方案 | 用 `addNetworkSuggestions()` 实现真正的自动漫游 | 新增模块 |

---

## 4. 技术架构

### 4.1 模块划分

```
com.heyu.apdemo2
├── connection/          # WiFi 连接相关（保留但已废弃）
│   ├── WifiConnector.kt      # 连接逻辑封装（Android 9 以下，已废弃）
│   └── PasswordStore.kt      # 密码存储
├── roaming/             # 漫游算法
│   ├── ApSelectionManager.kt # 选网主逻辑
│   ├── ApRoamingModel.kt     # ONNX 模型推理
│   ├── ApPairwisePredictor.kt# 预测器接口
│   └── RoamingLogManager.kt  # 日志管理（含实时监听）
├── service/             # 后台服务
│   ├── ScanForegroundService.kt  # 扫描、评分同步与漫游服务
│   └── WifiAccessibilityService.kt # 无障碍自动连接服务（当前主要连接方式）
├── ui/                  # UI 层
│   ├── MainActivity.kt       # 主活动（含漫游日志入口、无障碍引导）
│   ├── WifiFragment.kt       # WiFi 列表页面
│   └── HelpFragment.kt       # 帮助页面
├── model/               # 数据模型
├── adapter/             # UI 适配器
├── network/             # 网络层
└── scanner/             # WiFi 扫描（单次扫描模式）
```

### 4.2 关键技术栈

- **语言**: Kotlin
- **网络**: OkHttp, Gson
- **UI**: XML Layouts, RecyclerView, Material Design
- **AI 推理**: ONNX Runtime (LightGBM 模型)
- **权限**: 位置、WiFi 状态、后台位置、通知

---

## 5. 关键流程图

### 5.1 扫描与评分流程

```
每 35s AlarmManager 唤醒
    ↓
executeSingleScan() — 单次 wifiManager.startScan()
    ↓
同 SSID 取最强信号 → 更新 UI 列表
    ↓
syncScoresAndEvaluate()
    ├── 服务器可用 → 上传 AP 列表，获取评分 → updateScoresFromResponse → 漫游评估
    └── 服务器不可用 → 降级模式，用默认评分 → 漫游评估
    ↓
scheduleNextScan() — 启动轮询 + 设置下次扫描闹钟
    ↓
每 10s pollRunnable → queryScoresOnly → 更新评分 → 漫游评估
    ↓
35s 后回到顶部
```

### 5.2 点击连接流程

```
用户点击 AP
    ↓
检查是否已连接 → 是 → 显示断开弹窗
    ↓ 否
检查是否开放网络 → 是 → 准备无障碍连接
    ↓ 否
检查是否有保存密码 → 是 → 准备无障碍连接
    ↓ 否
显示密码输入弹窗
    ↓
ScanForegroundService.connectToNetwork() → 准备无障碍服务目标
    ↓
WifiFragment 打开系统 WiFi 设置页面
    ↓
WifiAccessibilityService 自动识别目标 SSID 并点击连接
    ↓
系统完成 WiFi 切换 → 回调 onConnected → 更新 UI 状态
```

### 5.3 漫游算法流程

```
evaluateAndTriggerRoaming()
    ↓
密码过滤：排除未保存密码的加密 AP
    ↓
获取当前连接 AP
    ↓
ApSelectionManager.selectBestAp(可连接AP列表)
    ├── Borda 排名过滤（保留前 K 个）
    └── Pairwise 模型比较（LightGBM ONNX）
    ↓
决策逻辑
    ├── 当前已是最优 → 跳过
    ├── 冷却期内（默认5s，可配置）→ 跳过
    └── 需要切换 → triggerRoamingConnection()
    ↓
WifiAccessibilityService.connectFromBackground() — 无障碍后台自动切换
```

---

## 6. 已知限制

1. **无障碍服务需要手动开启**: 用户必须先在系统设置中开启无障碍服务，否则无法自动完成 WiFi 切换。首次启动应用会引导用户前往设置
2. **模型特征简化**: 连接状态判断使用 `RSSI > -90` 近似，非真实连接状态
3. **冷却期不区分手动/自动**: 默认5秒冷却期（用户可在设置中配置）对所有切换统一生效

---

## 7. 使用说明

1. **启动应用**: 首次启动需授予位置权限和通知权限，并前往系统设置开启无障碍服务
2. **配置服务器**: 点击右上角设置图标，输入服务器 IP、端口、扫描间隔和后端请求间隔
3. **连接 WiFi**: 点击列表中的 AP，应用会自动打开系统 WiFi 设置，无障碍服务自动完成连接
4. **开启自动漫游**: 点击顶部"漫游"按钮（需先保存过密码，且无障碍服务已开启）
5. **查看漫游日志**: 点击顶部书本图标，日志实时更新

---

## 8. 文档更新记录

| 日期 | 更新内容 |
|------|----------|
| 2025-04-02 | 重构文档结构，增加待实现功能清单，补充漫游和日志模块状态 |
| 2025-04-02 | 统一连接机制：漫游和手动连接都使用无障碍服务 |
| 2026-04-02 | 扫描简化为单次模式（默认35s间隔），后端轮询独立可配（默认10s） |
| 2026-04-02 | 漫游评估增加密码过滤和服务器失败降级模式 |
| 2026-04-02 | 漫游日志入口移至主页面 toolbar 书本图标，支持实时更新 |
| 2026-04-14 | 新增 Video Only 模型（10维特征），更新文档和模型转换说明 |
| 2026-06-11 | 修正连接方式描述：当前实际使用无障碍服务，移除过时的 WifiNetworkSpecifier 和 connectWithSpecifier 引用 |
| 2026-06-11 | 清理冗余文档：删除 CHANGELOG、PROJECT_SUMMARY、requirements、dense_ap_selection 等 |
| 2026-04-15 | 新增弱信号 AP 保护机制（20秒生命期），防止列表抖动 |
| 2026-06-11 | 修正：冷却期默认时间为 5 秒（可配置），原文档误写为 30 秒 |
| 2026-06-11 | 新增：Android Wi-Fi Scan Throttling 扫描节流限制说明（开发者选项可关闭） |
