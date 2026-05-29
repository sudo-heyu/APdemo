#!/usr/bin/env python3
"""
Convert XGBoost model to ONNX - Method: Directly modify feature_names attribute
"""

import pickle
import numpy as np
import os
import sys
import xgboost as xgb

MODEL_PATH = "model_video_only_xgb_dense.pkl"
ONNX_PATH  = "ap_roaming_model_dense.onnx"


class XGBProbModel:
    def __init__(self):
        self.booster = None
    def __setstate__(self, state):
        self.__dict__.update(state)


sys.modules['__main__'].XGBProbModel = XGBProbModel


def main():
    print("Loading model...")
    with open(MODEL_PATH, "rb") as f:
        model_obj = pickle.load(f)

    scaler = model_obj["scaler"]
    feature_cols = model_obj["feature_cols"]
    xgb_model = model_obj["model"]
    booster = xgb_model.booster

    n = len(feature_cols)
    print(f"Features ({n}): {feature_cols}")
    print(f"Trees: {booster.num_boosted_rounds()}")

    # Method 1: Create new booster with renamed features
    print("\n[Method 1] Create new booster with f0-f9 features...")

    # Save current model
    booster.save_model("temp_original.ubj")

    # Create new booster with modified feature names
    new_booster = xgb.Booster()

    # Load the model
    new_booster.load_model("temp_original.ubj")

    # Try to set feature names
    try:
        new_booster.feature_names = [f"f{i}" for i in range(n)]
        print(f"Set feature names: {new_booster.feature_names}")
    except Exception as e:
        print(f"Could not set feature_names: {e}")

    # Try conversion
    from onnxmltools import convert_xgboost
    from onnxmltools.convert.common.data_types import FloatTensorType

    initial_type = [("input", FloatTensorType([None, n]))]

    try:
        onnx_model = convert_xgboost(new_booster, initial_types=initial_type, target_opset=12)
        with open(ONNX_PATH, "wb") as f:
            f.write(onnx_model.SerializeToString())
        print(f"[OK] Conversion successful: {ONNX_PATH}")
        verify_model()
        return True
    except Exception as e:
        print(f"[Failed] Method 1: {e}")

    # Method 2: Manually build ONNX TreeEnsemble
    print("\n[Method 2] Manually build ONNX from tree dump...")
    try:
        import onnx
        from onnx import helper, TensorProto, numpy_helper
        import json

        # Get tree dump
        dump = booster.get_dump(dump_format='json')
        trees = [json.loads(t) for t in dump]
        print(f"Dumped {len(trees)} trees")

        # Build ONNX TreeEnsembleClassifier
        nodes = []
        initializers = []

        # Build tree nodes for TreeEnsembleClassifier
        # This is complex - need to parse each tree
        node_ids = []
        tree_ids = []
        node_modes = []
        feature_ids = []
        thresholds = []
        values = []
        left_child = []
        right_child = []
        default_left = []

        base_score = 0.5
        for node in booster.attributes():
            if 'base_score' in node:
                base_score = float(booster.attributes()['base_score'])
                break

        node_id = 0
        for tree_id, tree in enumerate(trees):
            def parse_tree(node, tree_id, local_id):
                nonlocal node_id
                current_id = node_id
                node_id += 1

                if 'leaf' in node:
                    # Leaf node
                    node_ids.append(current_id)
                    tree_ids.append(tree_id)
                    node_modes.append('LEAF')
                    # For XGBoost binary classification, leaf value is raw score
                    values.append(node['leaf'])
                    left_child.append(-1)
                    right_child.append(-1)
                    feature_ids.append(0)
                    thresholds.append(0.0)
                    default_left.append(True)
                else:
                    # Split node
                    node_ids.append(current_id)
                    tree_ids.append(tree_id)
                    node_modes.append('BRANCH_LEQ')
                    feature_ids.append(int(node['split'].replace('f', '')) if node['split'].startswith('f') else 0)
                    thresholds.append(node['split_condition'])
                    default_left.append(True)

                    # Parse children
                    if 'children' in node:
                        left = node['children'][0]
                        right = node['children'][1]
                        left_id = node_id
                        left_child.append(left_id)
                        right_child.append(node_id + 1)
                        parse_tree(left, tree_id, left_id)
                        parse_tree(right, tree_id, left_id + 1)
                    else:
                        left_child.append(-1)
                        right_child.append(-1)

            parse_tree(tree, tree_id, 0)

        print(f"Parsed {len(node_ids)} nodes")

        # Create TreeEnsembleClassifier
        input_tensor = helper.make_tensor_value_info('input', TensorProto.FLOAT, [None, n])

        # Output tensors
        output_label = helper.make_tensor_value_info('label', TensorProto.INT64, [None])
        output_prob = helper.make_tensor_value_info('probabilities', TensorProto.FLOAT, [None, 2])

        # Build attribute tensors
        nodes_featureids = numpy_helper.from_array(np.array(feature_ids, dtype=np.int64), name='nodes_featureids')
        nodes_nodeids = numpy_helper.from_array(np.array(node_ids, dtype=np.int64), name='nodes_nodeids')
        nodes_treeids = numpy_helper.from_array(np.array(tree_ids, dtype=np.int64), name='nodes_treeids')

        # Create node modes as strings
        node_modes_bytes = [m.encode('utf-8') for m in node_modes]

        # TreeEnsembleClassifier node
        tree_node = helper.make_node(
            'TreeEnsembleClassifier',
            inputs=['input'],
            outputs=['label', 'probabilities'],
            domain='ai.onnx.ml',
            nodes_treeids=tree_ids,
            nodes_nodeids=node_ids,
            nodes_featureids=feature_ids,
            nodes_values=thresholds,
            nodes_hitrates=[1.0] * len(node_ids),
            nodes_modes=node_modes,
            nodes_truenodeids=left_child,
            nodes_falsenodeids=right_child,
            nodes_missing_value_tracks_true=default_left,
            class_ids=[0] * len(values),
            class_nodeids=node_ids,
            class_treeids=tree_ids,
            class_weights=values,
            classlabels_int64s=[0, 1],
            base_values=[base_score] if base_score else None
        )

        graph = helper.make_graph([tree_node], 'xgboost_model', [input_tensor], [output_label, output_prob])
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid('ai.onnx.ml', 3), helper.make_opsetid('', 12)])

        with open(ONNX_PATH, "wb") as f:
            f.write(model.SerializeToString())
        print(f"[OK] Manual ONNX build: {ONNX_PATH}")
        verify_model()
        return True

    except Exception as e:
        print(f"[Failed] Method 2: {e}")
        import traceback
        traceback.print_exc()

    # Method 3: Use lightgbm as intermediate
    print("\n[Method 3] Convert XGBoost -> LightGBM -> ONNX...")
    try:
        import lightgbm as lgb

        # Export XGBoost trees to LightGBM format
        # This is tricky but possible

        # Save XGBoost model
        booster.save_model("temp_xgb.json")

        # Read and convert to LightGBM format
        with open("temp_xgb.json", "r") as f:
            xgb_data = json.load(f)

        print(f"XGBoost model structure keys: {xgb_data.keys()}")

        # LightGBM model format is different
        # Let's try a different approach: re-predict and train LightGBM

        # Generate training data from XGBoost predictions
        print("Generating synthetic training data from XGBoost...")
        np.random.seed(42)
        X_synthetic = np.random.rand(10000, n).astype(np.float32)

        # Get XGBoost predictions
        dmat = xgb.DMatrix(X_synthetic)
        y_xgb = booster.predict(dmat)

        # Train LightGBM to mimic XGBoost
        lgb_train = lgb.Dataset(X_synthetic, y_xgb)
        lgb_params = {
            'objective': 'binary',
            'num_leaves': 31,
            'learning_rate': 0.1,
            'n_estimators': 100,
            'verbose': -1
        }

        lgb_model = lgb.train(lgb_params, lgb_train, num_boost_round=200)

        # Convert LightGBM to ONNX
        from onnxmltools import convert_lightgbm
        initial_type = [("input", FloatTensorType([None, n]))]
        onnx_model = convert_lightgbm(lgb_model, initial_types=initial_type, target_opset=12)

        with open(ONNX_PATH, "wb") as f:
            f.write(onnx_model.SerializeToString())
        print(f"[OK] XGBoost -> LightGBM -> ONNX: {ONNX_PATH}")
        verify_model()
        return True

    except Exception as e:
        print(f"[Failed] Method 3: {e}")
        import traceback
        traceback.print_exc()

    print("\n[All methods failed]")
    return False


def verify_model():
    import onnxruntime as ort
    import onnx

    model = onnx.load(ONNX_PATH)
    onnx.checker.check_model(model)
    print(f"[OK] ONNX model is valid")

    sess = ort.InferenceSession(ONNX_PATH)
    print(f"ONNX inputs: {[i.name for i in sess.get_inputs()]}")
    print(f"ONNX outputs: {[o.name for o in sess.get_outputs()]}")

    test_input = np.random.randn(2, 10).astype(np.float32)
    result = sess.run(None, {sess.get_inputs()[0].name: test_input})
    print(f"Test output shapes: {[r.shape for r in result]}")


if __name__ == "__main__":
    main()
