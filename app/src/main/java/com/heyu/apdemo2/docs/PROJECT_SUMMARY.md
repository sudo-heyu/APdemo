# APdemo2 项目总结文档
| 日期 | 更新内容 |
|------|----------|
| 2025-04-02 | 重构文档结构，增加待实现功能清单，补充漫游和日志模块状态 |
| 2025-04-02 | 统一连接机制：漫游和手动连接都使用 connectWithSpecifier() |
| 2026-04-02 | 扫描简化为单次模式（默认35s间隔），后端轮询独立可配（默认10s） |
| 2026-04-02 | 漫游评估增加密码过滤和服务器失败降级模式 |
| 2026-04-02 | 漫游日志入口移至主页面 toolbar 书本图标，支持实时更新 |

## 项目概述

APdemo2 是一个 Android WiFi 网络智能管理与自动漫游切换应用。该应用通过扫描周围 WiFi 热点，结合后端服务器评分和本地 ONNX 机器学习模型，实现智能 AP 选择和自动漫游功能。

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 编程语言 | Kotlin |
| 最低 SDK | API 24 (Android 7.0) |
| 目标 SDK | API 36 (Android 16) |
| 构建工具 | Gradle + Kotlin DSL |
| 核心库 | AndroidX, Material Design, RecyclerView, Lifecycle |
| 网络 | OkHttp 4.12.0, Gson |
| 机器学习 | ONNX Runtime Android 1.16.3 |
| 异步处理 | Kotlin Coroutines |

---

## 项目结构

```
app/src/main/java/com/heyu/apdemo2/
├── adapter/           # UI 适配器
├── connection/        # WiFi 连接管理
├── diagnostic/        # 诊断工具
├── model/             # 数据模型
├── network/           # 网络通信
├── roaming/           # 漫游算法
├── scanner/           # WiFi 扫描
├── service/           # 后台服务
└── ui/                # 用户界面
```

---

## 核心模块详解

### 1. 数据模型 (model/)

#### AccessPoint.kt
- **功能**: WiFi 热点数据模型
- **核心字段**:
  - `ssid`: 网络名称
  - `bssid`: MAC 地址
  - `rssi`: 信号强度 (dBm)
  - `frequency`: 频率 (MHz)
  - `capabilities`: 安全能力字符串
  - `score`: 服务器评分 (0-100)
  - `reason`: 推荐/不推荐理由
- **方法**:
  - `isSecured()`: 判断是否为加密网络
  - `getSignalLevel()`: 获取信号等级 (0-4)

#### ScanResponse.kt
- **功能**: 扫描结果响应模型
- **包含**: `ApScoreResult` (SSID + 评分 + 原因)

#### NetworkModels.kt
- **功能**: AP 详情请求/响应模型

---

### 2. WiFi 扫描 (scanner/)

#### WifiScanner.kt
- **功能**: WiFi 扫描管理器（单次扫描模式）
- **特点**:
  - 使用系统 `WifiManager.startScan()`
  - 4秒超时保护
  - 去重处理（同 SSID 保留信号最强）
  - 按 RSSI 降序排序
- **扫描结果处理**:
  - 过滤空 SSID
  - 转换为 AccessPoint 对象列表
  - 同 SSID 合并（取最强信号）

---

### 3. WiFi 连接 (connection/)

#### WifiConnector.kt
- **功能**: WiFi 连接管理器（已精简）
- **适用版本**: Android 9 及以下
- **Android 10+**: 使用 `WifiNetworkSpecifier` 方式

#### PasswordStore.kt
- **功能**: WiFi 密码本地存储
- **存储方式**: SharedPreferences
- **方法**: `save()`, `get()`, `delete()`

---

### 4. 网络通信 (network/)

#### ApiService.kt
- **功能**: HTTP 通信服务
- **依赖**: OkHttp + Gson
- **接口**:
  - `fetchApDetails()`: 获取单个 AP 详情
  - `uploadScanResults()`: 批量上传扫描结果并获取评分
- **超时设置**:
  - 连接超时: 10s
  - 读取超时: 15s
  - 写入超时: 10s

---

### 5. 漫游算法 (roaming/)

#### ApPairwisePredictor.kt
- **功能**: AP 两两比较预测器接口
- **方法**: `predict()` - 预测 AP A 优于 AP B 的概率
- **设计**: 接口化设计，便于切换不同模型实现

#### ApRoamingModel.kt
- **功能**: ONNX 模型推理实现
- **模型文件**: `assets/ap_roaming_model.onnx`
- **输入特征** (13维):
  - RSSI 归一化 (A/B/差值)
  - 评分归一化 (A/B/差值)
  - 乘积特征 (A/B)
  - 连接状态 (下行/上行)
  - 业务类型 (游戏模式)
- **预处理**: StandardScaler 归一化
- **输出**: AP A 优于 AP B 的概率

#### ApSelectionManager.kt
- **功能**: AP 选择管理器
- **算法流程**:
  1. **Borda 排名过滤**: 根据 RSSI 和众包评分综合排序，保留前 K 个
  2. **Pairwise 比较**: 使用模型对每对 AP 进行比较
  3. **最佳 AP 选择**: 综合平均概率、获胜次数、众包评分

#### RoamingLogManager.kt
- **功能**: 漫游算法执行日志管理
- **存储**: 文件存储 (`roaming_algorithm.log`)
- **限制**: 最大 1MB，超过自动清空
- **监听**: 支持实时日志监听器

---

### 6. 后台服务 (service/)

#### ScanForegroundService.kt
- **功能**: 前台服务，保持后台扫描不中断
- **启动方式**: `startForegroundService()` + `bindService()`
- **核心功能**:
  - 周期性 WiFi 扫描
  - 后端评分同步
  - 自动漫游决策
  - `WifiNetworkSpecifier` 连接管理
- **保活机制**:
  - 前台通知 (FOREGROUND_SERVICE_TYPE_LOCATION)
  - WakeLock (扫描期间持有)
  - AlarmManager (Doze 状态下唤醒)
  - START_STICKY (崩溃后重启)
- **配置参数**:
  - 扫描间隔: 默认 35s
  - 轮询间隔: 默认 10s
  - 漫游冷却: 30s

---

### 7. 用户界面 (ui/)

#### MainActivity.kt
- **功能**: 主 Activity
- **职责**:
  - 权限管理（位置、WiFi、通知、后台定位）
  - 服务绑定/解绑
  - 底部导航切换
  - 服务器配置对话框
  - 自动漫游开关
  - 漫游日志查看

#### WifiFragment.kt
- **功能**: WiFi 列表页面
- **功能**:
  - 显示 AP 列表 (RecyclerView)
  - 处理 AP 点击连接
  - 密码输入对话框
  - 连接状态同步
  - 系统 WiFi 状态监听

#### HelpFragment.kt
- **功能**: 帮助页面
- **内容**:
  - 后台运行设置指南（各品牌手机）
  - 自动连接设置指南
  - 漫游日志查看

#### AccessPointAdapter.kt
- **功能**: AP 列表适配器
- **排序逻辑**:
  1. 置顶 AP (已连接)
  2. 有评分的 AP
  3. 按 RSSI 降序
- **UI 元素**:
  - SSID 显示
  - 信号强度图标 (0-4级)
  - 评分显示
  - 推荐理由展开/收起
  - 已连接标记

---

## WiFi 连接机制

### Android 10+ (推荐方式)
使用 `WifiNetworkSpecifier`:
```kotlin
val specifier = WifiNetworkSpecifier.Builder()
    .setSsid(ssid)
    .setWpa2Passphrase(password)  // 加密网络
    .build()

val request = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .setNetworkSpecifier(specifier)
    .build()

connectivityManager.requestNetwork(request, networkCallback)
```

**特点**:
- 系统弹出确认对话框（无障碍服务可自动确认）
- 仅应用内生效，不替换系统 WiFi 连接
- 支持 `bindProcessToNetwork()` 绑定网络

### Android 9 及以下
使用 `WifiManager` 传统 API:
- `addNetwork()` + `enableNetwork()` + `reconnect()`
- 直接替换系统 WiFi 连接

---

## 自动漫游流程

```
1. 定时触发扫描
   ↓
2. 获取周围 AP 列表
   ↓
3. 上传后端获取评分
   ↓
4. 过滤可连接 AP（开放网络 + 已存密码的加密网络）
   ↓
5. Borda 排名过滤 → 保留前 K 个
   ↓
6. Pairwise 比较选出最佳 AP
   ↓
7. 决策：是否需要切换？
   - 当前已是最优 → 不切换
   - 冷却期内 → 不切换
   - 推荐切换 → 执行漫游
   ↓
8. 触发 WiFi 连接切换
```

---

## 权限清单

| 权限 | 用途 |
|------|------|
| `ACCESS_FINE_LOCATION` | 扫描 WiFi 需要精确定位 |
| `ACCESS_BACKGROUND_LOCATION` | 后台扫描定位 |
| `ACCESS_WIFI_STATE` | 获取 WiFi 状态 |
| `CHANGE_WIFI_STATE` | 修改 WiFi 状态 |
| `CHANGE_NETWORK_STATE` | 修改网络状态 |
| `NEARBY_WIFI_DEVICES` | Android 13+ 附近设备 |
| `FOGROUND_SERVICE` | 前台服务 |
| `FOREGROUND_SERVICE_LOCATION` | 前台服务位置类型 |
| `POST_NOTIFICATIONS` | 通知权限 |
| `WAKE_LOCK` | 保持 CPU 唤醒 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 忽略电池优化 |
| `INTERNET` | 网络通信 |
| `ACCESS_NETWORK_STATE` | 获取网络状态 |

---

## 配置文件

### build.gradle.kts (App)
- **compileSdk**: 36
- **minSdk**: 24
- **targetSdk**: 36
- **Java/Kotlin 目标版本**: 11

### AndroidManifest.xml
- **应用主题**: `Theme.APdemo2`
- **允许明文传输**: `android:usesCleartextTraffic="true"`
- **主 Activity**: `MainActivity`
- **前台服务**: `ScanForegroundService`

---

## 开发规范 (CLAUDE.md)

### 修改即更新原则
| 修改内容 | 需更新的文档 |
|----------|-------------|
| WiFi 连接逻辑 | `docs/connection/WIFI_CONNECTION_SPECIFIER.md` |
| 漫游算法 | `docs/roaming/ROAMING_IMPLEMENTATION_ANALYSIS.md` |
| 新增功能模块 | `docs/PROJECT_DOCS.md` |
| HTTP 接口变更 | `docs/http/http_frontend.md` |

### 提交规范
```
<type>: <简短描述>

- 代码变更：<描述>
- 文档更新：<描述>
```

**Type**: `feat`, `fix`, `docs`, `refactor`, `wip`

---

## 文件统计

| 类别 | 数量 | 主要文件 |
|------|------|----------|
| Kotlin 源文件 | 15 | 见上述模块 |
| XML 布局 | 5 | activity_main, fragment_wifi, fragment_help, item_access_point 等 |
| XML 资源 | 20+ | 图标、菜单、主题、配置 |
| 构建配置 | 4 | build.gradle.kts, settings.gradle.kts, gradle.properties, AndroidManifest.xml |

---

## 核心类关系图

```
MainActivity
    ├── binds to → ScanForegroundService
    │       ├── uses → WifiScanner
    │       ├── uses → ApSelectionManager
    │       │       └── uses → ApRoamingModel (ONNX)
    │       ├── uses → ApiService
    │       └── uses → RoamingLogManager
    ├── contains → WifiFragment
    │       └── uses → AccessPointAdapter
    └── contains → HelpFragment

WifiConnector (legacy, Android 9-)
PasswordStore (SharedPreferences)
```

---

*文档生成时间: 2026-04-02*
*项目分支: feature/network-specifier*
