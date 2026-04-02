# 漫游算法与日志系统实现分析报告

## 概述

本文档分析 APdemo2 项目中漫游自动切换算法和漫游日志系统的当前实现状态。

---

## 一、漫游算法实现

### 1.1 核心组件

| 组件 | 文件路径 | 职责 |
|------|----------|------|
| `ApSelectionManager` | `roaming/ApSelectionManager.kt` | 漫游选网主逻辑，Borda排名+Pairwise比较 |
| `ApRoamingModel` | `roaming/ApRoamingModel.kt` | ONNX模型推理，加载LightGBM模型 |
| `ApPairwisePredictor` | `roaming/ApPairwisePredictor.kt` | 预测器接口，解耦模型实现 |
| `RoamingLogManager` | `roaming/RoamingLogManager.kt` | 漫游算法执行日志管理（含实时监听） |

### 1.2 算法流程

```
扫描完成 / 轮询评分更新 / 服务器失败降级
    ↓
evaluateAndTriggerRoaming()
    ↓
密码过滤（排除未保存密码的加密AP）
    ↓
获取可连接候选AP列表
    ↓
Borda排名过滤 (保留前K个)
    ├── 按RSSI排序计分
    └── 按众包评分排序计分
    ↓
Pairwise比较 (LightGBM模型)
    ├── 对每对AP进行模型推理
    ├── 计算平均概率和获胜次数
    └── 选择综合得分最高的AP
    ↓
决策是否切换
    ├── 当前已是最优 → 跳过
    ├── 冷却期内 (30秒) → 跳过
    └── 触发连接切换
```

### 1.3 触发时机

漫游评估在以下三种情况下触发：

1. **服务器同步成功**：`updateScoresFromResponse()` → `evaluateAndTriggerRoaming()`
2. **服务器同步失败（降级模式）**：`syncScoresAndEvaluate()` 的 `onError` → 使用默认评分执行评估
3. **轮询同步失败（降级模式）**：`queryScoresOnly()` 的 `onError` → 使用现有评分执行评估

### 1.4 密码过滤

在 `evaluateAndTriggerRoaming()` 入口处，过滤掉不可连接的 AP：

```kotlin
val connectableAps = currentAccessPoints.filter { ap ->
    if (!ap.isSecured()) true  // 开放网络
    else !PasswordStore.get(this, ap.ssid).isNullOrEmpty()  // 已存密码
}
```

这避免了算法选出无法连接的 AP，浪费漫游机会和触发无意义的冷却期。

### 1.5 连接触发

```kotlin
// ScanForegroundService.triggerRoamingConnection()
private fun triggerRoamingConnection(targetAp: AccessPoint) {
    val password = PasswordStore.get(this, targetAp.ssid) ?: ""
    val isOpen = !targetAp.isSecured()
    // 统一使用 WifiNetworkSpecifier
    connectWithSpecifier(targetAp.ssid, password, callback)
}
```

漫游和手动连接使用完全相同的 `connectWithSpecifier()` 方法。

---

## 二、扫描与评分流程

### 2.1 单次扫描模式

每次扫描只调用一次 `wifiManager.startScan()`，不再累积多次结果。

```
AlarmManager 每 scanInterval(默认35s) 触发
    ↓
executeSingleScan()
    ↓
wifiManager.startScan() — 单次扫描
    ↓
读取 scanResults → 同SSID取最强信号 → 按RSSI降序
    ↓
syncScoresAndEvaluate()
    ├── 服务器可用 → 上传获取评分 → 漫游评估
    └── 服务器不可用 → 降级模式漫游评估
    ↓
scheduleNextScan() — 启动轮询 + 设置下次扫描闹钟
```

### 2.2 轮询机制

两次扫描之间，按 `pollInterval`（默认10s）定期向后端请求评分更新：

```
scheduleNextScan()
    ↓
pollRunnable 每 pollInterval 执行 queryScoresOnly()
    ├── 成功 → updateScoresFromResponse → 漫游评估
    └── 失败 → 降级模式漫游评估
    ↓
下次扫描时 handler.removeCallbacksAndMessages(null) 清除轮询
```

### 2.3 可配置参数

| 参数 | 默认值 | 配置位置 |
|------|--------|----------|
| 扫描间隔 | 35秒 | MainActivity 设置对话框 |
| 后端请求间隔 | 10秒 | MainActivity 设置对话框 |
| 服务器 IP | 无 | MainActivity 设置对话框 |
| 端口 | 无 | MainActivity 设置对话框 |

---

## 三、漫游日志系统

### 3.1 日志管理器

**文件**: `roaming/RoamingLogManager.kt`

**功能**:
- 单例模式管理日志文件
- 支持日志级别: D/I/W/E
- 日志文件存储: `/data/data/com.heyu.apdemo2/files/roaming_algorithm.log`
- 自动清理: 超过1MB时清空
- 同时输出到Logcat
- **实时监听**: 通过 `OnLogListener` 接口，新日志写入时通知所有监听器

### 3.2 日志查看 UI

**入口1 — 主页面 toolbar**:
- 书本图标（`ic_roaming_log`）位于漫游按钮左侧
- 点击弹出 AlertDialog 显示日志
- `MainActivity.kt` 中的 `showRoamingLog()`

**入口2 — 帮助页面**:
- "查看漫游日志" 卡片
- `HelpFragment.kt` 中的 `showRoamingLog()`

**共同特性**:
- 日志正序显示，打开时自动滚动到底部（最新日志）
- **实时更新**: 打开对话框时注册 `OnLogListener`，新日志实时追加并自动滚动
- 关闭对话框时自动移除监听器（`setOnDismissListener`）
- 支持清空日志

### 3.3 日志内容

每次漫游评估完整记录：

```
[2026-04-02 10:15:32.123] [I] 【开始扫描】
[2026-04-02 10:15:34.456] [I] 【扫描完成】发现 5 个AP
[2026-04-02 10:15:34.457] [D]   [0] CMCC-5G | RSSI: -58dBm | 加密: 是
[2026-04-02 10:15:34.458] [D]   [1] CMCC-2.4G | RSSI: -65dBm | 加密: 是
[2026-04-02 10:15:34.500] [I] 【同步成功】更新 5 个AP评分
[2026-04-02 10:15:34.501] [I] 【漫游评估开始】时间: 10:15:34
[2026-04-02 10:15:34.502] [D] 【密码过滤】排除 ChinaNet（加密但未保存密码）
[2026-04-02 10:15:34.503] [I] 【密码过滤】5 个AP → 3 个可连接AP
[2026-04-02 10:15:34.504] [I] 【当前连接】CMCC-5G, RSSI: -58dBm, 评分: 85
[2026-04-02 10:15:34.505] [I] 【AP选择算法开始】候选AP数量: 3, 游戏模式: false
[2026-04-02 10:15:34.510] [I] 【Borda过滤后】保留 3 个 AP
[2026-04-02 10:15:34.520] [I] 【Pairwise统计结果】
[2026-04-02 10:15:34.521] [I]   CMCC-5G: 平均概率=0.7200, 获胜次数=2/2, 众包评分=85
[2026-04-02 10:15:34.522] [I] 【算法推荐】最佳AP: CMCC-5G, RSSI: -58dBm, 评分: 85
[2026-04-02 10:15:34.523] [I] 【决策】当前AP已是最优，无需切换
```

---

## 四、已知限制与待优化

### 4.1 WifiNetworkSpecifier 限制 ⚠️

**问题**: `WifiNetworkSpecifier` 每次连接需要用户确认系统弹窗，无法实现完全自动的漫游切换。

**影响**: 漫游算法能正确选出最佳 AP，但切换过程需要人工干预。

**可能的替代方案**: `WifiManager.addNetworkSuggestions()` — 系统自动切换，首次仅需通知权限，但无法精确控制切换时机。

### 4.2 模型特征简化 ⚠️ 轻微

```kotlin
// ApSelectionManager.kt
val connA = apA.rssi > -90  // 简化的连接判断
val connB = apB.rssi > -90
```

使用 RSSI 阈值近似连接状态，非真实检测。

### 4.3 冷却期逻辑

- 30秒冷却期对手动切换和自动切换统一生效
- 冷却期内推荐切换只记录日志，无用户提示

### 4.4 日志持久化

- 超过 1MB 自动清空，可能丢失关键历史
- 无按日期分文件存储
- 无日志导出功能

---

## 五、关键代码位置

| 功能 | 文件 | 方法 |
|------|------|------|
| 扫描入口 | `ScanForegroundService.kt` | `executeSingleScan()` |
| 评分同步 | `ScanForegroundService.kt` | `syncScoresAndEvaluate()` |
| 轮询评分 | `ScanForegroundService.kt` | `queryScoresOnly()` |
| 漫游评估 | `ScanForegroundService.kt` | `evaluateAndTriggerRoaming()` |
| 漫游连接 | `ScanForegroundService.kt` | `triggerRoamingConnection()` |
| 密码过滤 | `ScanForegroundService.kt` | `evaluateAndTriggerRoaming()` 内 |
| 选网算法 | `ApSelectionManager.kt` | `selectBestAp()` |
| Borda排名 | `ApSelectionManager.kt` | `filterByBorda()` |
| Pairwise比较 | `ApSelectionManager.kt` | `selectByPairwiseComparison()` |
| 日志管理 | `RoamingLogManager.kt` | `log()`, `addListener()` |
| 日志查看(主页) | `MainActivity.kt` | `showRoamingLog()` |
| 日志查看(帮助) | `HelpFragment.kt` | `showRoamingLog()` |
| Specifier连接 | `ScanForegroundService.kt` | `connectWithSpecifier()` |

---

## 六、文档更新记录

| 日期 | 更新内容 |
|------|----------|
| 2025-04-02 | 初始版本，分析漫游算法框架和已知漏洞 |
| 2026-04-02 | 更新：扫描简化为单次模式，漫游评估增加密码过滤和降级模式 |
| 2026-04-02 | 更新：日志系统增加实时监听，入口移至主页面 toolbar |
| 2026-04-02 | 更新：扫描间隔（默认35s）和后端请求间隔（默认10s）独立可配置 |
