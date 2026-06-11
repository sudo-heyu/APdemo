# WiFi 连接实现细节文档

> 本文档详细说明 APdemo2 项目中 WiFi 连接的实现方式，当前使用 **无障碍服务（AccessibilityService）** 实现系统级 WiFi 切换。
>
> **更新记录**:
> - 2026-04-02: 初始版本，标记废弃模块
> - 2026-04-02: 已删除 Method2ActionWifiAddNetworks、WifiAutoConnectService，统一使用 Specifier
> - 2026-04-02: 扫描简化为单次模式，漫游评估增加密码过滤和降级模式
> - **2026-06-11: 重大变更：连接方式从 WifiNetworkSpecifier 全面迁移为无障碍服务（AccessibilityService），实现真正的系统级 WiFi 切换**

---

## 1. 当前主要连接方式：无障碍服务（AccessibilityService）

### 1.1 实现位置

- **核心服务**: `service/WifiAccessibilityService.kt`
- **连接准备**: `service/ScanForegroundService.kt`（`connectToNetwork()` 方法）
- **UI 调用**: `ui/WifiFragment.kt`（`initiateConnect()` / `checkA11yAndConnect()` 方法）
- **漫游触发**: `service/ScanForegroundService.kt`（`triggerRoamingConnection()` 方法）

### 1.2 工作原理

无障碍服务通过监听系统 WiFi 设置页面的界面节点，自动识别目标 SSID、自动输入密码并点击连接按钮，实现真正的系统级 WiFi 切换。

#### 手动连接流程

```
用户点击 AP
    ↓
checkA11yAndConnect() — 检查无障碍服务是否开启
    ↓ 未开启 → 弹窗引导用户前往系统设置开启
    ↓ 已开启
connectToNetwork() — ScanForegroundService 准备连接目标
    ↓
prepareManualConnect() — WifiAccessibilityService 注册广播、设置状态
    ↓
WifiFragment 打开系统 WiFi 设置页面（ACTION_WIFI_SETTINGS）
    ↓
onAccessibilityEvent() — 无障碍服务自动查找目标 SSID
    ↓
tryClickSsid() — 自动点击目标网络
    ↓
handlePasswordDialog() — 如有密码，自动输入并点击"连接"按钮
    ↓
WiFi 广播监听（NETWORK_STATE_CHANGED_ACTION）
    ↓
确认连接成功 → onConnected() 回调 → 返回应用
```

#### 自动漫游流程

```
漫游评估触发
    ↓
triggerRoamingConnection() — ScanForegroundService
    ↓
connectFromBackground() — WifiAccessibilityService 自行打开 WiFi 设置页面
    ↓
无障碍服务自动完成 SSID 点击、密码输入、连接确认
    ↓
WiFi 广播监听确认连接
    ↓
onConnected() 回调 → 自动返回应用（returnToApp）
```

### 1.3 核心代码

```kotlin
// WifiAccessibilityService 公共 API

// 1. 手动连接准备（Fragment 负责打开 WiFi 设置页面）
fun prepareManualConnect(
    ssid: String,
    password: String?,
    isOpen: Boolean,
    callback: ConnectionCallback
)

// 2. 后台自动漫游（服务自己打开 WiFi 设置页面）
fun connectFromBackground(
    ssid: String,
    password: String?,
    isOpen: Boolean,
    callback: ConnectionCallback
)

// 3. 检查无障碍服务是否已开启
fun isEnabled(context: Context): Boolean
```

### 1.4 关键特性

| 特性 | 说明 |
|------|------|
| 连接类型 | **系统级切换**（改变系统 WiFi 连接状态，非应用级） |
| 用户交互 | 无需手动点击（自动识别并点击 SSID、自动输入密码） |
| 前置条件 | 必须在系统设置中手动开启无障碍服务 |
| 流量绑定 | 系统级连接，无需 bindProcessToNetwork() |
| 系统 WiFi 状态 | **会改变**系统状态栏的 WiFi 连接状态 |
| 连接确认 | 通过 `NETWORK_STATE_CHANGED_ACTION` 广播确认 |

### 1.5 超时与保底机制

```kotlin
// 各阶段超时配置
private const val TOTAL_TIMEOUT_MS = 20_000L   // 总超时：20秒
private const val FORCE_RETURN_MS  = 5_000L    // 点击 SSID 后 5 秒强制返回保底
private const val MAX_RETRIES      = 20         // SSID 查找最大重试次数
private const val RETRY_MS         = 300L       // 重试间隔
```

| 超时类型 | 触发条件 | 结果 |
|----------|----------|------|
| **总超时** | 20 秒内未收到任何连接确认 | 回调 `onFailed("Connection timeout")` |
| **强制返回** | 点击 SSID 后 5 秒 | 回调 `onConnected()`（保底返回，不保证成功） |
| **SSID 查找** | 重试 20 次仍未找到目标 SSID | 回调 `onFailed("Network not found")` |

### 1.6 生命周期管理

```kotlin
// 释放连接时必须执行以下操作：
1. wifiAccessibilityService.cancel()  // 取消当前连接任务，重置状态
2. ScanForegroundService.disconnectPinned()  // 清除本地状态
```

**注意**: 无障碍服务执行的是真正的系统 WiFi 切换，应用代码无法强制断开系统已建立的 WiFi 连接，只能清除本地追踪的状态。

---

## 2. 漫游连接机制

### 2.1 统一连接

漫游和手动连接最终都依赖 `WifiAccessibilityService`，但入口不同：

```kotlin
// 手动连接（WifiFragment）
handleApClick() → checkA11yAndConnect() → initiateConnect()
    → ScanForegroundService.connectToNetwork()
    → WifiAccessibilityService.prepareManualConnect()
    → WifiFragment 打开系统 WiFi 设置

// 自动漫游（ScanForegroundService）
triggerRoamingConnection()
    → WifiAccessibilityService.connectFromBackground()  // 自行打开 WiFi 设置
```

### 2.2 漫游前的密码过滤

漫游评估前会过滤掉不可连接的 AP：

```kotlin
val connectableAps = currentAccessPoints.filter { ap ->
    if (!ap.isSecured()) true  // 开放网络
    else !PasswordStore.get(this, ap.ssid).isNullOrEmpty()  // 已存密码
}
```

只有可连接的 AP 才会传入选网算法，避免算法选出无法连接的 AP。

---

## 3. 已废弃/删除的模块

以下模块已不再使用：

### ❌ 3.1 已删除：Method2ActionWifiAddNetworks

**原文件位置**: `connection/Method2ActionWifiAddNetworks.kt`

**删除原因**:
- 使用 `ACTION_WIFI_ADD_NETWORKS` Intent 弹出系统对话框
- 与后续方案混用会导致连接状态混乱

**删除时间**: 2026-04-02

### ❌ 3.2 已删除：WifiAutoConnectService

**原文件位置**: `connection/WifiAutoConnectService.kt`

**删除原因**:
- 早期无障碍服务实现，功能已合并进 `WifiAccessibilityService`

**删除时间**: 2026-04-02

### ❌ 3.3 已废弃：WifiNetworkSpecifier（Specifier 方式）

**原实现位置**: `service/ScanForegroundService.kt`（`connectWithSpecifier()` 方法）

**废弃原因**:
- `WifiNetworkSpecifier` 是**应用级连接**，不会改变系统 WiFi 状态
- 每次连接需要用户手动确认系统弹窗，无法实现自动漫游
- 非系统级切换，用户体验不完整

**废弃时间**: 2026-06-11

### ⚠️ 3.4 已废弃：WifiConnector

**文件位置**: `connection/WifiConnector.kt`

**当前状态**:
- 已移除 `WifiNetworkSuggestion` 相关代码
- 仅保留 Android 9 及以下传统连接方式的空壳
- 代码中已注释说明请使用无障碍服务

---

## 4. 正确的调用链

### 4.1 用户点击 WiFi 列表项后的流程

```
WifiFragment.handleApClick()
    ↓
WifiFragment.checkA11yAndConnect()  // 检查无障碍服务是否开启
    ↓ 未开启
AlertDialog 引导用户前往系统设置开启无障碍服务
    ↓
用户返回应用后 onResume() 重试连接
    ↓ 已开启
ScanForegroundService.connectToNetwork()  // 准备连接目标
    ↓
WifiAccessibilityService.prepareManualConnect()  // 注册广播、设置状态
    ↓
WifiFragment 打开系统 WiFi 设置（ACTION_WIFI_SETTINGS）
    ↓
WifiAccessibilityService.onAccessibilityEvent()  // 无障碍事件监听
    ↓
tryClickSsid() → performClick()  // 自动点击目标 SSID
    ↓
handlePasswordDialog()  // 自动输入密码、点击"连接"按钮
    ↓
WiFi BroadcastReceiver.onReceive()  // 监听 NETWORK_STATE_CHANGED_ACTION
    ↓
NetworkInfo.DetailedState.CONNECTED 确认连接成功
    ↓
WifiAccessibilityService.onConnectionSuccess()
    ↓
ConnectionCallback.onConnected() → 通知 UI 更新
    ↓
performGlobalAction(GLOBAL_ACTION_BACK) / returnToApp() 返回应用
```

### 4.2 断开连接流程

```
WifiFragment.handleApClick() (已连接状态点击)
    ↓
ScanForegroundService.disconnectPinned()
    ↓
WifiAccessibilityService.cancel()  // 取消当前任务
    ↓
pinnedSsid = null / currentConnectedSsid = null  // 仅清除本地状态
    ↓
ConnectionCallback.onConnectionChanged(null, false)  // 通知 UI
```

**注意**: 由于无障碍服务执行的是真正的系统级连接，应用无法强制断开系统 WiFi。`disconnectPinned()` 仅清除本地追踪状态。

### 4.3 漫游连接流程

```
ScanForegroundService.triggerRoamingConnection(targetAp)
    ↓
WifiAccessibilityService.connectFromBackground(ssid, password, isOpen, callback)
    ↓
服务自行打开系统 WiFi 设置（FLAG_ACTIVITY_NEW_TASK）
    ↓
无障碍服务自动完成 SSID 点击、密码输入、连接确认
    ↓
WiFi 广播确认连接成功
    ↓
returnToApp() → performGlobalAction(GLOBAL_ACTION_BACK) 返回应用
    ↓
ConnectionCallback.onConnected() → 通知 UI + 漫游日志
```

---

## 5. 开发注意事项

### 5.1 禁止的操作

| 操作 | 原因 |
|------|------|
| ❌ 调用 `WifiConnector.connect()` | 使用废弃的传统 API |
| ❌ 混用 `WifiNetworkSpecifier` API | 已废弃，与无障碍服务冲突 |
| ❌ 直接修改 `pinnedSsid` / `currentConnectedSsid` | 应通过 `disconnectPinned()` 管理 |
| ❌ 绕过 `checkA11yAndConnect()` 直接连接 | 无障碍服务未开启时无法连接 |

### 5.2 推荐的操作

| 操作 | 说明 |
|------|------|
| ✅ 使用 `WifiFragment.checkA11yAndConnect()` | 自动检查无障碍服务并引导用户开启 |
| ✅ 使用 `ScanForegroundService.connectToNetwork()` | 手动连接的标准入口 |
| ✅ 使用 `ScanForegroundService.disconnectPinned()` | 正确清除连接状态 |
| ✅ 监听 `ScanForegroundService.ScanCallback.onConnectionChanged()` | 获取连接状态变更 |
| ✅ 检查 `WifiAccessibilityService.isEnabled()` | 判断无障碍服务是否可用 |

### 5.3 无障碍服务未开启的处理

```kotlin
// WifiFragment 中的标准处理流程
private fun checkA11yAndConnect(ssid: String, isOpen: Boolean, password: String) {
    if (WifiAccessibilityService.isEnabled(requireContext())) {
        initiateConnect(ssid, isOpen, password)
    } else {
        // 弹窗引导用户前往系统设置
        AlertDialog.Builder(requireContext())
            .setTitle("Accessibility Service Required")
            .setMessage("Please find this app in Accessibility settings and enable it.")
            .setPositiveButton("Go to Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                // 记录 pending 连接目标，onResume 返回后自动重试
                connectingSsid = ssid
                connectingPassword = password
                pendingA11yRetry = true
            }
            .show()
    }
}
```

---

## 6. 代码状态

| 文件 | 状态 | 说明 |
|------|------|------|
| `Method2ActionWifiAddNetworks.kt` | ❌ 已删除 | 早期系统弹窗方案 |
| `WifiAutoConnectService.kt` | ❌ 已删除 | 早期无障碍服务 |
| `WifiConnector.kt` | ⚠️ 已废弃 | 保留空壳，仅作兼容性占位 |
| `ScanForegroundService.kt` | ✅ 当前使用 | 使用无障碍服务 |
| `WifiAccessibilityService.kt` | ✅ 当前使用 | 无障碍自动连接服务 |
| `AndroidManifest.xml` | ✅ 已配置 | 无障碍服务声明 + accessibility_service_config |

---

## 7. 版本历史

| 日期 | 变更 |
|------|------|
| 2026-04-02 | 创建本文档，整理 Specifier 实现细节和废弃模块 |
| 2026-04-02 | 补充漫游密码过滤机制、系统对话框延迟行为说明 |
| 2026-04-05 | 修复：connectWithSpecifier() 添加 isOpen 参数，解决加密网络误判为开放网络的问题 |
| **2026-06-11** | **重大重构：连接方式从 WifiNetworkSpecifier 全面迁移为无障碍服务（WifiAccessibilityService），实现真正的系统级 WiFi 切换。移除所有 connectWithSpecifier() 相关实现和引用。** |
