# WiFi 连接实现细节文档

> 本文档详细说明 APdemo2 项目中 WiFi 连接的实现方式，特别是 `WifiNetworkSpecifier` 的使用细节。
> 
> **更新记录**:
> - 2026-04-02: 初始版本，标记废弃模块
> - 2026-04-02: 已删除 Method2ActionWifiAddNetworks、WifiAutoConnectService，统一使用 Specifier

---

## 1. 当前主要连接方式：WifiNetworkSpecifier

### 1.1 实现位置
- **核心代码**: `service/ScanForegroundService.kt` (第 655-748 行)
- **UI 调用**: `ui/WifiFragment.kt` (第 233-282 行)

### 1.2 工作原理

```kotlin
// 构建 Specifier
val specifier = WifiNetworkSpecifier.Builder()
    .setSsid(ssid)
    .apply { if (!isOpen) setWpa2Passphrase(password) }
    .build()

// 构建 NetworkRequest
val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)  // 关键：移除 INTERNET 能力
    .setNetworkSpecifier(specifier)
    .build()

// 发起请求
connectivityManager.requestNetwork(request, callback)
```

### 1.3 关键特性

| 特性 | 说明 |
|------|------|
| API 级别 | Android 10+ (API 29+) |
| 连接类型 | 应用级本地连接（非系统级切换） |
| 用户交互 | 系统弹窗请求用户确认 |
| 流量绑定 | 通过 `bindProcessToNetwork()` 将应用流量绑定到该网络 |
| 系统 WiFi 状态 | **不会改变**系统状态栏的 WiFi 连接状态 |

### 1.4 连接状态分类

```kotlin
// ScanForegroundService.kt:686-688
val caps = connectivityManager.getNetworkCapabilities(network)
isSystemWifiConnection = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
```

- **系统级连接** (`[系统]`): 网络具有 `NET_CAPABILITY_INTERNET` 和 `NET_CAPABILITY_VALIDATED` 能力
- **本地连接** (`[本地]`): 仅应用内可用，系统状态栏不显示已连接

### 1.5 生命周期管理

```kotlin
// 释放连接时必须执行以下操作：
1. unregisterNetworkCallback(callback)  // 注销回调
2. bindProcessToNetwork(null)           // 解绑进程网络
3. specifierConnectedSsid = null        // 清空状态
```

---

## 2. 已删除的干扰模块

以下模块已被删除，不再存在于代码库中：

### ✅ 2.1 已删除：Method2ActionWifiAddNetworks

**原文件位置**: `connection/Method2ActionWifiAddNetworks.kt`

**删除原因**:
- 使用 `ACTION_WIFI_ADD_NETWORKS` Intent 弹出系统对话框
- 与 Specifier 方式混用会导致连接状态混乱
- 代码复杂，包含大量 fallback 逻辑

**删除时间**: 2026-04-02

---

### ✅ 2.2 已删除：WifiAutoConnectService

**原文件位置**: `connection/WifiAutoConnectService.kt`

**删除原因**:
- 无障碍服务与 Specifier 方式不兼容
- Specifier 不需要打开系统设置页面

**删除时间**: 2026-04-02

---

### ⚠️ 2.3 已简化：WifiConnector

**文件位置**: `connection/WifiConnector.kt`

**当前状态**:
- 已移除 `WifiNetworkSuggestion` 相关代码
- 仅保留 Android 9 及以下传统连接方式
- Android 10+ 直接返回错误，提示使用 Specifier

**使用警告**:
```kotlin
// Android 10+ 上会直接失败
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    onFailed("Android 10+ 请使用 WifiNetworkSpecifier 方式连接")
    return
}
```

---

## 3. 正确的调用链

### 3.1 用户点击 WiFi 列表项后的流程

```
WifiFragment.handleApClick()
    ↓
WifiFragment.initiateConnect()  // 检查 Android 10+
    ↓
ScanForegroundService.connectWithSpecifier()  // 核心连接方法
    ↓
ConnectivityManager.requestNetwork()  // 系统 API
    ↓
NetworkCallback.onAvailable()  // 连接成功回调
    ↓
bindProcessToNetwork(network)  // 绑定应用流量
    ↓
SpecifierConnectionCallback.onConnected()  // 通知 UI
```

### 3.2 断开连接流程

```
WifiFragment.handleApClick() (已连接状态点击)
    ↓
ScanForegroundService.releaseSpecifierConnection()
    ↓
unregisterNetworkCallback()
bindProcessToNetwork(null)
    ↓
SpecifierConnectionCallback.onLost()  // 通知 UI
```

---

## 4. 开发注意事项

### 4.1 禁止的操作

| 操作 | 原因 |
|------|------|
| ❌ 调用 `WifiConnector.connect()` | 使用废弃的 Suggestion API |
| ❌ 调用 `Method2ActionWifiAddNetworks.connect()` | 会弹出系统对话框，干扰用户体验 |
| ❌ 调用 `WifiAutoConnectService` | 无障碍服务与 Specifier 不兼容 |
| ❌ 混用 `WifiConfiguration` API | Android 10+ 已废弃 |
| ❌ 直接修改 `specifierConnectedSsid` | 应通过 `releaseSpecifierConnection()` 管理 |

### 4.2 推荐的操作

| 操作 | 说明 |
|------|------|
| ✅ 使用 `ScanForegroundService.connectWithSpecifier()` | 当前标准连接方式 |
| ✅ 使用 `ScanForegroundService.releaseSpecifierConnection()` | 正确释放连接 |
| ✅ 监听 `SpecifierConnectionCallback` | 获取连接状态变更 |
| ✅ 检查 `isSystemWifiConnection` | 区分系统级和本地连接 |

### 4.3 调试技巧

```kotlin
// 查看当前连接状态
val (ssid, isSystem) = service.getSpecifierConnectionInfo()
Log.d(TAG, "当前连接: $ssid, 系统级: $isSystem")

// 检查系统 WiFi 状态（与 Specifier 独立）
val systemSsid = getSystemConnectedSsid()  // WifiFragment:167
```

---

## 5. 代码清理状态

### 5.1 已完成的清理

| 文件 | 操作 | 状态 |
|------|------|------|
| `Method2ActionWifiAddNetworks.kt` | 删除 | ✅ 已完成 |
| `WifiAutoConnectService.kt` | 删除 | ✅ 已完成 |
| `WifiConnector.kt` | 简化 | ✅ 已完成 |
| `ScanForegroundService.kt` | 统一使用 Specifier | ✅ 已完成 |
| `AndroidManifest.xml` | 移除无障碍服务声明 | ✅ 已完成 |

### 5.2 统一后的连接机制

```kotlin
// 手动连接（WifiFragment）
initiateConnect() → service.connectWithSpecifier()

// 自动漫游（ScanForegroundService）
triggerRoamingConnection() → connectWithSpecifier()
```

两者现在使用完全相同的连接机制。

---

## 6. 相关文档链接

- `docs/PROJECT_DOCS.md` - 项目整体架构
- `docs/http_frontend.md` - HTTP API 接口文档
- `diagnostic/WiFiConnectionDiagnostic.kt` - WiFi 连接诊断工具

---

## 7. 版本历史

| 日期 | 变更 |
|------|------|
| 2026-04-02 | 创建本文档，整理 Specifier 实现细节和废弃模块 |
