# HTTP 接口文档 (Frontend -> Backend)

本文档记录了 Android 客户端向后端发送的 HTTP 请求及其预期响应格式。

## 1. 批量上传扫描结果并请求评分

**接口描述**: 将扫描到的 SSID 列表发送给后端，获取每个 SSID 的评分和理由。

- **URL**: `http://<IP>:<PORT>/api/upload_scan`
- **Method**: `POST`
- **Content-Type**: `application/json`

### 请求格式 (JSON)
```json
{
  "ssids": ["SSID_1", "SSID_2", "SSID_3"],
  "count": 3,
  "device_model": "Pixel 6 Pro"
}
```

### 响应格式 (JSON)
```json
{
  "status": "success",
  "results": [
    {
      "ssid": "SSID_1",
      "score": 95,
      "reason": "信号极强，延迟低，且无干扰"
    },
    {
      "ssid": "SSID_2",
      "score": 60,
      "reason": "信号中等，信道拥塞严重"
    }
  ]
}
```

---

## 2. 示例场景

### 场景：批量扫描并展示评分
1. 客户端扫描周围 WiFi，收集到 SSID 列表：`["Office_5G", "Starbucks_Free"]`。
2. 客户端构造请求体：
   ```json
   {
     "ssids": ["Office_5G", "Starbucks_Free"],
     "count": 2,
     "device_model": "Samsung S23"
   }
   ```
3. 发送 POST 请求至 `/api/upload_scan`。
4. 后端返回评分：
   ```json
   {
     "status": "success",
     "results": [
       {
         "ssid": "Office_5G",
         "score": 98,
         "reason": "企业级 AP，吞吐量大且稳定"
       }
     ]
   }
   ```
5. 客户端 UI 逻辑：
    - **排序**: "Office_5G" (有评分) 排在前面，"Starbucks_Free" (无评分) 排在后面。
    - **展示**: "Office_5G" 右侧显示分数 "98"，RSSI 信号值向左偏移。
    - **交互**: "Office_5G" 右侧增加下拉按钮，点击展开显示理由 "企业级 AP，吞吐量大且稳定"。

---

---

## 2. 废弃的接口

### 导出漫游算法日志（已移除）

此功能已从当前版本中移除。客户端不再支持将本地漫游日志上传到后端。
原接口 `POST /api/roaming_log` 已废弃，相关 UI 菜单项（工具栏溢出菜单 → "Export Log"）已删除。

**保留的日志功能**:
- 本地日志存储（`RoamingLogManager.kt`）：1MB 自动清空
- 实时日志查看（主页面 toolbar 书本图标，AlertDialog + WebView）
- 日志清空（日志对话框 "Clear" 按钮）

---

## 3. 废弃的接口

### 获取单个AP详细信息

此功能已从当前版本中移除，客户端不再支持通过点击单个 AP 来查询其详细信息。所有评分和信息均通过批量接口获取。
