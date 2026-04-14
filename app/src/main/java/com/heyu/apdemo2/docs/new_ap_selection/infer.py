#!/usr/bin/env python3
"""
model_video_only.pkl 批量推理脚本
从 ml_lgb_test.py 提取并适配视频专用模型（无 biz 特征）

使用示例：
    python infer.py --csv input.csv --rmin -90 --rmax -30 --output results.csv
"""

import pickle
import numpy as np
import argparse
import pandas as pd
from typing import Dict, Any


def load_model(model_path: str) -> Dict[str, Any]:
    """加载 video_only 模型"""
    with open(model_path, 'rb') as f:
        model_obj = pickle.load(f)
    print(f"已加载模型，特征列: {model_obj['feature_cols']}")

    # 检查模型是否包含 RSSI 范围参数
    if 'rssi_min' in model_obj and 'rssi_max' in model_obj:
        print(f"模型使用固定 RSSI 范围: [{model_obj['rssi_min']}, {model_obj['rssi_max']}]")
    else:
        # 默认范围 -90 ~ -30
        model_obj['rssi_min'] = -90.0
        model_obj['rssi_max'] = -30.0
        print(f"模型未指定 RSSI 范围，使用默认: [{model_obj['rssi_min']}, {model_obj['rssi_max']}]")

    return model_obj


def batch_predict_csv(model_obj: Dict[str, Any],
                      csv_path: str,
                      output_path: str = None,
                      rmin: float = None,
                      rmax: float = None):
    """
    从 CSV 文件批量推理

    CSV 格式要求列：
        rssi_a, rssi_b, score_a, score_b, a_conn, b_conn
    """
    df = pd.read_csv(csv_path)
    required_cols = ['rssi_a', 'rssi_b', 'score_a', 'score_b', 'a_conn', 'b_conn']

    for col in required_cols:
        if col not in df.columns:
            raise ValueError(f"CSV 缺少必要列: {col}")

    # 确定 RSSI 归一化范围（优先级：model_obj > 参数 > 默认）
    if 'rssi_min' in model_obj and 'rssi_max' in model_obj:
        rmin_use = model_obj['rssi_min']
        rmax_use = model_obj['rssi_max']
        print(f"使用模型保存的 RSSI 范围: [{rmin_use}, {rmax_use}]")
    elif rmin is not None and rmax is not None:
        rmin_use = rmin
        rmax_use = rmax
        print(f"使用命令行指定的 RSSI 范围: [{rmin_use}, {rmax_use}]")
    else:
        rmin_use = -90.0
        rmax_use = -30.0
        print(f"使用默认 RSSI 范围: [{rmin_use}, {rmax_use}]")

    rden = rmax_use - rmin_use
    if rden == 0:
        rden = 1.0
    score_max = 100.0

    results = []
    for idx, row in df.iterrows():
        # 1. RSSI 归一化
        ra = max(0.0, min(1.0, (float(row['rssi_a']) - rmin_use) / rden))
        rb = max(0.0, min(1.0, (float(row['rssi_b']) - rmin_use) / rden))

        # 2. 分数归一化
        sa = max(0.0, min(1.0, float(row['score_a']) / score_max))
        sb = max(0.0, min(1.0, float(row['score_b']) / score_max))

        # 3. 构建特征字典
        feat_map = {
            "rssi_a": ra,
            "rssi_b": rb,
            "rssi_diff": ra - rb,
            "score_a": sa,
            "score_b": sb,
            "score_diff": sa - sb,
            "prod_a": ra * sa,
            "prod_b": rb * sb,
            "a_conn": int(bool(row['a_conn'])),
            "b_conn": int(bool(row['b_conn'])),
        }

        # 4. 按模型特征列顺序提取特征
        feature_cols = model_obj['feature_cols']
        feat_vec = np.array([feat_map[col] for col in feature_cols], dtype=float).reshape(1, -1)

        # 5. 标准化并预测
        feat_scaled = model_obj['scaler'].transform(feat_vec)
        prob = float(model_obj['model'].predict(feat_scaled)[0])

        prediction = 1 if prob >= 0.5 else 0
        results.append({
            'idx': idx,
            'prob_a_better': prob,
            'prediction': prediction,
            'pred_label': 'A优于B' if prediction == 1 else 'B优于A'
        })

    results_df = pd.DataFrame(results)

    if output_path:
        results_df.to_csv(output_path, index=False)
        print(f"批量预测结果已保存到: {output_path}")

    return results_df


def main():
    parser = argparse.ArgumentParser(description='model_video_only.pkl 批量推理')
    parser.add_argument('--model', type=str, default='model_video_only.pkl',
                        help='模型路径 (默认: model_video_only.pkl)')
    parser.add_argument('--csv', type=str, required=True,
                        help='输入 CSV 文件路径，必须包含列: rssi_a, rssi_b, score_a, score_b, a_conn, b_conn')
    parser.add_argument('--output', type=str, help='输出结果文件路径（可选）')
    parser.add_argument('--rmin', type=float, default=-90.0,
                        help='RSSI 归一化最小值 (默认: -90.0)')
    parser.add_argument('--rmax', type=float, default=-30.0,
                        help='RSSI 归一化最大值 (默认: -30.0)')

    args = parser.parse_args()

    # 加载模型
    model_obj = load_model(args.model)

    print(f"从 {args.csv} 进行批量推理...")
    results_df = batch_predict_csv(
        model_obj,
        csv_path=args.csv,
        rmin=args.rmin,
        rmax=args.rmax,
        output_path=args.output
    )

    print(f"\n批量推理完成，共处理 {len(results_df)} 个样本")
    print(f"平均概率: {results_df['prob_a_better'].mean():.6f}")
    print(f"A优于B的比例: {(results_df['prediction'] == 1).mean():.2%}")


if __name__ == '__main__':
    main()