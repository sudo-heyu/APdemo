# AP Roaming Model Integration Guide

## Current Model Status

| Item | Value |
|------|-------|
| Model Name | `ap_roaming_model_video.onnx` |
| Model Type | LightGBM |
| Source Directory | `docs/new_ap_selection/` |
| Asset Path | `app/src/main/assets/ap_roaming_model_video.onnx` |
| Kotlin Class | `roaming/ApRoamingModel.kt` |

## Files to Modify When Switching Models

### 1. Core Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/heyu/apdemo2/roaming/ApRoamingModel.kt` | Model inference, SCALER parameters |
| `app/src/main/assets/*.onnx` | ONNX model file |

### 2. ApRoamingModel.kt Configuration Points

```kotlin
companion object {
    // [1] Model file name (must match asset filename)
    private const val MODEL_NAME = "ap_roaming_model_video.onnx"
    
    // [2] Input tensor name (usually "input" for onnxmltools conversion)
    private const val INPUT_NAME = "input"
    
    // [3] Number of features (currently 10)
    private const val NUM_FEATURES = 10
    
    // [4] SCALER parameters - MUST match training script output
    private val SCALER_MEAN = doubleArrayOf(
        0.49083859067711855,  // rssi_a
        0.4908385906771184,   // rssi_b
        -3.5723122770303585e-19,  // rssi_diff
        0.6425999703372633,   // score_a
        0.6425999703372629,   // score_b
        -4.1081591185849125e-19,  // score_diff
        0.31202489710403214,  // prod_a
        0.3120248971040321,   // prod_b
        0.9678209577293426,   // a_conn
        0.9678209577293426    // b_conn
    )
    private val SCALER_SCALE = doubleArrayOf(
        0.21116770088905168,  // rssi_a
        0.21116770088905168,  // rssi_b
        0.294505171203483,    // rssi_diff
        0.23394000569251036,  // score_a
        0.23394000569251028,  // score_b
        0.33366565945121557,  // score_diff
        0.17740493072407978,  // prod_a
        0.17740493072407978,  // prod_b
        0.1764753566626248,   // a_conn
        0.17647535666262476   // b_conn
    )
}
```

## Feature Specification

| Index | Feature Name | Description | Formula |
|-------|--------------|-------------|---------|
| 0 | `rssi_a` | AP A RSSI normalized | `(rssi - (-90)) / 60`, clipped [0,1] |
| 1 | `rssi_b` | AP B RSSI normalized | `(rssi - (-90)) / 60`, clipped [0,1] |
| 2 | `rssi_diff` | RSSI difference | `rssi_a - rssi_b` |
| 3 | `score_a` | AP A score normalized | `score / 100`, clipped [0,1] |
| 4 | `score_b` | AP B score normalized | `score / 100`, clipped [0,1] |
| 5 | `score_diff` | Score difference | `score_a - score_b` |
| 6 | `prod_a` | AP A combined | `rssi_a * score_a` |
| 7 | `prod_b` | AP B combined | `rssi_b * score_b` |
| 8 | `a_conn` | AP A connectable | 0 or 1 |
| 9 | `b_conn` | AP B connectable | 0 or 1 |

**Normalization ranges:**
- RSSI: [-90, -30] dBm
- Score: [0, 100]

**Output:** Probability that AP A is better than AP B (0.0 ~ 1.0)

## Step-by-Step: Switch to a New Model

### Step 1: Prepare New Model

Ensure the new model has:
- Same 10 features in same order
- Output format compatible with ONNX Runtime (label + probabilities)

### Step 2: Convert to ONNX

For **LightGBM** models:
```bash
cd docs/new_ap_selection/
python convert_to_onnx.py
# Output: ap_roaming_model_video.onnx + SCALER parameters
```

For **XGBoost** models:
```bash
cd docs/dense_ap_selection/
python convert_to_onnx.py
# Note: XGBoost requires feature names f0-f9 for onnxmltools
```

**Important:** If conversion fails due to feature names, modify the booster:
```python
import xgboost as xgb
booster = xgb.Booster()
booster.load_model("model.ubj")
booster.feature_names = [f"f{i}" for i in range(10)]  # Rename features
# Then convert with onnxmltools
```

### Step 3: Copy ONNX to Assets

```bash
cp <new_model>.onnx app/src/main/assets/
```

### Step 4: Update ApRoamingModel.kt

1. Change `MODEL_NAME` to new model filename
2. Update `SCALER_MEAN` and `SCALER_SCALE` from conversion script output
3. Verify `NUM_FEATURES` matches

### Step 5: Verify

1. Build and run app
2. Check logcat for: `D/[ApRoamingModel]: Video-specific ONNX model loaded successfully`
3. Test roaming predictions

## ONNX Conversion Troubleshooting

### Problem: Feature name error
```
Unable to interpret 'score_diff', feature names should follow pattern 'f%d'
```
**Solution:** Rename features to f0, f1, f2... before conversion:
```python
booster.feature_names = [f"f{i}" for i in range(n_features)]
```

### Problem: Pickle class not found
```
AttributeError: Can't get attribute 'XGBProbModel'
```
**Solution:** Define stub class before unpickling:
```python
class XGBProbModel:
    def __init__(self):
        self.booster = None
    def __setstate__(self, state):
        self.__dict__.update(state)
sys.modules['__main__'].XGBProbModel = XGBProbModel
```

### Problem: sklearn version warning
```
InconsistentVersionWarning: Trying to unpickle from version X.X.X
```
**Solution:** This is a warning only; scaler values are still correct.

## Model Comparison

| Feature | LightGBM (current) | XGBoost (dense) |
|---------|-------------------|-----------------|
| File | `ap_roaming_model_video.onnx` | `ap_roaming_model_dense.onnx` |
| Size | ~1.1 MB | ~278 KB |
| Trees | - | 220 |
| Source | `docs/new_ap_selection/` | `docs/dense_ap_selection/` |

## Related Documentation

- `docs/new_ap_selection/README.md` - LightGBM model details
- `docs/dense_ap_selection/README.md` - XGBoost dense model details
- `docs/roaming/ROAMING_IMPLEMENTATION_ANALYSIS.md` - Roaming algorithm overview
