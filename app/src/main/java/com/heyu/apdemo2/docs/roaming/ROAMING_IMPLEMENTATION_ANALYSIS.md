# 漫游算法与日志系统实现分析报告

## 概述

本文档分析 APdemo2 项目中漫游自动切换算法和漫游日志系统的当前实现状态。

---

## 一、漫游算法实现

### 1.1 核心组件

| 组件 | 文件路径 | 职责 |
|------|----------|------|
| `ApSelectionManager` | `roaming/ApSelectionManager.kt` | 漫游选网主逻辑，Borda排名+Pairwise比较 |
| `ApRoamingModel` | `roaming/ApRoamingModel.kt` | ONNX模型推理，加载LightGBM模型（Video Only版本） |
| `ApPairwisePredictor` | `roaming/ApPairwisePredictor.kt` | 预测器接口，解耦模型实现 |
| `RoamingLogManager` | `roaming/RoamingLogManager.kt` | 漫游算法执行日志管理（含实时监听） |

### 1.2 模型特征（Video Only 版本）

**模型文件**: `assets/ap_roaming_model_video.onnx`

**特征维度**: 10 维（从 13 维简化，移除 biz_type 等通用业务特征）

| 序号 | 特征名 | 说明 | 归一化方式 |
|------|--------|------|-----------|
| 0 | `rssi_a` | AP A 的 RSSI 值 | `(rssi - (-90)) / 60` → [0, 1] |
| 1 | `rssi_b` | AP B 的 RSSI 值 | `(rssi - (-90)) / 60` → [0, 1] |
| 2 | `rssi_diff` | RSSI 差值 | `rssi_a - rssi_b` |
| 3 | `score_a` | AP A 众包评分 | `score / 100` → [0, 1] |
| 4 | `score_b` | AP B 众包评分 | `score / 100` → [0, 1] |
| 5 | `score_diff` | 评分差值 | `score_a - score_b` |
| 6 | `prod_a` | AP A 综合特征 | `rssi_a * score_a` |
| 7 | `prod_b` | AP B 综合特征 | `rssi_b * score_b` |
| 8 | `a_conn` | AP A 连接状态 | 0 或 1 |
| 9 | `b_conn` | AP B 连接状态 | 0 或 1 |

**标准化参数**: 使用训练时计算的 `SCALER_MEAN` 和 `SCALER_SCALE` 进行 StandardScaler 变换

**转换工具**: `docs/new_ap_selection/convert_to_onnx.py` 生成 ONNX 并打印标准化参数

**Python 推理脚本**: `docs/new_ap_selection/infer.py` 支持批量 CSV 推理

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
Pairwise比较 (LightGBM模型)
    ├── 对每对AP进行模型推理
    ├── 计算平均概率和获胜次数
    └── 按胜率/胜场/评分综合排序
    ↓
RSSI顺延选择（ML模式新增）
    ├── 跳过 RSSI < -80 dBm 的AP
    └── 选择第一个 RSSI ≥ -80 dBm 的AP
    ↓
决策是否切换
    ├── 当前已是最优 → 跳过
    ├── 冷却期内 (默认5秒，可配置) → 跳过
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
    // 使用无障碍服务实现后台自动切换
    val a11y = WifiAccessibilityService.getInstance()
    a11y?.connectFromBackground(targetAp.ssid, password, isOpen, callback)
}
```

漫游和手动连接均使用 `WifiAccessibilityService` 无障碍服务完成实际系统切换。

### 1.6 评分漫游选择策略

`selectBestApByScore()` 方法使用以下选择逻辑：

```
候选AP列表
    ↓
过滤：无评分 AP → 排除
    ↓
过滤：RSSI < -70 dBm → 排除（如全部排除则降级选择评分最高）
    ↓
按评分降序排序
    ↓
取前两名比较
    ├── 分差 ≤ 10 → 选 RSSI 更高的
    └── 分差 > 10 → 选第一名
```

**参数说明**:
| 参数 | 值 | 说明 |
|------|-----|------|
| `RSSI_CANDIDATE_THRESHOLD` | -70 dBm | 低于此值的候选直接过滤 |
| `SCORE_GAP_THRESHOLD` | 10 | 分差阈值，≤10时比较RSSI |

**设计理由**:
- RSSI < -70 dBm 的信号质量差，连接体验不佳
- 分差 ≤ 10 说明评分接近，此时信号强度更关键
- 分差 > 10 说明评分差距明显，优先选评分高的

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

### 4.2 Android Wi-Fi Scan Throttling（扫描节流）⚠️

**问题**: Android 9+（API 28）系统对前台应用限制每 2 分钟最多 4 次 `wifiManager.startScan()` 调用。超出后扫描请求被系统静默拒绝（`startScan()` 返回 `false`）。

**影响**:
- 默认 35 秒扫描间隔在节流环境下理论上不会触发（2 分钟内约 3.4 次），但实际系统行为可能更严格
- 扫描被节流时，`WifiScanner` 回退到缓存结果（`scanResults`），RSSI 数据不够新鲜
- 日志中会出现 "Scan throttled, using cached results"，影响漫游决策实时性

**代码处理**:
```kotlin
// WifiScanner.kt
val startSuccess = wifiManager.startScan()
if (!startSuccess) {
    Log.w(TAG, "Scan throttled, using cached results")
    // 回退到缓存结果
}
```

**解除方式（仅开发/测试环境）**:
设置 → 开发者选项 → 关闭 "Wi-Fi scan throttling"

**生产环境建议**:
普通用户设备默认开启节流，不可依赖用户手动关闭。需在代码层面接受扫描频率上限，被节流时增加智能退避或依赖缓存数据。

### 4.3 模型特征简化 ⚠️ 轻微

```kotlin
// ApSelectionManager.kt
val connA = apA.rssi > -90  // 简化的连接判断
val connB = apB.rssi > -90
```

使用 RSSI 阈值近似连接状态，非真实检测。

### 4.4 StandardScaler 参数 ✅ 已修复 (2026-04-14)

**原问题**: Android 端 `ApRoamingModel.kt` 中的 StandardScaler 参数（`SCALER_MEAN` 和 `SCALER_SCALE`）使用了占位值，而非训练时真实计算的值，导致特征标准化错误，模型预测不准确。

**修复方案**: 运行 `docs/new_ap_selection/convert_to_onnx.py` 获取真实参数并更新到 Android 代码。

**Video Only 模型参数**（10维特征）：
```kotlin
// 特征顺序: ['rssi_a', 'rssi_b', 'rssi_diff', 'score_a', 'score_b', 'score_diff', 'prod_a', 'prod_b', 'a_conn', 'b_conn']
SCALER_MEAN = doubleArrayOf(
    0.389, 0.389, 0.0,   // rssi_a, rssi_b, rssi_diff
    0.657, 0.657, 0.0,   // score_a, score_b, score_diff
    0.256, 0.256,        // prod_a, prod_b
    0.675, 0.675         // a_conn, b_conn
)
SCALER_SCALE = doubleArrayOf(
    0.235, 0.235, 0.392,
    0.219, 0.219, 0.315,
    0.173, 0.173,
    0.469, 0.469
)
```

**注意**: 上述参数为示例值，实际值请以 `convert_to_onnx.py` 打印为准。

### 4.5 冷却期逻辑

- 冷却期默认 **5秒**（用户可在设置中配置），对手动切换和自动切换统一生效
- 冷却期内推荐切换只记录日志，无用户提示
- 记录时间戳：`lastRoamingSwitchTime = System.currentTimeMillis()`

### 4.6 日志持久化

- 超过 1MB 自动清空，可能丢失关键历史
- 无按日期分文件存储

### 4.7 本地 AP 性能缓存 ✅ 新增 (2026-04-05)

**功能**: 纯本地缓存用户实际连接过的 AP 性能数据，用于辅助选网决策。

**实现文件**:
- `ApPerformanceCache.kt` - 性能缓存管理器
- `ApPerformanceMonitor.kt` - 后台监控服务

**采集指标**:
- 下行速率 (KB/s) - 通过 `TrafficStats.getTotalRxBytes()` 差值计算
- 上行速率 (KB/s) - 通过 `TrafficStats.getTotalTxBytes()` 差值计算
- RSSI 信号强度
- Ping 延迟 (ms)
- 实际连接状态（是否有有效数据传输）

**使用方式**:
1. 选网时优先查询本地缓存
2. 如果有 2+ AP 的历史数据，直接使用缓存评分排序
3. 否则回退到 LightGBM 模型进行 Pairwise 比较

**评分公式**:
```
综合评分 = 0.4 * 吞吐量评分 + 0.3 * 延迟评分 + 0.3 * RSSI评分
```

**限制**:
- 只能采集已连接 AP 的性能
- 无法获取未连接 AP 的流量/延迟
- 首次使用时无历史数据

**生命周期管理**:
- 随 `MainActivity` 自动启动：`onStart()` → `ApPerformanceMonitor.start()`
- 随 `MainActivity` 自动停止：`onStop()` → `ApPerformanceMonitor.stop()`
- 需在 `AndroidManifest.xml` 中声明服务

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
| 无障碍服务连接 | `ScanForegroundService.kt` | `triggerRoamingConnection()` 内调用 `WifiAccessibilityService` |
| 性能缓存 | `ApPerformanceCache.kt` | `sample()`, `getPerformanceSummary()` |
| 性能监控服务 | `ApPerformanceMonitor.kt` | `startMonitoring()`, `sampleCurrentAp()` |
| 监控服务生命周期 | `MainActivity.kt` | `startAndBindService()` 中启动, `onStop()` 中停止 |

---

## 六、文档更新记录

| 日期 | 更新内容 |
|------|----------|
| 2025-04-02 | 初始版本，分析漫游算法框架和已知漏洞 |
| 2026-04-02 | 更新：扫描简化为单次模式，漫游评估增加密码过滤和降级模式 |
| 2026-04-02 | 更新：日志系统增加实时监听，入口移至主页面 toolbar |
| 2026-04-02 | 更新：扫描间隔（默认35s）和后端请求间隔（默认10s）独立可配置 |
| 2026-04-05 | 新增：本地 AP 性能缓存系统 (ApPerformanceCache + ApPerformanceMonitor) |
| 2026-04-14 | 更新：Video Only 模型（10维特征），标准化参数通过 convert_to_onnx.py 生成 |
| 2026-04-18 | 更新：评分漫游增加 RSSI 过滤（-70dBm）和分差阈值判断（≤10选RSSI高者） |
| 2026-04-20 | 更新：ML漫游增加 RSSI 顺延选择，跳过 RSSI < -80 dBm 的AP |
| 2026-06-11 | 新增：Android Wi-Fi Scan Throttling 扫描节流限制说明 |
| 2026-06-11 | 修正：冷却期默认时间为 5 秒（可配置），原文档误写为 30 秒 |
| 2026-06-11 | 修正：连接触发方式更新为无障碍服务，移除过时的 WifiNetworkSpecifier 引用 |
| 2026-06-11 | 删除：导出漫游日志功能（移除 UI 菜单项、代码实现和 HTTP 接口） |
