# APdemo2 项目文档

## 相关文档

- [WiFi 连接实现细节](connection/WIFI_CONNECTION_SPECIFIER.md) - WifiNetworkSpecifier 详细实现、废弃模块说明
- [HTTP 接口文档](http/http_frontend.md) - 后端 API 接口规范
- [需求文档](http/requirements.md) - 功能需求说明
- [漫游实现分析](roaming/ROAMING_IMPLEMENTATION_ANALYSIS.md) - 漫游算法与日志系统漏洞分析

---

## 1. 项目概述

APdemo2 是一个 Android WiFi 智能切换应用，核心功能包括：
1. **WiFi 扫描与评分** - 扫描周围热点并获取服务器评分
2. **一键连接** - 使用 WifiNetworkSpecifier 实现系统级 WiFi 连接
3. **智能漫游** - 基于 LightGBM 模型的自动 AP 切换算法
4. **漫游日志** - 完整的算法执行日志记录与查看

---

## 2. 核心功能模块

### 2.1 WiFi 扫描与数据收集 ✅ 已实现

- **扫描机制**: 通过 `WifiManager` 进行周期性 WiFi 扫描
- **扫描周期**:
    - **采集阶段**: 进行一次完整扫描，完成后上传服务器
    - **监控阶段**: 进入等待期，每 10 秒轮询评分更新
    - **循环间隔**: 150 秒一个完整周期
- **后台保活**: 前台服务 + WakeLock + AlarmManager 确保后台运行

### 2.2 点击连接（Specifier 方式）✅ 已实现

- **连接方式**: 使用 `WifiNetworkSpecifier` (Android 10+)
- **连接流程**:
    1. 用户点击列表中的 AP
    2. 系统弹出连接确认弹窗
    3. 用户确认后完成系统级 WiFi 切换
    4. 支持无障碍模式（开启后无需弹窗确认）
- **状态管理**:
    - 区分"系统级连接"（有互联网）和"本地连接"（仅应用内）
    - 密码自动保存，下次连接无需输入
    - 支持断开当前连接

**相关代码**:
- `connection/WifiConnector.kt` - 连接逻辑封装
- `connection/PasswordStore.kt` - 密码本地存储
- `service/ScanForegroundService.kt:663-747` - Specifier 连接实现

### 2.3 智能漫游 ⚠️ 框架完成，待打通

- **算法架构**:
    - **Borda 排名**: 根据 RSSI 和众包评分进行初步筛选
    - **Pairwise 比较**: 使用 LightGBM ONNX 模型比较 AP 对
    - **决策逻辑**: 冷却期检查、最优 AP 判断

- **当前状态**:
    - ✅ 算法框架实现完成
    - ✅ 自动漫游开关 UI
    - ❌ **漫游触发后的连接状态同步不可靠**
    - ❌ **手动连接和自动漫游使用两套连接机制**
    - ❌ **缺少连接结果确认和反馈**

**相关代码**:
- `roaming/ApSelectionManager.kt` - 选网主逻辑
- `roaming/ApRoamingModel.kt` - ONNX 模型推理
- `roaming/ApPairwisePredictor.kt` - 预测器接口
- `service/ScanForegroundService.kt:425-522` - 漫游评估与触发

### 2.4 漫游日志 ⚠️ 基础实现，待完善

- **当前功能**:
    - ✅ 算法执行过程日志记录
    - ✅ 日志文件本地存储
    - ✅ 帮助页面查看日志
    - ✅ 日志清空功能

- **待完善**:
    - ❌ **日志内容不完整** - 缺少切换结果、耗时、拒绝原因
    - ❌ **入口隐蔽** - 放在帮助页面，用户不易发现
    - ❌ **无导出功能** - 无法分享日志用于调试
    - ❌ **自动清空** - 超过 1MB 自动清空，可能丢失关键历史

**相关代码**:
- `roaming/RoamingLogManager.kt` - 日志管理器
- `ui/HelpFragment.kt:41-70` - 日志查看 UI

---

## 3. 待实现功能清单

### 🔴 高优先级（阻塞性问题）

| 功能 | 问题描述 | 影响文件 |
|------|----------|----------|
| 统一连接机制 | 漫游和手动连接使用同一套 Specifier 机制 | `ScanForegroundService.kt` |
| 状态同步修复 | 监听系统 WiFi 广播，确保 currentConnectedSsid 准确 | `ScanForegroundService.kt` |
| 连接结果确认 | 漫游触发后确认实际切换成功/失败 | `ScanForegroundService.kt` |
| 完善日志内容 | 记录切换结果、耗时、拒绝原因等关键信息 | `ScanForegroundService.kt`, `RoamingLogManager.kt` |

### 🟡 中优先级（体验优化）

| 功能 | 描述 | 影响文件 |
|------|------|----------|
| 独立日志页面 | 从帮助页面分离，提供实时日志流 | 新增 `RoamingLogFragment` |
| 日志导出 | 支持分享/导出日志文件 | `RoamingLogManager.kt` |
| 漫游状态 UI | 在 WiFi 页面显示当前漫游状态 | `WifiFragment.kt` |
| 连接质量监控 | 收集连接后的实际质量数据 | 新增模块 |

### 🟢 低优先级（增强功能）

| 功能 | 描述 | 影响文件 |
|------|------|----------|
| 日志分文件存储 | 按日期分文件，避免自动清空 | `RoamingLogManager.kt` |
| 冷却期策略优化 | 区分手动/自动切换的冷却策略 | `ScanForegroundService.kt` |
| 模型特征完善 | 使用真实的连接状态而非简化判断 | `ApSelectionManager.kt` |

---

## 4. 技术架构

### 4.1 模块划分

```
com.heyu.apdemo2
├── connection/          # WiFi 连接相关
│   ├── WifiConnector.kt      # 连接逻辑封装
│   └── PasswordStore.kt      # 密码存储
├── roaming/             # 漫游算法
│   ├── ApSelectionManager.kt # 选网主逻辑
│   ├── ApRoamingModel.kt     # ONNX 模型推理
│   ├── ApPairwisePredictor.kt# 预测器接口
│   └── RoamingLogManager.kt  # 日志管理
├── service/             # 后台服务
│   └── ScanForegroundService.kt  # 扫描与漫游服务
├── ui/                  # UI 层
│   ├── MainActivity.kt       # 主活动
│   ├── WifiFragment.kt       # WiFi 列表页面
│   └── HelpFragment.kt       # 帮助页面（含日志查看）
├── model/               # 数据模型
├── adapter/             # UI 适配器
├── network/             # 网络层
└── scanner/             # 扫描工具
```

### 4.2 关键技术栈

- **语言**: Kotlin
- **网络**: OkHttp, Gson
- **UI**: XML Layouts, RecyclerView, Material Design
- **AI 推理**: ONNX Runtime (LightGBM 模型)
- **权限**: 位置、WiFi 状态、后台位置、通知

---

## 5. 关键流程图

### 5.1 点击连接流程

```
用户点击 AP
    ↓
检查是否已连接 → 是 → 显示断开弹窗
    ↓ 否
检查是否开放网络 → 是 → 直接连接
    ↓ 否
检查是否有保存密码 → 是 → 直接连接
    ↓ 否
显示密码输入弹窗
    ↓
调用 connectWithSpecifier()
    ↓
系统弹出连接确认弹窗
    ↓
用户确认 → 系统完成 WiFi 切换
    ↓
回调 onConnected → 更新 UI 状态
```

### 5.2 漫游算法流程

```
评分更新 (updateScoresFromResponse)
    ↓
检查自动漫游开关
    ↓
评估漫游 (evaluateAndTriggerRoaming)
    ├── 获取当前连接 AP
    ├── 调用算法选出最佳 AP
    ├── 检查是否已是最优
    ├── 检查冷却期
    └── 决策：切换 / 跳过
    ↓
触发连接 (triggerRoamingConnection)
    ↓
✅ 统一使用 connectWithSpecifier()
    ↓
回调更新 currentConnectedSsid
```

---

## 6. 已知问题汇总

### 6.1 待验证问题

1. **漫游后状态同步**
   - 漫游和手动连接已统一使用 `connectWithSpecifier()`
   - 需要验证 `currentConnectedSsid` 更新是否可靠

### 6.2 中等问题

1. **日志信息不完整**
   - 缺少切换结果（成功/失败）
   - 缺少切换耗时
   - 缺少拒绝原因详情

2. **日志入口隐蔽**
   - 放在帮助页面，用户不易发现
   - 没有实时日志显示

### 6.3 轻微问题

1. **冷却期无用户提示**
2. **日志自动清空可能丢失历史**
3. **模型特征简化**（RSSI>-90 即认为可连接）

---

## 7. 使用说明

1. **启动应用**: 首次启动需授予位置权限和通知权限
2. **配置服务器**: 点击右上角设置图标，输入服务器 IP 和端口
3. **连接 WiFi**: 点击列表中的 AP，按系统弹窗确认连接
4. **开启自动漫游**: 点击顶部"自动漫游"按钮（需先保存过密码）
5. **查看漫游日志**: 切换到"帮助"页面 → "查看漫游日志"

---

## 8. 文档更新记录

| 日期 | 更新内容 |
|------|----------|
| 2025-04-02 | 重构文档结构，增加待实现功能清单，补充漫游和日志模块状态 |
| 2025-04-02 | 统一连接机制：漫游和手动连接都使用 connectWithSpecifier() |
