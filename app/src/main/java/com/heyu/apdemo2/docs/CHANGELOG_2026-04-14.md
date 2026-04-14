# 2026-04-14 开发日志

## 提交概览

从 `1ec501e 文档更新` 到 `2cfaccf 隐藏无分项`，共 5 次提交。

---

## 核心改动

### 1. 无障碍服务回归 (5a50dcf, c20a4c1)

- 重新启用 `WifiAccessibilityService` 进行WiFi连接
- 重构 `ScanForegroundService`，移除 Specifier 连接逻辑
- 新增 `ApPerformanceCache` 和 `ApPerformanceMonitor` 用于AP性能监控

### 2. 漫游算法更新 (8738d66)

- 更新ONNX模型为 `ap_roaming_model_video.onnx` (Video only版本)
- 新增 `RoamingMode.kt` 支持多种漫游模式
- 改进 ML 评分算法和 AP 选择逻辑
- 新增 `docs/new_ap_selection/` 目录包含模型训练和推理代码

### 3. UI优化 (d2c2ce9, 2cfaccf)

- AP列表排序优化
- 添加登录提醒功能
- 隐藏无分项AP（过滤无评分的AP）

---

## 文件变更统计

| 类别 | 新增 | 修改 | 删除 |
|------|------|------|------|
| Kotlin源码 | 3 | 8 | 0 |
| ONNX模型 | 1 | 1 | 0 |
| 文档 | 4 | 2 | 0 |
| Python脚本 | 2 | 0 | 0 |

**总计**: +2363 行, -565 行

---

## 新增文件

- `ApPerformanceCache.kt` - AP性能缓存
- `ApPerformanceMonitor.kt` - AP性能监控
- `WifiAccessibilityService.kt` - 无障碍服务（重新引入）
- `RoamingMode.kt` - 漫游模式枚举
- `docs/new_ap_selection/` - 模型训练推理脚本
