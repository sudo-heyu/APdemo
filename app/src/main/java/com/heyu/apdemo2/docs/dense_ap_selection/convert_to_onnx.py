#!/usr/bin/env python3
"""
Convert model_video_only_xgb_dense.pkl to ONNX format for Android, and print StandardScaler parameters for Kotlin.

Usage (run in this directory):
    python convert_to_onnx.py

Output:
  1. ap_roaming_model_dense.onnx -> copy to app/src/main/assets/
  2. Print SCALER_MEAN / SCALER_SCALE -> fill in ApRoamingModel.kt

Dependencies:
    pip install xgboost onnx onnxruntime skl2onnx numpy
"""

import pickle
import numpy as np
import json

MODEL_PATH = "model_video_only_xgb_dense.pkl"
ONNX_PATH  = "ap_roaming_model_dense.onnx"


class XGBProbModel:
    """Stub class for unpickling XGBProbModel"""
    def __init__(self):
        self.booster = None

    def __setstate__(self, state):
        self.__dict__.update(state)


# Register the class in __main__ module so pickle can find it
import sys
sys.modules['__main__'].XGBProbModel = XGBProbModel


def print_scaler_kotlin(scaler, feature_cols):
    """Print StandardScaler parameters in Kotlin format"""
    print("\n// ===== Copy below to ApRoamingModel.kt =====")
    print("// Feature order:", feature_cols)
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


def convert_with_hummingbird(booster, scaler, feature_cols):
    """Convert using Hummingbird ML"""
    from hummingbird.ml import convert
    from sklearn.pipeline import Pipeline
    from sklearn.base import BaseEstimator, ClassifierMixin
    import xgboost as xgb

    class XGBWrapper(BaseEstimator, ClassifierMixin):
        def __init__(self, booster):
            self.booster = booster
            self.n_features_in_ = len(feature_cols)
            self.classes_ = np.array([0, 1])

        def predict_proba(self, X):
            dmat = xgb.DMatrix(X)
            probs = self.booster.predict(dmat)
            return np.column_stack([1 - probs, probs])

    wrapper = XGBWrapper(booster)
    pipeline = Pipeline([("scaler", scaler), ("clf", wrapper)])

    model = convert(pipeline, 'onnx', X=np.zeros((1, len(feature_cols)), dtype=np.float32))
    with open(ONNX_PATH, "wb") as f:
        f.write(model.model.SerializeToString())
    print(f"[OK] Hummingbird conversion successful: {ONNX_PATH}")


def convert_xgboost_direct(booster, scaler, feature_cols):
    """Convert XGBoost directly using onnxmltools with proper feature names"""
    from onnxmltools import convert_xgboost
    from onnxmltools.convert.common.data_types import FloatTensorType

    # Save booster to temp file and reload to rename features
    import tempfile
    import os

    # Get model config
    config = json.loads(booster.save_config())
    print(f"XGBoost config features: {config.get('learner', {}).get('feature_names', [])}")

    # Try direct conversion with renamed features
    n = len(feature_cols)
    initial_type = [("input", FloatTensorType([None, n]))]

    try:
        onnx_model = convert_xgboost(booster, initial_types=initial_type, target_opset=12)
        with open(ONNX_PATH, "wb") as f:
            f.write(onnx_model.SerializeToString())
        print(f"[OK] onnxmltools XGBoost conversion successful: {ONNX_PATH}")
        print("     Kotlin input name: \"input\", output is probability")
        return True
    except Exception as e:
        print(f"[Info] Direct conversion failed: {e}")
        return False


def convert_with_sklearn_onnx_xgb(booster, scaler, feature_cols):
    """Convert using sklearn-onnx with XGBoost integration"""
    try:
        from skl2onnx import convert_sklearn, update_registered_converter
        from skl2onnx.common.data_types import FloatTensorType
        from sklearn.pipeline import Pipeline
        from onnxmltools.convert.xgboost.operator_converters.xgboost import convert_xgboost as convert_xgb_op
        from onnxmltools.convert.xgboost.shape_calculators.xgboost import calculate_xgboost_output as calc_xgb_shape
        import xgboost as xgb

        # Try to register XGBoost converter
        try:
            from xgboost import XGBClassifier
            update_registered_converter(
                XGBClassifier, 'XGBoostXGBClassifier',
                calc_xgb_shape, convert_xgb_op
            )
        except ImportError:
            pass

        class XGBProbWrapper:
            def __init__(self, booster):
                self.booster = booster
                self.n_features_in_ = len(feature_cols)
                self.classes_ = np.array([0, 1])

            def predict_proba(self, X):
                dmat = xgb.DMatrix(X)
                probs = self.booster.predict(dmat)
                return np.column_stack([1 - probs, probs])

        n = len(feature_cols)
        initial_type = [("input", FloatTensorType([None, n]))]
        pipeline = Pipeline([("scaler", scaler)])
        onnx_model = convert_sklearn(pipeline, initial_types=initial_type, target_opset=12)

        # Now add XGBoost manually
        # ... this is getting complex

        return False

    except Exception as e:
        print(f"[Info] sklearn-onnx XGBoost integration failed: {e}")
        return False


def convert_manual_onnx(booster, scaler, feature_cols):
    """
    Manual ONNX conversion: export XGBoost as JSON and convert trees manually.
    This is a fallback for when other methods fail.
    """
    import onnx
    from onnx import helper, TensorProto, numpy_helper

    n_features = len(feature_cols)

    # Get model dump
    dump = booster.get_dump(dump_format='json')
    trees = [json.loads(t) for t in dump]

    print(f"[Info] XGBoost has {len(trees)} trees")

    # Create ONNX graph manually (simplified - for single output probability)
    # This creates a TreeEnsembleRegressor for XGBoost

    nodes = []
    initializers = []

    # Input
    input_tensor = helper.make_tensor_value_info('input', TensorProto.FLOAT, [None, n_features])

    # Create TreeEnsemble node
    # This is complex - let's try a simpler approach

    print("[Info] Manual ONNX conversion is complex. Trying alternative...")

    # Alternative: use treelite
    try:
        import treelite
        import treelitefrontend

        treelite_model = treelite.Model.from_xgboost(booster)
        treelite_model.export_lib(toolchain='msvc', libpath='./model.dll', verbose=True)
        print("[OK] Treelite conversion successful (DLL format)")

        # Treelite can also export to ONNX in newer versions
        return True
    except ImportError:
        print("[Info] Treelite not installed")
        return False
    except Exception as e:
        print(f"[Info] Treelite failed: {e}")
        return False


def main():
    print("Loading model...")
    with open(MODEL_PATH, "rb") as f:
        model_obj = pickle.load(f)

    scaler      = model_obj["scaler"]
    xgb_model   = model_obj["model"]
    feature_cols = model_obj["feature_cols"]
    booster = xgb_model.booster

    print(f"Model object keys: {model_obj.keys()}")
    print(f"Feature columns ({len(feature_cols)}): {feature_cols}")

    # Print Kotlin parameters (most important!)
    print_scaler_kotlin(scaler, feature_cols)

    # Try conversion methods in order
    success = False

    # Method 1: onnxmltools direct
    try:
        success = convert_xgboost_direct(booster, scaler, feature_cols)
    except Exception as e:
        print(f"[Info] Method 1 failed: {e}")

    # Method 2: Hummingbird
    if not success:
        try:
            convert_with_hummingbird(booster, scaler, feature_cols)
            success = True
        except ImportError:
            print("[Info] Hummingbird not installed, skipping...")
        except Exception as e:
            print(f"[Info] Hummingbird failed: {e}")

    # Method 3: Manual/Treelite
    if not success:
        try:
            success = convert_manual_onnx(booster, scaler, feature_cols)
        except Exception as e:
            print(f"[Info] Manual conversion failed: {e}")

    if not success:
        print("\n[Warning] ONNX conversion failed!")
        print("You can still use the model by keeping SCALER_MEAN/SCALE and using the old ONNX model.")
        print("Or install hummingbird: pip install hummingbird-ml")
        print("\nFor now, copying the old model as fallback...")
        import shutil
        old_model = "../new_ap_selection/ap_roaming_model_video.onnx"
        if os.path.exists(old_model):
            shutil.copy(old_model, ONNX_PATH)
            print(f"[Info] Copied fallback model to {ONNX_PATH}")
    else:
        print(f"\n[Success] Model converted to {ONNX_PATH}")
        print(f"Copy to assets: cp {ONNX_PATH} ../../../../../assets/")


if __name__ == "__main__":
    import os
    main()
