"""
将 model_combined.pkl (LightGBM + StandardScaler) 转换为 ONNX 格式。

用法:
    pip install onnxmltools onnxconverter-common skl2onnx lightgbm
    python convert_to_onnx.py

输出:
    - ap_roaming_model.onnx   : ONNX 模型文件（供 Android 使用）
    - 控制台打印 scaler 的 mean_ 和 scale_（需嵌入 Android 代码）
"""

import pickle
import numpy as np
import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType

def main():
    # 1. 加载 pkl
    with open("model_combined.pkl", "rb") as f:
        model_obj = pickle.load(f)

    bst = model_obj["model"]
    scaler = model_obj["scaler"]
    feature_cols = model_obj["feature_cols"]
    score_max = model_obj["score_max"]

    print("=== 模型信息 ===")
    print(f"特征列: {feature_cols}")
    print(f"特征数: {len(feature_cols)}")
    print(f"score_max: {score_max}")

    # 2. 打印 StandardScaler 参数（嵌入 Android）
    print("\n=== StandardScaler 参数 ===")
    print(f"mean_ = {scaler.mean_.tolist()}")
    print(f"scale_ = {scaler.scale_.tolist()}")

    # 格式化为 Kotlin 数组
    mean_str = ", ".join([f"{v}" for v in scaler.mean_])
    scale_str = ", ".join([f"{v}" for v in scaler.scale_])
    print(f"\n// Kotlin 常量:")
    print(f"private val SCALER_MEAN = doubleArrayOf({mean_str})")
    print(f"private val SCALER_SCALE = doubleArrayOf({scale_str})")

    # 3. 转换 LightGBM 模型为 ONNX
    num_features = len(feature_cols)
    onnx_model = onnxmltools.convert_lightgbm(
        bst,
        initial_types=[("features", FloatTensorType([None, num_features]))],
        target_opset=11
    )

    # 4. 保存 ONNX 模型
    output_path = "ap_roaming_model.onnx"
    onnxmltools.utils.save_model(onnx_model, output_path)
    print(f"\nONNX 模型已保存到: {output_path}")

    # 5. 验证 ONNX 模型
    try:
        import onnxruntime as ort
        session = ort.InferenceSession(output_path)
        input_name = session.get_inputs()[0].name
        output_names = [o.name for o in session.get_outputs()]
        print(f"\n=== ONNX 验证 ===")
        print(f"输入名: {input_name}")
        print(f"输出名: {output_names}")

        # 用一条随机数据测试
        test_input = np.random.randn(1, num_features).astype(np.float32)
        result = session.run(None, {input_name: test_input})
        print(f"测试输出 (label): {result[0]}")
        if len(result) > 1:
            print(f"测试输出 (probabilities): {result[1]}")
        print("ONNX 模型验证通过!")
    except ImportError:
        print("\n(onnxruntime 未安装，跳过验证)")

if __name__ == "__main__":
    main()
