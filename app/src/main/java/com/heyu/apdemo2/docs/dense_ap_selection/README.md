# Dense AP Selection Model

This directory contains the dense AP roaming selection model, based on XGBoost for Pairwise AP comparison.

## Files

| File | Description |
|------|-------------|
| `model_video_only_xgb_dense.pkl` | XGBoost model file (with StandardScaler), video-specific, no biz features |
| `convert_to_onnx.py` | Script to convert to ONNX format, generates Android-usable model and prints normalization parameters |
| `ap_roaming_model_dense.onnx` | Generated ONNX model (copy to `app/src/main/assets/`) |

## Model Features

**Feature count**: 10 dimensions

| Index | Feature Name | Description | Formula |
|--------|------|------|----------|
| 0 | `rssi_a` | AP A RSSI normalized | `(rssi_a - (-90)) / 60`, clipped to [0, 1] |
| 1 | `rssi_b` | AP B RSSI normalized | `(rssi_b - (-90)) / 60`, clipped to [0, 1] |
| 2 | `rssi_diff` | RSSI difference | `rssi_a - rssi_b` |
| 3 | `score_a` | AP A crowdsourced score normalized | `score_a / 100`, clipped to [0, 1] |
| 4 | `score_b` | AP B crowdsourced score normalized | `score_b / 100`, clipped to [0, 1] |
| 5 | `score_diff` | Score difference | `score_a - score_b` |
| 6 | `prod_a` | AP A combined feature | `rssi_a * score_a` |
| 7 | `prod_b` | AP B combined feature | `rssi_b * score_b` |
| 8 | `a_conn` | AP A connection status | Whether connectable (0 or 1) |
| 9 | `b_conn` | AP B connection status | Whether connectable (0 or 1) |

**RSSI normalization range**: [-90, -30] dBm

**Score normalization range**: [0, 100]

**Model output**: Probability value (0.0 ~ 1.0), indicating likelihood that AP A is better than AP B

## Conversion and Integration Steps

### 1. Generate ONNX Model

```bash
# Navigate to model directory
cd app/src/main/java/com/heyu/apdemo2/docs/dense_ap_selection/

# Install dependencies (first time)
pip install xgboost onnxmltools numpy

# Run conversion script
python convert_to_onnx.py
```

### 2. Copy Model to Assets

```bash
# Copy from conversion output directory
cp ap_roaming_model_dense.onnx ../../../../../assets/

# Or manually copy to
# app/src/main/assets/ap_roaming_model_dense.onnx
```

### 3. Update Android Code with Normalization Parameters

After running `convert_to_onnx.py`, the terminal will print `SCALER_MEAN` and `SCALER_SCALE` parameters:

```kotlin
// ===== Copy below to ApRoamingModel.kt =====
// Feature order: ['rssi_a', 'rssi_b', 'rssi_diff', 'score_a', 'score_b', 'score_diff', 'prod_a', 'prod_b', 'a_conn', 'b_conn']
private val SCALER_MEAN = doubleArrayOf(
    0.xxx, 0.xxx, 0.xxx,  // rssi_a, rssi_b, rssi_diff
    0.xxx, 0.xxx, 0.xxx,  // score_a, score_b, score_diff
    0.xxx, 0.xxx,         // prod_a, prod_b
    0.xxx, 0.xxx          // a_conn, b_conn
)
private val SCALER_SCALE = doubleArrayOf(
    0.xxx, 0.xxx, 0.xxx,
    0.xxx, 0.xxx, 0.xxx,
    0.xxx, 0.xxx,
    0.xxx, 0.xxx
)
// ============================================
```

**Note**: The above parameters are examples only. Use the actual values printed by `convert_to_onnx.py`.

### 4. Verify Model Loading

After starting the app, check logs to confirm:
```
D/[ApRoamingModel]: Dense ONNX model loaded successfully
```

## Comparison with Previous Model

| Feature | Previous (ap_roaming_model_video.onnx) | New (ap_roaming_model_dense.onnx) |
|------|-------------------------------|-------------------------------------|
| Model type | LightGBM | XGBoost (Dense) |
| Business features | None (video only) | None (video only) |
| Feature dimensions | 10 | 10 |
| Use case | Video-specific | Video-specific |

## Notes

1. **Feature order must be strictly consistent**: Python training and Kotlin inference feature order must be identical
2. **Normalization parameters must match**: Must use `SCALER_MEAN` and `SCALER_SCALE` calculated during training
3. **RSSI range**: Normalization range [-90, -30] consistent with `infer.py` defaults
4. **Model fallback**: If ONNX model fails to load, `ApRoamingModel` returns 0.5 (equal probability)

## Update Log

| Date | Update Content |
|------|------|
| 2026-04-23 | Initial version, using XGBoost dense model |
