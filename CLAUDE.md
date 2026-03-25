# APdemo2 开发规范

## WiFi 连接：必须执行真实系统级切换

**核心原则：任何 WiFi 连接操作必须触发真正的系统级 WiFi 切换，禁止产生假连接。**

### 允许的连接方式

| 系统版本 | 方式 | 说明 |
|---|---|---|
| Android 11+（API 30+） | `WifiManager.ACTION_WIFI_ADD_NETWORKS` | 弹出系统级弹窗询问是否连接，用户点确认后系统立即发起连接，全程不离开 App；RESULT_OK 后必须监听广播确认实际连接成功再更新 UI |
| Android 10（API 29） | 无障碍服务（WifiAutoConnectService）| 设置 `pendingSsid/pendingPassword` → 打开 WiFi 设置页 → 服务自动操作 |
| Android 9-（API 28-） | `WifiManager.addNetwork + enableNetwork` | 旧版直接 API |

### 严格禁止的方式

- **`WifiNetworkSpecifier` + `requestNetwork`**：产生带"."的本地/P2P 连接，不是真实 WiFi 切换
- **`WifiNetworkSuggestion`** 作为直接连接触发器：系统自行决定何时切换，无法保证立即生效
- **`Settings.Panel.ACTION_WIFI` / `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`**：弹出的是引导跳转设置的底部面板，体验比直接跳设置更差，用户反馈明确拒绝，永远禁用

### onConnectSuccess 调用时机

只能在 WiFi **实际切换成功**后调用（通过广播或无障碍服务回调确认），禁止在用户点击确认对话框时立即调用。
