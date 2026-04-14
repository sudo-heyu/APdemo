#!/usr/bin/env python3
"""
将 model_video_only.pkl 转换为 Android 可用的 ONNX 格式，并打印 Kotlin 所需的 StandardScaler 参数。

用法（在本文件所在目录下执行）：
    python convert_to_onnx.py

输出：
  1. ap_roaming_model_video.onnx  → 复制到 app/src/main/assets/
  2. 终端打印 SCALER_MEAN / SCALER_SCALE → 填入 ApRoamingModel.kt

依赖：
    pip install lightgbm onnxmltools skl2onnx numpy
"""

import pickle
import numpy as np

MODEL_PATH = "model_video_only.pkl"
ONNX_PATH  = "ap_roaming_model_video.onnx"


def print_scaler_kotlin(scaler, feature_cols):
    """打印 Kotlin 格式的 StandardScaler 参数"""
    print("\n// ===== 复制以下内容到 ApRoamingModel.kt =====")
    print("// 特征顺序:", feature_cols)
    print(f"private val SCALER_MEAN = doubleArrayOf(")
    for i, (name, val_) in enumerate(zip(feature_cols, scaler.mean_)):
        comma = "" if i == len(feature_cols) - 1 else ","
        print(f"    {val_}{comma}  // {name}")
    print(")")
    print(f"private val SCALER_SCALE = doubleArrayOf(")
    for i, (name, val_) in enumerate(zip(feature_cols, scaler.scale_)):
        comma = "" if i == len(feature_cols) - 1 else ","
        print(f"    {val_}{comma}  // {name}")
    print(")")
    print("// ============================================\n")


def convert_with_onnxmltools(model, feature_cols):
    from onnxmltools import convert_lightgbm
    from onnxmltools.convert.common.data_types import FloatTensorType
    n = len(feature_cols)
    initial_type = [("input", FloatTensorType([None, n]))]
    onnx_model = convert_lightgbm(model, initial_types=initial_type, target_opset=12)
    with open(ONNX_PATH, "wb") as f:
        f.write(onnx_model.SerializeToString())
    print(f"[OK] onnxmltools 转换成功: {ONNX_PATH}")
    print("     Kotlin 输入名称: \"input\"，输出索引 [1] 为概率序列")


def convert_with_skl2onnx(model, scaler, feature_cols):
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType
    from sklearn.pipeline import Pipeline
    pipeline = Pipeline([("scaler", scaler), ("clf", model)])
    n = len(feature_cols)
    initial_type = [("input", FloatTensorType([None, n]))]
    onnx_model = convert_sklearn(pipeline, initial_types=initial_type, target_opset=12)
    with open(ONNX_PATH, "wb") as f:
        f.write(onnx_model.SerializeToString())
    print(f"[OK] skl2onnx Pipeline 转换成功（Scaler 已内嵌）: {ONNX_PATH}")
    print("     Kotlin 无需手动标准化，SCALER_MEAN/SCALE 行可删除")
    print("     Kotlin 输入名称: \"input\"，输出索引 [1] 为概率序列")


def main():
    with open(MODEL_PATH, "rb") as f:
        model_obj = pickle.load(f)

    scaler      = model_obj["scaler"]
    model       = model_obj["model"]
    feature_cols = model_obj["feature_cols"]

    print(f"特征列 ({len(feature_cols)}): {feature_cols}")

    # 打印 Kotlin 参数
    print_scaler_kotlin(scaler, feature_cols)

    # 尝试转换
    try:
        import onnxmltools
        convert_with_onnxmltools(model, feature_cols)
    except ImportError:
        print("[提示] onnxmltools 未安装，尝试 skl2onnx Pipeline...")
        try:
            import skl2onnx
            convert_with_skl2onnx(model, scaler, feature_cols)
        except ImportError:
            print("[错误] 请安装 onnxmltools 或 skl2onnx:")
            print("       pip install onnxmltools   # 推荐，仅转 LightGBM")
            print("       pip install skl2onnx      # 备用，包含 Scaler")
            return

    print(f"\n下一步：")
    print(f"  cp {ONNX_PATH} ../../../../../assets/")
    print(f"  # 或复制到 app/src/main/assets/ap_roaming_model_video.onnx")


if __name__ == "__main__":
    main()
