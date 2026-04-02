# WiFi 连接实现细节文档

> 本文档详细说明 APdemo2 项目中 WiFi 连接的实现方式，特别是 `WifiNetworkSpecifier` 的使用细节，以及可能产生干扰的函数和废弃模块。
> 
> 最后更新：2026-04-02

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

## 2. 干扰函数与废弃模块警告

### ⚠️ 2.1 废弃：WifiNetworkSuggestion（方式一/二）

**文件位置**:
- `connection/WifiConnector.kt` (第 84-166 行)
- `connection/Method2ActionWifiAddNetworks.kt` (整个文件)

**废弃原因**:
- `WifiNetworkSuggestion` 旨在提供**系统级真实连接**
- 但首次使用需要用户在系统通知栏批准「允许 AppName 建议 WiFi」
- Android 10+ 系统限制严格，许多 OEM 设备（小米、华为、vivo）存在兼容性问题
- **与 Specifier 方式混用会导致连接状态混乱**

**禁用标记**:
```kotlin
// WifiConnector.kt:71-75
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    connectViaSuggestion(...)  // 当前代码中已不调用
} else {
    connectLegacy(...)  // Android 9 及以下
}
```

**干扰风险**: ⭐⭐⭐⭐⭐ (极高)
- 如果同时启用，Suggestion 和 Specifier 会竞争网络连接
- 导致 `onConnected` 回调多次触发
- 状态栏显示与实际连接不一致

---

### ⚠️ 2.2 废弃：WifiConfiguration.addNetwork（传统方式）

**文件位置**:
- `connection/WifiConnector.kt` (第 249-277 行)
- `connection/Method2ActionWifiAddNetworks.kt` (第 252-346 行)

**废弃原因**:
- Android 10+ (API 29) 开始，`WifiManager.addNetwork()` 返回 `-1`（失败）
- 系统禁止应用直接修改 WiFi 配置
- 需要 `CHANGE_WIFI_STATE` 权限，但在新系统上无效

**代码中的废弃标记**:
```kotlin
@Suppress("DEPRECATION")
private fun connectLegacy(...) { ... }
```

**干扰风险**: ⭐⭐⭐ (中等)
- 在 Android 10+ 上调用会静默失败
- 不会直接导致崩溃，但会误导开发者认为连接已发起

---

### ⚠️ 2.3 危险：Method2ActionWifiAddNetworks 类

**文件位置**: `connection/Method2ActionWifiAddNetworks.kt`

**危险操作**:
1. 反射调用 `WifiManager.forget()` 方法 (第 173-183 行)
2. 尝试启用已保存网络 (第 252-313 行)
3. 使用 `ACTION_WIFI_ADD_NETWORKS` Intent (第 100 行)

**干扰风险**: ⭐⭐⭐⭐⭐ (极高)
- 此类中的方法**不应被调用**
- 如果意外调用，会导致系统 WiFi 设置页面弹出
- 破坏当前 Specifier 连接状态

---

### ⚠️ 2.4 注意：WifiAutoConnectService（无障碍服务）

**文件位置**: `connection/WifiAutoConnectService.kt`

**功能说明**:
- 通过 Android 无障碍服务自动点击系统 WiFi 设置页面
- 用于在 `ACTION_WIFI_ADD_NETWORKS` 失败后自动输入密码

**当前状态**: 
- 与 Specifier 方式**不兼容**
- 因为 Specifier 不需要打开系统设置页面

**干扰风险**: ⭐⭐⭐ (中等)
- 如果启用，会尝试点击不存在的 UI 元素
- 消耗系统资源，无实际效果

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

## 5. 代码清理建议

### 5.1 可删除的文件（如果确认不再使用）

1. `connection/Method2ActionWifiAddNetworks.kt` - 整个文件已废弃
2. `connection/WifiAutoConnectService.kt` - 无障碍服务已废弃
3. `connection/WifiConnector.kt` - 可简化为仅保留 Android 9 支持

### 5.2 需要保留但标记为废弃的代码

```kotlin
// 在 WifiConnector.kt 顶部添加
@Deprecated("使用 WifiNetworkSpecifier 替代", ReplaceWith("ScanForegroundService.connectWithSpecifier()"))
class WifiConnector { ... }

// 在 Method2ActionWifiAddNetworks.kt 顶部添加
@Deprecated("此方式已废弃，使用 WifiNetworkSpecifier 替代", level = DeprecationLevel.ERROR)
class Method2ActionWifiAddNetworks { ... }
```

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
