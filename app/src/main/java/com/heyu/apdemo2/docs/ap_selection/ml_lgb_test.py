import os
import re
import itertools
import pickle
import argparse
import numpy as np
import pandas as pd
import csv
from matplotlib.colors import ListedColormap, BoundaryNorm
import matplotlib.pyplot as plt

# ---------- 配置 ----------
test_dir = "./ap_data_test/ap_data_20260227"
ap_count = 6

# 直播与游戏的众包分数
ap_scores_video = {0:72, 1:51, 2:79, 3:32, 4:65, 5:54}
ap_scores_game = {0:49, 1:100, 2:48, 3:97, 4:48, 5:96}

#ap_scores_video = {0:47, 1:73, 2:51, 3:34, 4:65, 5:50}
#ap_scores_game = {0:33, 1:100, 2:32, 3:100, 4:37, 5:100}


out_csv_down = "./eval_result/video_evaluation_results.csv"
out_csv_up = "./eval_result/game_evaluation_results.csv"
input_model = "model_combined.pkl"

# -----------------------------------------------------------------

def parse_position_to_xy_safe(pos):
    if pd.isna(pos):
        return (None, None)
    s = str(pos).strip().replace('"', "").replace("'", "")
    nums = re.findall(r"-?\d+\.?\d*", s)
    if len(nums) >= 2:
        try:
            x = int(round(float(nums[0])))
            y = int(round(float(nums[1])))
            return (x, y)
        except Exception:
            return (None, None)
    return (None, None)

def normalize_position_str(pos):
    x, y = parse_position_to_xy_safe(pos)
    if x is not None and y is not None:
        return f"{x},{y}"
    return str(pos).strip().replace('"', "").replace("'", "")

def get_position_bounds(df):
    xs, ys = [], []
    for p in df["position"].unique():
        x, y = parse_position_to_xy_safe(p)
        if x is None or y is None:
            continue
        xs.append(x)
        ys.append(y)

    if not xs:
        return 0, 0, 0, 0

    return min(xs), max(xs), min(ys), max(ys)


def read_csv_auto(path):

    def _clean_cell(x):
        if x is None:
            return ""
        return str(x).strip().strip('"').strip("'")

    def _is_index_like(x):
        s = _clean_cell(x)
        if s == "":
            return False
        return s.isdigit()

    def _fix_row_length(row, ncols):
        row = [_clean_cell(x) for x in row]
        while len(row) > ncols and row[-1] == "":
            row.pop()
        if len(row) == ncols + 1 and _is_index_like(row[0]):
            row = row[1:]
        while len(row) > ncols:
            row.pop()

        while len(row) < ncols:
            row.append("")
        return row

    with open(path, "r", newline="", encoding="utf-8-sig") as f:
        reader = csv.reader(f)
        rows = [row for row in reader if len(row) > 0]

    if not rows:
        raise ValueError(f"空文件: {path}")

    header = [_clean_cell(x) for x in rows[0]]

    header = [h if h != "" else f"Unnamed_{i}" for i, h in enumerate(header)]
    data = []
    for row in rows[1:]:
        fixed = _fix_row_length(row, len(header))
        data.append(fixed)

    df = pd.DataFrame(data, columns=header)

    df = df.loc[:, ~df.columns.astype(str).str.match(r"^Unnamed")]


    df.columns = [str(c).strip() for c in df.columns]
    return df

def find_uldelay_col(df):
    for c in df.columns:
        low = c.lower().replace(" ", "")
        if "uldelay" in low and "<100" in low:
            return c
    for c in df.columns:
        low = c.lower().replace(" ", "")
        if "uldelay" in low and "<" in low:
            return c
    for c in df.columns:
        if "uldelay" in c.lower():
            return c
    return None

def calc_test_rssi_range(df, rssi_col="avg_beacon_rssi", connected_col=None, fill_value=None):
    s = pd.to_numeric(df[rssi_col], errors="coerce")

    s = s[s != 0].dropna()

    if len(s) == 0:
        return -120.0, -30.0

    return float(s.min()), float(s.max())

# -------------------- 读取 down/all up 数据（按 ap） --------------------
def load_down_ap_dir(data_dir, ap_count=6):
    parts = []
    for ap in range(ap_count):
        fn = os.path.join(data_dir, f"ap_{ap}_down.csv")
        if not os.path.exists(fn):
            raise FileNotFoundError(fn)

        df = read_csv_auto(fn)
        df.columns = [c.strip() for c in df.columns]

        if "position" not in df.columns:
            raise ValueError(f"{fn} 中缺少 position 列，当前列: {df.columns.tolist()}")

        drcol = None
        rcol = None
        for c in df.columns:
            lc = c.lower()
            if "diff rx" in lc or "diff_rx" in lc or "diff rx bytes" in lc:
                drcol = c
            if "rssi" in lc:
                rcol = c

        if drcol is None:
            if "diff rx bytes" in df.columns:
                drcol = "diff rx bytes"
        if rcol is None:
            for c in df.columns:
                if "rssi" in c.lower():
                    rcol = c
                    break

        if drcol is None or rcol is None:
            raise ValueError(f"{fn} 缺少 diff_rx 或 rssi 列，当前列: {df.columns.tolist()}")

        sub = pd.DataFrame({
            "position": df["position"].astype(str).map(normalize_position_str),
            "diff_rx_bytes_down": pd.to_numeric(df.get(drcol, 0.0), errors="coerce").fillna(0.0),
            "avg_beacon_rssi": pd.to_numeric(df.get(rcol, 0.0), errors="coerce").fillna(0.0)
        })
        sub["ap_id"] = ap
        parts.append(sub)

    return pd.concat(parts, ignore_index=True)

def load_up_and_merge(down_dir, up_dir, ap_count=6):
    merged_rows = []
    for ap in range(ap_count):
        fn_down = os.path.join(down_dir, f"ap_{ap}_down.csv")
        fn_up = os.path.join(up_dir, f"ap_{ap}_up.csv")
        if not os.path.exists(fn_down):
            raise FileNotFoundError(fn_down)
        if not os.path.exists(fn_up):
            raise FileNotFoundError(fn_up)

        df_down = read_csv_auto(fn_down)
        df_down.columns = [c.strip() for c in df_down.columns]
        if "position" not in df_down.columns:
            raise ValueError(f"{fn_down} 中缺少 position 列，当前列: {df_down.columns.tolist()}")

        drcol, rcol = None, None
        for c in df_down.columns:
            lc = c.lower()
            if "diff rx" in lc or "diff_rx" in lc or "diff rx bytes" in lc:
                drcol = c
            if "rssi" in lc:
                rcol = c

        if drcol is None or rcol is None:
            raise ValueError(f"{fn_down} missing expected columns. Cols: {df_down.columns.tolist()}")

        sub_down = pd.DataFrame({
            "position": df_down["position"].astype(str).map(normalize_position_str),
            "diff_rx_bytes_down": pd.to_numeric(df_down.get(drcol, 0.0), errors="coerce").fillna(0.0),
            "avg_beacon_rssi": pd.to_numeric(df_down.get(rcol, 0.0), errors="coerce").fillna(0.0)
        })

        df_up = read_csv_auto(fn_up)
        df_up.columns = [c.strip() for c in df_up.columns]
        if "position" not in df_up.columns:
            raise ValueError(f"{fn_up} 中缺少 position 列，当前列: {df_up.columns.tolist()}")

        ucol = find_uldelay_col(df_up)
        if ucol is None:
            raise ValueError(f"{fn_up} 中找不到 uldelay 列，当前列: {df_up.columns.tolist()}")

        sub_up = pd.DataFrame({
            "position": df_up["position"].astype(str).map(normalize_position_str),
            "uldelay": pd.to_numeric(df_up.get(ucol, 0.0), errors="coerce").fillna(0.0),
            "diff_tx_bytes_up": pd.to_numeric(df_up.get("diff tx bytes", 0.0), errors="coerce").fillna(0.0),
            "total_latency_95_percent": pd.to_numeric(df_up.get("total_latency_95_percent", np.nan), errors="coerce").fillna(np.nan)
        })

        merged = pd.merge(sub_down, sub_up, on="position", how="outer")
        merged["ap_id"] = ap
        merged["diff_rx_bytes_down"] = pd.to_numeric(merged["diff_rx_bytes_down"], errors="coerce").fillna(0.0)
        merged["avg_beacon_rssi"] = pd.to_numeric(merged["avg_beacon_rssi"], errors="coerce").fillna(0.0)
        merged["uldelay"] = pd.to_numeric(merged["uldelay"], errors="coerce").fillna(0.0)
        merged["diff_tx_bytes_up"] = pd.to_numeric(merged["diff_tx_bytes_up"], errors="coerce").fillna(0.0)
        merged_rows.append(merged)

    return pd.concat(merged_rows, ignore_index=True)

# --------------------- 断连处理 & 填充值 ---------------------
def handle_disconnects_down(df):
    df = df.copy()
    df["is_connected"] = ~((df["avg_beacon_rssi"] == 0) & (df["diff_rx_bytes_down"] == 0))
    nonzero = df.loc[df["avg_beacon_rssi"] != 0, "avg_beacon_rssi"]
    if len(nonzero) > 0:
        fill = float(nonzero.min() - 10.0)
    else:
        fill = -120.0
    mask = (df["avg_beacon_rssi"] == 0) & (df["diff_rx_bytes_down"] == 0)
    df.loc[mask, "avg_beacon_rssi"] = fill
    return df, fill

def handle_disconnects_up(df):
    df = df.copy()
    df["connected_down"] = ~((df["avg_beacon_rssi"] == 0) & (df["diff_rx_bytes_down"] == 0))
    df["connected_up"] = ~((df["uldelay"] == 0) & (df["diff_tx_bytes_up"] == 0))

    nonzero_rssi = df.loc[df["avg_beacon_rssi"] != 0, "avg_beacon_rssi"]
    if len(nonzero_rssi) > 0:
        rssi_fill = float(nonzero_rssi.min() - 10.0)
    else:
        rssi_fill = -120.0

    mask_down_dis = (df["avg_beacon_rssi"] == 0) & (df["diff_rx_bytes_down"] == 0)
    df.loc[mask_down_dis, "avg_beacon_rssi"] = rssi_fill

    nonzero_ul = df.loc[df["uldelay"] > 0, "uldelay"]
    if len(nonzero_ul) > 0:
        u_fill = float(nonzero_ul.min() - 0.1)
    else:
        u_fill = -0.1

    mask_up_dis = (df["uldelay"] == 0) & (df["diff_tx_bytes_up"] == 0)
    df.loc[mask_up_dis, "uldelay"] = u_fill
    return df, rssi_fill, u_fill

def predict_pair_prob_from_model(model_obj, rssi_a_raw, score_a_raw, rssi_b_raw, score_b_raw,
                                 a_conn_down, b_conn_down, a_conn_up, b_conn_up, biz,
                                 rmin, rden, smax):
    """
    这里只允许 model_obj 出现在预测里。
    """
    ra = max(0.0, min(1.0, (float(rssi_a_raw) - rmin) / rden))
    rb = max(0.0, min(1.0, (float(rssi_b_raw) - rmin) / rden))
    sa = max(0.0, min(1.0, float(score_a_raw) / smax))
    sb = max(0.0, min(1.0, float(score_b_raw) / smax))

    feat_map = {
        "rssi_a": ra,
        "rssi_b": rb,
        "rssi_diff": ra - rb,
        "score_a": sa,
        "score_b": sb,
        "score_diff": sa - sb,
        "prod_a": ra * sa,
        "prod_b": rb * sb,
        "a_conn_down": int(bool(a_conn_down)),
        "b_conn_down": int(bool(b_conn_down)),
        "a_conn_up": int(bool(a_conn_up)),
        "b_conn_up": int(bool(b_conn_up)),
        "biz": int(biz)
    }

    feat = np.array([feat_map[n] for n in model_obj["feature_cols"]], dtype=float).reshape(1, -1)
    feat_s = model_obj["scaler"].transform(feat)
    prob = float(model_obj["model"].predict(feat_s)[0])
    return prob

def filter_by_borda(ap_list, rssi_vals, ap_scores_map, k=3):
    sorted_by_rssi = sorted(ap_list, key=lambda a: rssi_vals.get(a, -1e9), reverse=True)
    sorted_by_score = sorted(ap_list, key=lambda a: ap_scores_map.get(a, 0), reverse=True)
    N = len(ap_list)
    points = {a: 0 for a in ap_list}
    for idx, a in enumerate(sorted_by_rssi):
        points[a] += (N - idx)
    for idx, a in enumerate(sorted_by_score):
        points[a] += (N - idx)
    sorted_aps = sorted(ap_list, key=lambda a: points[a], reverse=True)
    return sorted_aps[:min(k, len(sorted_aps))]

def select_best_per_position_generic(all_df, model_obj, ap_scores, business, out_csv, rmin, rden):
    """
    model_obj 只在 predict_pair_prob_from_model 里使用。
    """
    DOWN_PCT = 0.95
    UP_PCT = 0.95
    TL_EPS = 1.5
    SCORE_PCT = 0.95

    results = []
    positions = all_df["position"].unique()

    for pos in positions:
        sub = all_df[all_df["position"] == pos].copy()
        if len(sub) == 0:
            continue

        if business == "down":
            sub["diff_rx"] = pd.to_numeric(sub.get("diff_rx_bytes_down", sub.get("diff rx bytes", 0.0)),
                                           errors="coerce").fillna(0.0).astype(float)
            sub["rssi"] = pd.to_numeric(sub.get("avg_beacon_rssi", 0.0), errors="coerce").fillna(0.0).astype(float)
            max_val = sub["diff_rx"].max()

            if pd.isna(max_val):
                gt_list = []
                gt_primary = None
            else:
                threshold = DOWN_PCT * max_val
                cand = sub[sub["diff_rx"] >= threshold].copy()
                gt_list = sorted(list(set([int(x) for x in cand["ap_id"].astype(int).tolist()])))

                chosen = sub[sub["diff_rx"] == sub["diff_rx"].max()].copy()
                if len(chosen) > 1:
                    max_rssi = chosen["rssi"].max()
                    chosen = chosen[chosen["rssi"] == max_rssi].copy()
                    if len(chosen) > 1 and ap_scores is not None:
                        chosen["ap_score"] = chosen["ap_id"].astype(int).map(lambda x: ap_scores.get(int(x), 0))
                        max_s = chosen["ap_score"].max()
                        chosen = chosen[chosen["ap_score"] == max_s].copy()
                gt_primary = int(chosen.iloc[0]["ap_id"])

        else:
            sub["ul"] = pd.to_numeric(sub.get("uldelay", 0.0), errors="coerce").fillna(0.0).astype(float)
            if "total_latency_95_percent" in sub.columns:
                sub["tl95"] = pd.to_numeric(sub["total_latency_95_percent"], errors="coerce")
            else:
                sub["tl95"] = np.nan
            sub["rssi"] = pd.to_numeric(sub.get("avg_beacon_rssi", 0.0), errors="coerce").fillna(0.0).astype(float)

            max_ul = sub["ul"].max()
            if pd.isna(max_ul):
                gt_list = []
                gt_primary = None
            else:
                threshold_ul = UP_PCT * max_ul
                cand = sub[sub["ul"] >= threshold_ul].copy()
                
                # tl_vals = cand["tl95"].dropna()
                # if len(tl_vals) > 0:
                #     min_tl = float(tl_vals.min())
                #     cand2 = cand[(cand["tl95"].isna() == False) & (cand["tl95"] <= (min_tl * TL_EPS))].copy()
                #     if len(cand2) > 0:
                #         cand = cand2
                # else:
                #     if ap_scores is not None and len(cand) > 0:
                #         cand["ap_score"] = cand["ap_id"].astype(int).map(lambda x: ap_scores.get(int(x), 0))
                #         max_s = cand["ap_score"].max()
                #         cand = cand[cand["ap_score"] >= (SCORE_PCT * max_s)].copy()
                #         if len(cand) == 0:
                #             cand = sub[sub["ul"] >= threshold_ul].copy()
                
                gt_list = sorted(list(set([int(x) for x in cand["ap_id"].astype(int).tolist()])))
                best_ul_rows = sub[sub["ul"] == sub["ul"].max()].copy()
                if len(best_ul_rows) > 1:
                    if "tl95" in best_ul_rows.columns and not best_ul_rows["tl95"].isna().all():
                        min_tl_all = best_ul_rows["tl95"].min()
                        best_ul_rows = best_ul_rows[best_ul_rows["tl95"] == min_tl_all].copy()
                    if len(best_ul_rows) > 1 and ap_scores is not None:
                        best_ul_rows["ap_score"] = best_ul_rows["ap_id"].astype(int).map(lambda x: ap_scores.get(int(x), 0))
                        max_s = best_ul_rows["ap_score"].max()
                        best_ul_rows = best_ul_rows[best_ul_rows["ap_score"] == max_s].copy()
                gt_primary = int(best_ul_rows.iloc[0]["ap_id"])

        if len(gt_list) == 0:
            try:
                if business == "down":
                    chosen = sub.loc[sub["diff_rx"].idxmax()]
                    gt_primary = int(chosen["ap_id"])
                    gt_list = [gt_primary]
                else:
                    chosen = sub.loc[sub["ul"].idxmax()]
                    gt_primary = int(chosen["ap_id"])
                    gt_list = [gt_primary]
            except Exception:
                gt_list = []
                gt_primary = None

        ap_map = {int(r["ap_id"]): r for i, r in sub.reset_index().iterrows()}
        ap_list = list(ap_map.keys())

        if len(ap_list) == 0:
            continue

        if len(ap_list) == 1:
            best_ap = ap_list[0]
        else:
            rssi_vals = {a: float(ap_map[a].get("avg_beacon_rssi", 0.0)) for a in ap_list}
            ap_scores_map = ap_scores

            sorted_aps = filter_by_borda(ap_list, rssi_vals, ap_scores_map)

            # 保留前 len-3 个，去掉后三名；如果不足 4 个则保留全部
            if len(sorted_aps) > 3:
                keep_count = len(sorted_aps) - 3
                filtered_ap_list = sorted_aps[:keep_count]
            else:
                filtered_ap_list = sorted_aps[:]

            if len(filtered_ap_list) == 0:
                filtered_ap_list = ap_list.copy()

            filtered_ap_list = ap_list.copy()

            prob_sums = {a: 0.0 for a in filtered_ap_list}
            win_counts = {a: 0 for a in filtered_ap_list}

            # score_max 固定为 100.0
            smax = 100.0
            biz_flag = 0 if business == "down" else 1

            for i, j in itertools.permutations(filtered_ap_list, 2):
                ai = ap_map[i]
                aj = ap_map[j]
                ri = float(ai.get("avg_beacon_rssi", 0.0))
                rj = float(aj.get("avg_beacon_rssi", 0.0))
                ai_cd = bool(ai.get("connected_down", ai.get("is_connected", False)))
                aj_cd = bool(aj.get("connected_down", aj.get("is_connected", False)))
                ai_cu = bool(ai.get("connected_up", ai.get("is_connected", False)))
                aj_cu = bool(aj.get("connected_up", aj.get("is_connected", False)))

                prob_i = predict_pair_prob_from_model(
                    model_obj,
                    ri, ap_scores.get(i, 0),
                    rj, ap_scores.get(j, 0),
                    ai_cd, aj_cd, ai_cu, aj_cu,
                    biz_flag,
                    rmin, rden, smax
                )
                prob_sums[i] += prob_i
                if prob_i >= 0.5:
                    win_counts[i] += 1

            best_ap = max(
                filtered_ap_list,
                key=lambda a: (
                    prob_sums[a] / max(1, (len(filtered_ap_list) - 1)),
                    win_counts[a],
                    (ap_scores.get(a, 0) if ap_scores is not None else 0)
                )
            )

        gt_list_str = ";".join([str(x) for x in gt_list])
        res_primary = int(gt_primary) if gt_primary is not None else (int(gt_list[0]) if len(gt_list) > 0 else None)
        is_correct = (int(best_ap) in gt_list)

        results.append({
            "position": pos,
            "model_best_ap": int(best_ap),
            "gt_best_ap": int(res_primary) if res_primary is not None else None,
            "gt_best_ap_list": gt_list_str,
            "is_in_gt_list": bool(is_correct)
        })

    res_df = pd.DataFrame(results)

    def _xy_tuple(p):
        t = re.findall(r"-?\d+\.?\d*", str(p))
        if len(t) >= 2:
            return (int(round(float(t[0]))), int(round(float(t[1]))))
        return (1e9, 1e9)

    res_df["__x"] = res_df["position"].apply(lambda p: _xy_tuple(p)[0])
    res_df["__y"] = res_df["position"].apply(lambda p: _xy_tuple(p)[1])
    res_df = res_df.sort_values(by=["__x", "__y"], ascending=[True, True], na_position="last").reset_index(drop=True)
    out_df = res_df.drop(columns=["__x", "__y"], errors="ignore")
    out_df.to_csv(out_csv, index=False)

    total = len(out_df)
    if total == 0:
        acc = float("nan")
        correct = 0
    else:
        correct = int(out_df["is_in_gt_list"].sum())
        acc = correct / total

    print(f"[{business}] 写入 {out_csv}，评估位置数={total}，模型选择在 gt_list 内的正确数={correct}，准确率={acc:.4f}")

    # 绘图：按测试集实际坐标范围自适应，支持负坐标
    x_min, x_max, y_min, y_max = get_position_bounds(out_df)
    grid_h = y_max - y_min + 1
    grid_w = x_max - x_min + 1
    grid = np.zeros((grid_h, grid_w), dtype=np.int8)

    parsed = 0
    for _, row in out_df.iterrows():
        x, y = parse_position_to_xy_safe(row["position"])
        if x is None or y is None:
            continue

        xi = x - x_min
        yi = y - y_min
        if not (0 <= xi < grid_w and 0 <= yi < grid_h):
            continue

        parsed += 1
        ok = bool(row.get("is_in_gt_list", False))
        grid[yi, xi] = 1 if ok else 2

    if parsed > 0:
        cmap = ListedColormap(["black", "white", "red"])
        norm = BoundaryNorm([0, 1, 2, 3], cmap.N)
        fig, ax = plt.subplots(figsize=(8, 10))

        ax.imshow(
            grid,
            cmap=cmap,
            norm=norm,
            origin="upper",
            extent=[x_min - 0.5, x_max + 0.5, y_max + 0.5, y_min - 0.5]
        )

        ax.set_title(f"{business.upper()} accuracy (list-based): {acc:.3f} (white=correct, red=wrong)")
        ax.set_xlim(x_min - 0.5, x_max + 0.5)
        ax.set_ylim(y_max + 0.5, y_min - 0.5)

        x_step = max(1, (x_max - x_min) // 8)
        y_step = max(1, (y_max - y_min) // 8)
        ax.set_xticks(list(range(x_min, x_max + 1, x_step)))
        ax.set_yticks(list(range(y_min, y_max + 1, y_step)))

        ax.set_aspect("equal", adjustable="box")
        ax.set_xlabel("x")
        ax.set_ylabel("y")
        ax.invert_yaxis()
        plot_name = out_csv.replace(".csv", "_accuracy_grid.png")
        fig.tight_layout()
        fig.savefig(plot_name, dpi=200)
        plt.close(fig)
        print(f"[{business}] 已保存网格图到: {plot_name}")
    else:
        print(f"[{business}] 无可绘制的位置，跳过绘图。")

    return out_df, acc

def load_model(path):
    with open(path, "rb") as f:
        return pickle.load(f)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=input_model, help="模型路径，例如 model_combined.pkl")
    parser.add_argument("--test_dir", default=test_dir, help="测试数据目录")
    args = parser.parse_args()

    model_obj = load_model(args.model)

    print("读取并处理 test down 文件...")
    down_test_df = load_down_ap_dir(args.test_dir, ap_count=ap_count)

    down_test_df, rfill_d = handle_disconnects_down(down_test_df)

    print("读取并处理 test up 文件并合并 down/up ...")
    up_test_df = load_up_and_merge(args.test_dir, args.test_dir, ap_count=ap_count)

    up_test_df, rfill_u, ufill = handle_disconnects_up(up_test_df)


    # 测试集自己的 RSSI 范围
    rmin_d, rmax_d = calc_test_rssi_range(down_test_df, fill_value=rfill_d, connected_col="is_connected")
    rden_d = max(rmax_d - rmin_d, 1e-9)

    rmin_u, rmax_u = calc_test_rssi_range(up_test_df, fill_value=rfill_u, connected_col="connected_down")
    rden_u = max(rmax_u - rmin_u, 1e-9)

    print("\n=== 评估 Down（直播）业务 on test set ===")
    res_down_df, acc_down = select_best_per_position_generic(
        down_test_df,
        model_obj,
        ap_scores_video,
        business="down",
        out_csv=out_csv_down,
        rmin=rmin_d,
        rden=rden_d
    )

    print("\n=== 评估 Up（游戏）业务 on test set ===")
    res_up_df, acc_up = select_best_per_position_generic(
        up_test_df,
        model_obj,
        ap_scores_game,
        business="up",
        out_csv=out_csv_up,
        rmin=rmin_u,
        rden=rden_u
    )

    print("\n=== 最终汇总 ===")
    print(f"Down (video) accuracy = {acc_down:.4f}, evaluated positions = {len(res_down_df)}")
    print(f"Up   (game)  accuracy = {acc_up:.4f}, evaluated positions = {len(res_up_df)}")

    combined = pd.concat([res_down_df.assign(biz="down"), res_up_df.assign(biz="up")], ignore_index=True)
    total = len(combined)
    correct = int((combined["is_in_gt_list"] == True).sum())
    print(f"Combined positions = {total}, Combined accuracy = {correct / total if total > 0 else float('nan'):.4f}")

if __name__ == "__main__":
    main()