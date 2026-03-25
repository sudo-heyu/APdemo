# WiFi 连接方式尝试列表

> 创建时间：2026-03-23
> 状态：测试中 - 所有方式暂时解禁，逐一验证

---

## 官方标准 API 方式

### 方式 1：WifiConfiguration + addNetwork (Android 9-)
- **API 级别**：Android 9 (API 28) 及以下
- **连接途径**：`WifiManager.addNetwork() → enableNetwork(true) → reconnect()`
- **是否系统级**：✅ 是，真实 WiFi 切换
- **用户体验**：无弹窗，直接连接
- **限制**：Android 10+ 被限制，只能连接已保存网络
- **状态**：作为系统判断的分支选项

---

### 方式 2：ACTION_WIFI_ADD_NETWORKS (Android 11+)
- **API 级别**：Android 11 (API 30+)
- **连接途径**：`Intent(Settings.ACTION_WIFI_ADD_NETWORKS)` 弹系统窗
- **是否系统级**：✅ 是，用户确认后立即连接
- **用户体验**：系统弹窗确认
- **限制**：国产 ROM 可能静默返回
- **状态**：✅ 已实现，当前在用

---

### 方式 3：WifiNetworkSpecifier + requestNetwork (Android 10+)
- **API 级别**：Android 10 (API 29+)
- **连接途径**：`WifiManager.requestNetwork(WifiNetworkSpecifier, callback)`
- **是否系统级**：❌ 否，产生本地/P2P 连接（带"."）
- **用户体验**：应用内弹窗选择网络
- **限制**：无法真正切换系统 WiFi
- **状态**：❌ 已禁用（假连接）

---

### 方式 4：WifiNetworkSuggestion 后台自动 (Android 10+)
- **API 级别**：Android 10 (API 29+)
- **连接途径**：`WifiManager.addNetworkSuggestions() → 系统后台自动连接`
- **是否系统级**：⚠️ 部分，系统决定连接时机
- **用户体验**：无感知，可能延迟或从不连接
- **限制**：无法保证立即生效
- **状态**：待测试

---

## 系统设置页面方式

### 方式 5：ACTION_WIFI_SETTINGS + 无障碍服务自动操作
- **API 级别**：全版本
- **连接途径**：跳转设置页 → 无障碍服务模拟点击输入
- **是否系统级**：✅ 是，真实系统操作
- **用户体验**：页面跳转，自动完成
- **限制**：需用户开启无障碍权限
- **状态**：✅ 已实现，保底方案


## 厂商私有 API 方式（同时集成）

### 小米 MIUI 私有 API
- **API 级别**：MIUI 全版本
- **连接途径**：反射调用 `WifiManager.connectToNetwork()`
- **是否系统级**：✅ 是（如可用）
- **限制**：需系统权限或白名单，随时可能失效
- **状态**：待测试

---

### 华为 EMUI/HarmonyOS 私有 API
- **API 级别**：EMUI 全版本
- **连接途径**：反射调用华为扩展方法
- **是否系统级**：✅ 是（如可用）
- **限制**：需 `android.permission.HW_CONNECTIVITY_ACTION`
- **状态**：待测试

---

### OPPO ColorOS 私有 API
- **API 级别**：ColorOS 全版本
- **连接途径**：反射调用 ColorOS 扩展
- **是否系统级**：✅ 是（如可用）
- **限制**：部分操作被系统拦截
- **状态**：待测试

---

### vivo OriginOS 私有 API
- **API 级别**：OriginOS/FuntouchOS 全版本
- **连接途径**：反射调用 vivo 扩展
- **是否系统级**：✅ 是（如可用）
- **限制**：限制较多
- **状态**：待测试

---

### 方式 三星 OneUI 私有 API
- **API 级别**：OneUI 全版本
- **连接途径**：反射调用三星扩展
- **是否系统级**：✅ 是（如可用）
- **限制**：相对开放但仍非官方
- **状态**：待测试

---

## 隐藏/反射 API 方式

### 方式 12：反射调用 connect 方法
- **API 级别**：Android 10+
- **连接途径**：反射 `WifiManager.connect(WifiConfiguration, ActionListener)`
- **是否系统级**：✅ 是（如可用）
- **限制**：Android 10+ 隐藏 API 限制，可能抛出异常
- **状态**：待测试

---

### 方式 13：反射调用 hidden 字段
- **API 级别**：全版本
- **连接途径**：反射修改 `WifiConfiguration` hidden 字段
- **是否系统级**：⚠️ 不确定
- **限制**：Google Play 政策风险
- **状态**：待测试

---

## 其他方式

### 方式 14：Settings.Panel.ACTION_INTERNET_CONNECTIVITY
- **API 级别**：Android 11+
- **连接途径**：底部面板引导用户连接
- **是否系统级**：⚠️ 是，但体验差
- **用户体验**：底部滑出面板，需多步操作
- **限制**：用户反馈明确拒绝此方式
- **状态**：待测试

---

### 方式 15：ConnectivityManager + NetworkRequest
- **API 级别**：Android 5+
- **连接途径**：请求特定网络，绑定到 App
- **是否系统级**：❌ 否，仅 App 内可用
- **用户体验**：无感知
- **限制**：不改变系统 WiFi 连接
- **状态**：待测试

---

## 测试进度记录

| 序号 | 方式 | 测试结果 | 用户判定 |
|------|------|----------|----------|
| 1 | WifiConfiguration + addNetwork | ✅ 已实现 | 待测试 |
| 2 | ACTION_WIFI_ADD_NETWORKS | ✅ 已重新实现 | 待测试 |
| 3 | WifiNetworkSpecifier + requestNetwork | 待测试 | |
| 4 | WifiNetworkSuggestion 后台自动 | 待测试 | |
| 5 | ACTION_WIFI_SETTINGS + 无障碍 | ✅ 已实现 | 在用 |
| 6 | ACTION_WIFI_SETTINGS + 手动 | 待测试 | |
| 7 | 小米 MIUI 私有 API | 待测试 | |
| 8 | 华为 EMUI 私有 API | 待测试 | |
| 9 | OPPO ColorOS 私有 API | 待测试 | |
| 10 | vivo OriginOS 私有 API | 待测试 | |
| 11 | 三星 OneUI 私有 API | 待测试 | |
| 12 | 反射 connect 方法 | 待测试 | |
| 13 | 反射 hidden 字段 | 待测试 | |
| 14 | ACTION_INTERNET_CONNECTIVITY | 待测试 | |
| 15 | ConnectivityManager + NetworkRequest | 待测试 | |

---

## 下一步行动计划

请指示从哪个方式开始测试：

1. **先测试官方 API 补充方式**（方式 3、4、6）
2. **先测试厂商私有 API**（方式 7-11）
3. **先测试反射 API**（方式 12-13）
4. **全部同时实现，逐个验证**

> 注：每测试完成一个方式，我会更新此文档的状态列，您可以直接在文档中打叉 ❌ 表示否定。
