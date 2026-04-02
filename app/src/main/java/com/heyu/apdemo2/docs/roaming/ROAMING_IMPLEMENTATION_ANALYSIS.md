# 漫游算法与日志系统实现分析报告

## 概述

本文档分析 APdemo2 项目中漫游自动切换算法和漫游日志系统的当前实现，并指出存在的关键漏洞。

---

## 一、漫游算法实现

### 1.1 核心组件

| 组件 | 文件路径 | 职责 |
|------|----------|------|
| `ApSelectionManager` | `roaming/ApSelectionManager.kt` | 漫游选网主逻辑，Borda排名+Pairwise比较 |
| `ApRoamingModel` | `roaming/ApRoamingModel.kt` | ONNX模型推理，加载LightGBM模型 |
| `ApPairwisePredictor` | `roaming/ApPairwisePredictor.kt` | 预测器接口，解耦模型实现 |
| `RoamingLogManager` | `roaming/RoamingLogManager.kt` | 漫游算法执行日志管理 |

### 1.2 算法流程

```
扫描周期开始
    ↓
获取候选AP列表
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
    ├── 检查冷却期 (30秒)
    ├── 检查当前是否已是最优
    └── 触发连接切换
```

### 1.3 当前实现代码位置

**触发点**: `ScanForegroundService.kt:415-419`

```kotlin
if (autoRoamingEnabled) {
    evaluateAndTriggerRoaming()
} else {
    roamingLogManager.i("【自动漫游已关闭】跳过漫游评估")
}
```

**评估逻辑**: `ScanForegroundService.kt:425-482`

```kotlin
private fun evaluateAndTriggerRoaming() {
    // 1. 获取当前AP和候选AP
    // 2. 调用ApSelectionManager.selectBestAp()
    // 3. 决策逻辑（冷却期、是否最优等）
    // 4. 触发triggerRoamingConnection()
}
```

**连接触发**: `ScanForegroundService.kt:487-522`

```kotlin
private fun triggerRoamingConnection(targetAp: AccessPoint) {
    // 获取密码
    // 调用wifiConnector.connect()
    // 回调处理成功/失败/重试
}
```

---

## 二、漫游日志系统实现

### 2.1 日志管理器

**文件**: `roaming/RoamingLogManager.kt`

**功能**:
- 单例模式管理日志文件
- 支持日志级别: D/I/W/E
- 日志文件存储: `/data/data/com.heyu.apdemo2/files/roaming_algorithm.log`
- 自动清理: 超过1MB时清空
- 同时输出到Logcat

**日志内容示例**:
```
[2025-04-02 10:15:32.123] [I] ============================================================
[2025-04-02 10:15:32.124] [I] 【漫游评估开始】时间: 10:15:32
[2025-04-02 10:15:32.125] [I] 【当前连接】CMCC-5G, RSSI: -65dBm, 评分: 85
[2025-04-02 10:15:32.126] [D] 【候选AP概览】
[2025-04-02 10:15:32.127] [D]   CMCC-5G | RSSI: -65dBm | 评分: 85 ← 当前
[2025-04-02 10:15:32.128] [D]   CMCC-2.4G | RSSI: -58dBm | 评分: 72
[2025-04-02 10:15:32.129] [I] 【AP选择算法开始】候选AP数量: 2, 游戏模式: false
...
```

### 2.2 日志查看UI

**文件**: `ui/HelpFragment.kt:41-70`

**功能**:
- 在"帮助"页面提供"查看漫游日志"入口
- 点击后弹出AlertDialog显示日志内容
- 支持清空日志
- 日志倒序显示（最新在上）

---

## 三、关键漏洞分析

### 漏洞1: 漫游自动连接未真正打通 ⚠️ 严重

**问题描述**:

当前代码中，`evaluateAndTriggerRoaming()` 虽然会被调用，但存在以下问题：

1. **连接回调未正确同步状态**
   - `triggerRoamingConnection` 中的回调只更新了 `currentConnectedSsid`
   - 但系统WiFi状态变化依赖 `WifiFragment` 中的广播接收器
   - 两者状态可能不一致

2. **WifiConnector vs Specifier连接混淆**
   - 漫游算法调用的是 `wifiConnector.connect()` (第499行)
   - 但用户手动连接使用的是 `connectWithSpecifier()` (在WifiFragment中)
   - 两种方式状态管理不一致

3. **currentConnectedSsid 更新不可靠**
   ```kotlin
   // ScanForegroundService.kt:504
   onConnected = {
       currentConnectedSsid = targetAp.ssid  // 仅在这里更新
       ...
   }
   ```
   - 如果连接成功但回调未触发，状态就不同步
   - 没有监听系统WiFi状态广播来校正

4. **漫游触发后无状态确认**
   - 触发连接后没有等待实际切换成功的确认
   - 如果切换失败，算法不会收到反馈

**代码位置**: `ScanForegroundService.kt:487-522`

### 漏洞2: 漫游日志显示不完整 ⚠️ 中等

**问题描述**:

1. **日志查看入口隐蔽**
   - 日志查看放在"帮助"页面，用户不易发现
   - 没有实时日志流显示

2. **日志内容不完整**
   - 只记录了算法执行过程
   - 缺少关键信息：
     - 实际连接切换结果（成功/失败）
     - 切换耗时
     - 切换原因（信号差/评分高等）
     - 拒绝切换的原因（冷却期/已最优等）

3. **日志持久化问题**
   - 超过1MB自动清空，可能丢失关键历史
   - 没有按日期分文件存储

4. **无日志导出功能**
   - 无法分享或导出日志用于调试

### 漏洞3: 算法与连接层耦合问题 ⚠️ 中等

**问题描述**:

1. **模型推理特征不完整**
   - `ApPairwisePredictor.predict()` 接口接收很多参数
   - 但实际调用时部分参数是硬编码的简化逻辑：
     ```kotlin
     // ApSelectionManager.kt:192-193
     val connA = apA.rssi > -90  // 简化的连接判断
     val connB = apB.rssi > -90
     ```

2. **缺少连接质量反馈闭环**
   - 算法选出AP并连接后，没有收集实际连接质量反馈
   - 无法验证算法选择是否正确
   - 无法基于实际体验优化模型

### 漏洞4: 冷却期逻辑缺陷 ⚠️ 轻微

**问题描述**:

```kotlin
// ScanForegroundService.kt:463-468
if (now - lastRoamingTime < ROAMING_COOLDOWN_MS) {
    val remaining = (ROAMING_COOLDOWN_MS - (now - lastRoamingTime)) / 1000
    roamingLogManager.i("【决策】推荐切换到 ${bestAp.ssid}，但处于冷却期（剩余 ${remaining}s）")
    roamingLogManager.i("=".repeat(60))
    return
}
```

- 冷却期只记录日志，没有给用户任何提示
- 如果用户手动切换后，冷却期可能阻止必要的自动漫游
- 没有区分"手动切换"和"自动切换"的冷却策略

---

## 四、需要完善的功能

### 4.1 高优先级

| 功能 | 描述 | 影响文件 |
|------|------|----------|
| 统一连接管理 | 漫游和手动连接使用同一套Specifier连接机制 | `ScanForegroundService.kt` |
| 状态同步修复 | 通过广播监听系统WiFi状态，确保currentConnectedSsid准确 | `ScanForegroundService.kt` |
| 连接结果反馈 | 漫游触发后确认实际切换成功/失败，并记录日志 | `ScanForegroundService.kt` |

### 4.2 中优先级

| 功能 | 描述 | 影响文件 |
|------|------|----------|
| 日志增强 | 增加切换结果、耗时、原因等关键信息 | `ScanForegroundService.kt`, `RoamingLogManager.kt` |
| 日志UI优化 | 增加实时日志显示或独立日志页面 | `HelpFragment.kt` 或新页面 |
| 日志导出 | 支持分享/导出日志文件 | `RoamingLogManager.kt`, `HelpFragment.kt` |

### 4.3 低优先级

| 功能 | 描述 | 影响文件 |
|------|------|----------|
| 连接质量反馈 | 收集连接后的实际质量数据用于算法优化 | 新增模块 |
| 冷却期策略优化 | 区分手动/自动切换的冷却策略 | `ScanForegroundService.kt` |
| 日志分文件存储 | 按日期分文件，避免自动清空 | `RoamingLogManager.kt` |

---

## 五、关键代码片段

### 5.1 漫游评估入口

```kotlin
// ScanForegroundService.kt:396-420
private fun updateScoresFromResponse(response: ScanResponse) {
    // ... 更新评分 ...
    
    if (autoRoamingEnabled) {
        evaluateAndTriggerRoaming()
    } else {
        roamingLogManager.i("【自动漫游已关闭】跳过漫游评估")
    }
}
```

### 5.2 漫游决策逻辑

```kotlin
// ScanForegroundService.kt:425-482
private fun evaluateAndTriggerRoaming() {
    val now = System.currentTimeMillis()
    roamingLogManager.i("【漫游评估开始】...")
    
    // 1. 获取当前AP
    val currentAp = currentAccessPoints.find { it.ssid == currentConnectedSsid }
    
    // 2. 选择最佳AP
    val bestAp = apSelectionManager.selectBestAp(currentAccessPoints, isGameMode = false)
    
    // 3. 决策逻辑
    if (bestAp.ssid == currentConnectedSsid) {
        roamingLogManager.i("【决策】当前AP已是最优，无需切换")
        return
    }
    
    if (now - lastRoamingTime < ROAMING_COOLDOWN_MS) {
        roamingLogManager.i("【决策】处于冷却期，暂不切换")
        return
    }
    
    // 4. 触发切换
    roamingLogManager.i("【决策】执行漫游: ...")
    triggerRoamingConnection(bestAp)
    lastRoamingTime = now
}
```

### 5.3 漫游连接触发

```kotlin
// ScanForegroundService.kt:487-522
private fun triggerRoamingConnection(targetAp: AccessPoint) {
    val password = PasswordStore.get(this, targetAp.ssid) ?: ""
    val isOpen = !targetAp.isSecured()
    
    if (!isOpen && password.isEmpty()) {
        roamingLogManager.w("【切换失败】需要密码但未保存")
        return
    }
    
    wifiConnector.connect(
        ssid = targetAp.ssid,
        isOpen = isOpen,
        password = password,
        onConnected = { ... },
        onFailed = { ... },
        ...
    )
}
```

---

## 六、总结

当前漫游算法框架已经搭建完成，包括：
- ✅ Borda排名过滤
- ✅ Pairwise模型比较
- ✅ 自动漫游开关
- ✅ 基础日志记录
- ✅ 日志查看UI

**但关键流程未真正打通**：
- ❌ 漫游触发后的连接状态同步不可靠
- ❌ 手动连接和自动漫游使用两套连接机制
- ❌ 缺少连接结果确认和反馈
- ❌ 日志信息不完整，缺少关键决策结果

**建议优先修复**:
1. 统一使用Specifier连接作为唯一连接方式
2. 增加系统WiFi状态广播监听，确保状态同步
3. 完善日志，记录每次评估的完整结果（包括拒绝原因）
4. 增加漫游切换成功/失败的确认机制
