# WiFi 连接方式总览

本文档梳理 APdemo2 项目中所有与 WiFi 连接相关的实现方式、适用场景、技术限制和当前使用状态。

---

## 一、项目背景

APdemo2 是一个 WiFi 扫描与自动连接工具，核心需求是：**收到服务器下发的目标 SSID 后，自动切换设备 WiFi 并连接**。由于 Android 从 API 29 起对 WiFi 操作收紧了权限，不同系统版本所能使用的连接方式差异显著，项目中因此积累了多套方案。

---

## 二、连接方式一览

| 方案 | 适用版本 | 是否真实切换系统 WiFi | 当前分支 | 状态 |
|---|---|---|---|---|
| `WifiManager.addNetwork + enableNetwork` | API 28- (Android 9 及以下) | ✅ 是 | `main` | 活跃（旧机兜底） |
| `WifiNetworkSuggestion` | API 29+ (Android 10+) | ✅ 是（系统决定时机） | `main` | 活跃 |
| `ACTION_WIFI_ADD_NETWORKS` | API 30+ (Android 11+) | ✅ 是（用户确认后立即切换） | `main` | 活跃（首选） |
| 无障碍服务 (`WifiAutoConnectService`) | API 29 (Android 10) | ✅ 是（模拟用户操作） | `main` | 活跃（Android 10 专用） |
| `WifiNetworkSpecifier` | API 29+ (Android 10+) | ❌ 否（仅绑定当前 App 进程） | `feature/network-specifier` | 实验分支 |

---

## 三、各方案详解

### 方案 A：Legacy API（API 28 及以下）

**实现文件**：`WifiConnector.kt` → `connectLegacy()`

**核心 API**：
```kotlin
WifiManager.addNetwork(WifiConfiguration)
WifiManager.enableNetwork(networkId, disableOthers = true)
WifiManager.reconnect()
```

**流程**：
1. 构造 `WifiConfiguration`（填写 SSID、PSK 或开放标志）
2. `addNetwork()` 写入系统已保存网络列表，返回 `networkId`
3. `enableNetwork(networkId, true)` 禁用其他网络并启用目标网络
4. `reconnect()` 触发系统立即发起关联

**特点**：
- 操作直接，无需用户交互
- Android 10+ 上已被系统禁用（返回 -1 或无效果）
- 切换是真实的系统级切换，状态栏 WiFi 图标会变化

---

### 方案 B：WifiNetworkSuggestion（API 29+）

**实现文件**：`WifiConnector.kt` → `connectViaSuggestion()`

**核心 API**：
```kotlin
WifiManager.addNetworkSuggestions(List<WifiNetworkSuggestion>)
```

**流程**：
1. 构造 `WifiNetworkSuggestion`（SSID + WPA2/WPA3 密码）
2. `addNetworkSuggestions()` 向系统提交"建议连接此网络"
3. 注册 `WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION` 广播
4. 触发 `wm.startScan()` 让系统发现该网络
5. 系统在扫描到目标 SSID 后，**自行决定**何时切换，切换完成后广播通知

**特点**：
- 系统级真实切换，所有应用流量均走新 WiFi
- 首次使用需用户在系统通知中授权"允许 App 建议 WiFi"（一次性）
- 系统自主决定切换时机，**无法保证立即生效**，可能延迟或静默失败
- 最大重连次数为 5 次（`MAX_RECONNECT = 5`），掉线后自动触发 `startScan()` 重连
- 连接超时为 5 分钟（等待用户在系统 UI 操作）

---

### 方案 C：ACTION_WIFI_ADD_NETWORKS（API 30+，首选）

**实现文件**：`connection/Method2ActionWifiAddNetworks.kt`

**核心 API**：
```kotlin
Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
    putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
}
```

**流程**：
1. 构造 `WifiNetworkSuggestion` 作为参数
2. 启动系统 Intent，弹出**系统原生对话框**（白色背景，显示网络名和加密类型）
3. 用户点击"连接"后，`onActivityResult` 收到 `RESULT_OK`
4. 监听 `WifiManager.NETWORK_STATE_CHANGED_ACTION` 广播，等待系统广播确认实际连接成功
5. 仅在广播确认后调用 `onConnectSuccess`

**ActivityResult 结果码**：
- `ADD_WIFI_RESULT_SUCCESS`：添加成功，等待连接广播
- `ADD_WIFI_RESULT_ALREADY_EXISTS`：网络已保存，转由无障碍服务处理
- `ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED`：密码错误或配置无效

**特点**：
- 用户体验最佳：全程在 App 内完成，不跳出
- 连接为真实系统级切换，所有应用流量均走新 WiFi
- 需要 Android 11+（API 30）
- 网络已存在时会返回 `ALREADY_EXISTS`，需要联动无障碍服务完成切换

---

### 方案 D：无障碍服务（Android 10 专用）

**实现文件**：`WifiAutoConnectService.kt`

**适用场景**：
- Android 10（API 29）上 `ACTION_WIFI_ADD_NETWORKS` 不可用
- `ACTION_WIFI_ADD_NETWORKS` 返回 `ALREADY_EXISTS`，需要切换到已保存的网络

**触发方式**：
```kotlin
WifiAutoConnectService.pendingSsid = ssid
WifiAutoConnectService.pendingPassword = password  // 开放网络传 null
startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
```

**工作原理**（两阶段自动操作）：

**阶段一：查找并点击 SSID**
1. 监听 `TYPE_WINDOW_STATE_CHANGED` 事件，等待 WiFi 设置页加载（延迟 800ms）
2. 在页面节点树中按文本搜索目标 SSID
3. 优先精确匹配，降级时接受唯一候选
4. 找到后向上遍历节点树（最多 8 层）找可点击祖先节点并执行点击
5. 最多重试 20 次，每次间隔 300ms；30 秒超时后自动清理

**阶段二：处理密码输入框**
1. 点击 SSID 后延迟 700ms 等待密码框弹出
2. BFS 遍历节点树查找 `EditText`（通过 `inputType` 识别密码类型）
3. 通过 `ACTION_SET_TEXT` 填入密码
4. 搜索"连接/加入/Connect/Join/确定/OK"按钮并点击
5. 延迟 600ms 后执行 `GLOBAL_ACTION_BACK` 返回 App

**首次使用前提**：用户需在无障碍设置中手动开启"WiFi 一键切换"服务。跳转方式：
```kotlin
Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
    putExtra(":settings:show_fragment_args", Bundle().apply {
        putString(":settings:fragment_args_key", "${packageName}/${WifiAutoConnectService::class.name}")
    })
}
```
原生 Android 会直接定位到该服务项；国产 ROM 回退到无障碍总列表。

---

### 方案 E：WifiNetworkSpecifier（实验分支）

**分支**：`feature/network-specifier`

**实现文件**：`WifiFragment.kt` → `connectWithSpecifier()`

**核心 API**：
```kotlin
val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pwd).build()
val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .setNetworkSpecifier(specifier)
    .build()
connectivityManager.requestNetwork(request, networkCallback)
```

**回调**：
- `onAvailable(network)`：连接成功，调用 `bindProcessToNetwork(network)` 绑定进程流量
- `onUnavailable()`：连接失败或用户取消（可能延迟 30–60 秒才触发）
- `onLost(network)`：连接断开，解绑进程网络

**断开**：调用 `unregisterNetworkCallback()` 释放请求，系统自动恢复原有网络。

**本质限制**：
- **不是系统级切换**：系统 WiFi 关联状态和状态栏图标均不变化
- **仅绑定当前 App 进程**：其他应用的流量不受影响，无法路由第三方 App 的流量
- **onUnavailable 触发滞后**：密码错误或信号弱时，系统内部重试后才回调，等待可达 30–60 秒
- 要求 Android 10+（API 29），API 28- 设备直接提示不支持

---

## 四、main 分支连接决策流程

```
用户点击 AP
    │
    ├─ Android 11+ ──► ACTION_WIFI_ADD_NETWORKS 弹出系统对话框
    │                        │
    │                  RESULT_OK + ALREADY_EXISTS
    │                        │
    │                        └──► 无障碍服务切换（已保存网络）
    │
    ├─ Android 10  ──► 无障碍服务（直接打开 WiFi 设置页）
    │
    └─ Android 9-  ──► WifiManager.addNetwork + enableNetwork（Legacy）
```

---

## 五、密码持久化

**实现文件**：`PasswordStore.kt`

连接成功后，密码以 SSID 为键存储在 `SharedPreferences` 中。下次连接同一 SSID 时自动读取，无需用户重新输入。

---

## 六、各方案对比

| 维度 | Legacy (A) | Suggestion (B) | ADD_NETWORKS (C) | 无障碍 (D) | Specifier (E) |
|---|---|---|---|---|---|
| 真实系统切换 | ✅ | ✅ | ✅ | ✅ | ❌ |
| 无需用户操作 | ✅ | ❌（首次授权） | ❌（需点确认） | ❌（需开无障碍） | ❌（需点系统选择器） |
| 连接速度 | 快 | 不确定 | 快（用户确认后） | 中 | 慢（系统超时长） |
| 最低 API | 无限制 | 29 | 30 | 29 | 29 |
| 流量覆盖范围 | 全系统 | 全系统 | 全系统 | 全系统 | 仅当前进程 |
