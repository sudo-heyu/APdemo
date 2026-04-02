# APdemo2 开发规范

### onConnectSuccess 调用时机

只能在 WiFi **实际切换成功**后调用（通过广播或无障碍服务回调确认），禁止在用户点击确认对话框时立即调用。

---

## 文档维护规范

### 修改即更新

每次代码修改必须同步更新相关文档：

| 修改内容 | 需更新的文档 |
|----------|-------------|
| WiFi 连接逻辑 | `docs/connection/WIFI_CONNECTION_SPECIFIER.md` |
| 漫游算法 | `docs/roaming/ROAMING_IMPLEMENTATION_ANALYSIS.md` |
| 漫游日志 | `docs/roaming/ROAMING_IMPLEMENTATION_ANALYSIS.md` |
| 新增功能模块 | `docs/PROJECT_DOCS.md` |
| 功能状态变更（已实现/待完善） | `docs/PROJECT_DOCS.md` |
| HTTP 接口变更 | `docs/http/http_frontend.md` |

### 提交规范

- **触发条件**: 用户反馈"改动成功"或"功能正常"后
- **提交内容**: 代码修改 + 文档更新（必须一起提交）
- **提交信息格式**:
  ```
  <type>: <简短描述>

  - 代码变更：<描述>
  - 文档更新：<描述>
  ```
- **Type 规范**:
  - `feat`: 新功能
  - `fix`: 修复问题
  - `docs`: 仅文档更新
  - `refactor`: 重构
  - `wip`: 工作进行中（不单独提交，完成后合并）

### 示例

```bash
# 用户确认漫游连接修复成功后
git add app/src/main/java/com/heyu/apdemo2/service/ScanForegroundService.kt
git add app/src/main/java/com/heyu/apdemo2/docs/roaming/ROAMING_IMPLEMENTATION_ANALYSIS.md
git add app/src/main/java/com/heyu/apdemo2/docs/PROJECT_DOCS.md
git commit -m "fix: 统一漫游和手动连接的Specifier机制

- 代码：漫游触发改用 connectWithSpecifier()，移除 wifiConnector
- 文档：更新漫游实现分析，标记统一连接机制为已修复"
```
