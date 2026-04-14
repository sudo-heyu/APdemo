# 新 AP 选择模型 (Video Only)

本目录包含视频业务专用的 AP 漫游选择模型，基于 LightGBM 实现 Pairwise AP 比较算法。

## 文件说明

| 文件 | 说明 |
|------|------|
| `model_video_only.pkl` | LightGBM 模型文件（含 StandardScaler），视频业务专用，无 biz 特征 |
| `infer.py` | Python 批量推理脚本，支持 CSV 输入输出 |
| `convert_to_onnx.py` | 转换为 ONNX 格式的脚本，生成 Android 可用模型并打印标准化参数 |
| `ap_roaming_model_video.onnx` | 生成的 ONNX 模型（需复制到 `app/src/main/assets/`） |

## 模型特征

**特征数量**: 10 维

| 序号 | 特征名 | 说明 | 计算公式 |
|------|--------|------|----------|
| 0 | `rssi_a` | AP A 的 RSSI 归一化值 | `(rssi_a - (-90)) / 60`，裁剪至 [0, 1] |
| 1 | `rssi_b` | AP B 的 RSSI 归一化值 | `(rssi_b - (-90)) / 60`，裁剪至 [0, 1] |
| 2 | `rssi_diff` | RSSI 差值 | `rssi_a - rssi_b` |
| 3 | `score_a` | AP A 的众包评分归一化 | `score_a / 100`，裁剪至 [0, 1] |
| 4 | `score_b` | AP B 的众包评分归一化 | `score_b / 100`，裁剪至 [0, 1] |
| 5 | `score_diff` | 评分差值 | `score_a - score_b` |
| 6 | `prod_a` | AP A 综合特征 | `rssi_a * score_a` |
| 7 | `prod_b` | AP B 综合特征 | `rssi_b * score_b` |
| 8 | `a_conn` | AP A 连接状态 | 是否可连接（0 或 1） |
| 9 | `b_conn` | AP B 连接状态 | 是否可连接（0 或 1） |

**RSSI 归一化范围**: [-90, -30] dBm

**评分归一化范围**: [0, 100]

**模型输出**: 概率值 (0.0 ~ 1.0)，表示 AP A 优于 AP B 的可能性

## 转换与集成步骤

### 1. 生成 ONNX 模型

```bash
# 进入模型目录
cd app/src/main/java/com/heyu/apdemo2/docs/new_ap_selection/

# 安装依赖（首次需要）
pip install lightgbm onnxmltools numpy pandas scikit-learn

# 运行转换脚本
python convert_to_onnx.py
```

### 2. 复制模型到 assets

```bash
# 从转换脚本输出目录复制
cp ap_roaming_model_video.onnx ../../../../../assets/

# 或手动复制到
# app/src/main/assets/ap_roaming_model_video.onnx
```

### 3. 更新 Android 代码中的标准化参数

运行 `convert_to_onnx.py` 后，终端会打印 `SCALER_MEAN` 和 `SCALER_SCALE` 参数：

```kotlin
// ===== 复制以下内容到 ApRoamingModel.kt =====
// 特征顺序: ['rssi_a', 'rssi_b', 'rssi_diff', 'score_a', 'score_b', 'score_diff', 'prod_a', 'prod_b', 'a_conn', 'b_conn']
private val SCALER_MEAN = doubleArrayOf(
    0.389, 0.389, 0.0,  // rssi_a, rssi_b, rssi_diff
    0.657, 0.657, 0.0,  // score_a, score_b, score_diff
    0.256, 0.256,       // prod_a, prod_b
    0.675, 0.675        // a_conn, b_conn
)
private val SCALER_SCALE = doubleArrayOf(
    0.235, 0.235, 0.392,
    0.219, 0.219, 0.315,
    0.173, 0.173,
    0.469, 0.469
)
// ============================================
```

**注意**: 上述参数仅为示例，请以 `convert_to_onnx.py` 实际打印的数值为准。

### 4. 验证模型加载

启动应用后查看日志，确认输出：
```
D/[ApRoamingModel]: 视频专用 ONNX 模型加载成功
```

## 与旧模型对比

| 特性 | 旧模型 (ap_roaming_model.onnx) | 新模型 (ap_roaming_model_video.onnx) |
|------|-------------------------------|-------------------------------------|
| 业务特征 | 包含 biz_type 等特征 | 仅视频业务，无 biz 特征 |
| 特征维度 | 13 维 | 10 维 |
| 使用场景 | 通用场景 | 视频业务专用 |
| 文件大小 | 较大 | 较小 |

## Python 批量推理示例

```bash
# 准备 CSV 文件（必须包含列: rssi_a, rssi_b, score_a, score_b, a_conn, b_conn）
python infer.py --csv input.csv --rmin -90 --rmax -30 --output results.csv

# 查看结果
cat results.csv
# 输出: idx, prob_a_better, prediction, pred_label
```

## 注意事项

1. **特征顺序必须严格一致**: Python 训练和 Kotlin 推理的特征顺序必须相同
2. **标准化参数必须匹配**: 必须使用训练时计算的 `SCALER_MEAN` 和 `SCALER_SCALE`
3. **RSSI 范围**: 归一化范围 [-90, -30] 与 `infer.py` 默认值一致
4. **模型降级**: 如果 ONNX 模型加载失败，`ApRoamingModel` 会返回 0.5（同等概率）

## 文档更新记录

| 日期 | 更新内容 |
|------|----------|
| 2026-04-14 | 初始版本，完整说明 Video Only 模型的特征、转换步骤和集成方法 |
