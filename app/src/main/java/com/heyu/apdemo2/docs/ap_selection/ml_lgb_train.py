import os
import itertools
import pickle
import numpy as np
import pandas as pd
import lightgbm as lgb
import csv
from sklearn.preprocessing import StandardScaler

# ---------- 多场景配置 ----------
train_dirs = [
    "./ap_data_train/ap_data_20260129",
    #"./ap_data_train/ap_data_20260227",
    #"./ap_data_train/ap_data_20260323",
]

ap_scores_video_list = [
    {0:72, 1:51, 2:79, 3:32, 4:65, 5:54},
    #{0:72, 1:51, 2:79, 3:32, 4:65, 5:54},
    #{0:47, 1:73, 2:51, 3:34, 4:65, 5:50},

]


ap_scores_game_list = [
    {0:49, 1:100, 2:48, 3:97, 4:48, 5:96},
    #{0:49, 1:100, 2:48, 3:97, 4:48, 5:96},
    #{0:33, 1:100, 2:32, 3:100, 4:37, 5:100},

]

ap_count = 6
output_model = "model_combined.pkl"
score_max = 100.0

# -----------------------------------------------------------------

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

def load_down_ap_dir(data_dir, ap_count=6):
    parts = []
    for ap in range(ap_count):
        fn = os.path.join(data_dir, f"ap_{ap}_down.csv")
        if not os.path.exists(fn):
            raise FileNotFoundError(fn)

        df = read_csv_auto(fn)
        df.columns = [c.strip() for c in df.columns]

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
            "position": df["position"].astype(str),
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
            "position": df_down["position"].astype(str),
            "diff_rx_bytes_down": pd.to_numeric(df_down.get(drcol, 0.0), errors="coerce").fillna(0.0),
            "avg_beacon_rssi": pd.to_numeric(df_down.get(rcol, 0.0), errors="coerce").fillna(0.0)
        })

        df_up = read_csv_auto(fn_up)
        df_up.columns = [c.strip() for c in df_up.columns]
        ucol = find_uldelay_col(df_up)
        if ucol is None:
            raise ValueError(f"{fn_up} 中找不到 uldelay 列，当前列: {df_up.columns.tolist()}")

        sub_up = pd.DataFrame({
            "position": df_up["position"].astype(str),
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

def build_pairs_down(all_df_down, ap_scores, rssi_min=None, rssi_max=None, score_max=100.0):
    df = all_df_down.copy()
    nonzero_rssis = df.loc[df["avg_beacon_rssi"] != 0, "avg_beacon_rssi"]
    if rssi_min is None or rssi_max is None:
        if len(nonzero_rssis) > 0:
            rmin = float(nonzero_rssis.min())
            rmax = float(nonzero_rssis.max())
        else:
            rmin, rmax = -120.0, -30.0
    else:
        rmin, rmax = rssi_min, rssi_max
    rden = rmax - rmin if (rmax - rmin) != 0 else 1.0

    rows = []
    for pos in df["position"].unique():
        sub = df[df["position"] == pos]
        if len(sub) < 2:
            continue

        for a_idx, b_idx in itertools.permutations(sub.index, 2):
            a = sub.loc[a_idx]
            b = sub.loc[b_idx]

            a_conn = not ((a["avg_beacon_rssi"] == rmin) and (a["diff_rx_bytes_down"] == 0))
            b_conn = not ((b["avg_beacon_rssi"] == rmin) and (b["diff_rx_bytes_down"] == 0))
            if (not a_conn) and (not b_conn):
                continue

            raw_ra = float(a["avg_beacon_rssi"])
            raw_rb = float(b["avg_beacon_rssi"])
            ra = max(0.0, min(1.0, (raw_ra - rmin) / rden))
            rb = max(0.0, min(1.0, (raw_rb - rmin) / rden))

            sa = max(0.0, min(1.0, float(ap_scores.get(int(a["ap_id"]), 0)) / score_max))
            sb = max(0.0, min(1.0, float(ap_scores.get(int(b["ap_id"]), 0)) / score_max))

            diff_a = float(a["diff_rx_bytes_down"])
            diff_b = float(b["diff_rx_bytes_down"])

            if a_conn and (not b_conn):
                label = 1
            elif (not a_conn) and b_conn:
                label = 0
            else:
                label = 1 if diff_a > diff_b else 0

            rows.append({
                "position": pos,
                "ap_a": int(a["ap_id"]),
                "ap_b": int(b["ap_id"]),
                "rssi_a": ra,
                "rssi_b": rb,
                "rssi_diff": ra - rb,
                "score_a": sa,
                "score_b": sb,
                "score_diff": sa - sb,
                "prod_a": ra * sa,
                "prod_b": rb * sb,
                "a_conn_down": int(a_conn),
                "b_conn_down": int(b_conn),
                "a_conn_up": 0,
                "b_conn_up": 0,
                "biz": 0,
                "label": int(label)
            })

    return pd.DataFrame(rows), rmin, rmax

def build_pairs_up(all_df_upmerged, ap_scores, rssi_min=None, rssi_max=None, score_max=100.0):
    df = all_df_upmerged.copy()
    nonzero = df.loc[df["avg_beacon_rssi"] != 0, "avg_beacon_rssi"]
    if rssi_min is None or rssi_max is None:
        if len(nonzero) > 0:
            rmin = float(nonzero.min())
            rmax = float(nonzero.max())
        else:
            rmin, rmax = -120.0, -30.0
    else:
        rmin, rmax = rssi_min, rssi_max
    rden = rmax - rmin if (rmax - rmin) != 0 else 1.0

    rows = []
    for pos in df["position"].unique():
        sub = df[df["position"] == pos]
        if len(sub) < 2:
            continue

        for a_idx, b_idx in itertools.permutations(sub.index, 2):
            a = sub.loc[a_idx]
            b = sub.loc[b_idx]

            a_up = bool(a.get("connected_up", True))
            b_up = bool(b.get("connected_up", True))
            if (not a_up) and (not b_up):
                continue

            raw_ra = float(a["avg_beacon_rssi"])
            raw_rb = float(b["avg_beacon_rssi"])
            ra = max(0.0, min(1.0, (raw_ra - rmin) / rden))
            rb = max(0.0, min(1.0, (raw_rb - rmin) / rden))

            sa = max(0.0, min(1.0, float(ap_scores.get(int(a["ap_id"]), 0)) / score_max))
            sb = max(0.0, min(1.0, float(ap_scores.get(int(b["ap_id"]), 0)) / score_max))

            ul_a = float(a.get("uldelay", 0.0))
            ul_b = float(b.get("uldelay", 0.0))
            tl_a = a.get("total_latency_95_percent", np.nan)
            tl_b = b.get("total_latency_95_percent", np.nan)

            if a_up and (not b_up):
                label = 1
            elif (not a_up) and b_up:
                label = 0
            else:
                ul_eps = 0.01
                delta_ul = ul_a - ul_b
                if abs(delta_ul) > ul_eps:
                    label = 1 if delta_ul > 0 else 0
                else:
                    label = 1 if delta_ul > 0 else 0
            '''
                    tl_eps = min(tl_a, tl_b)*0.5
                    if np.isnan(tl_a) and np.isnan(tl_b):
                        label = 1 if sa > sb else 0 if sa != sb else 0
                    elif np.isnan(tl_a) and (not np.isnan(tl_b)):
                        label = 0
                    elif (not np.isnan(tl_a)) and np.isnan(tl_b):
                        label = 1
                    else:
                        delta_tl = tl_b - tl_a
                        if abs(delta_tl) > tl_eps:
                            label = 1 if delta_tl > 0 else 0
                        else:
                            label = 1 if sa > sb else 0 if sa != sb else 0
            '''
            rows.append({
                "position": pos,
                "ap_a": int(a["ap_id"]),
                "ap_b": int(b["ap_id"]),
                "rssi_a": ra,
                "rssi_b": rb,
                "rssi_diff": ra - rb,
                "score_a": sa,
                "score_b": sb,
                "score_diff": sa - sb,
                "prod_a": ra * sa,
                "prod_b": rb * sb,
                "a_conn_down": int(bool(a.get("connected_down", False))),
                "b_conn_down": int(bool(b.get("connected_down", False))),
                "a_conn_up": int(a_up),
                "b_conn_up": int(b_up),
                "biz": 1,
                "label": int(label)
            })

    return pd.DataFrame(rows), rmin, rmax

def train_combined_model(train_dirs, ap_count, ap_scores_video_list, ap_scores_game_list):
    if not isinstance(train_dirs, (list, tuple)):
        train_dirs = [train_dirs]
    if not isinstance(ap_scores_video_list, (list, tuple)):
        ap_scores_video_list = [ap_scores_video_list]
    if not isinstance(ap_scores_game_list, (list, tuple)):
        ap_scores_game_list = [ap_scores_game_list]

    if not (len(train_dirs) == len(ap_scores_video_list) == len(ap_scores_game_list)):
        raise ValueError("train_dirs / ap_scores_video_list / ap_scores_game_list 长度必须一致")

    scene_items = []
    

    # 先逐场景加载原始数据并处理断连
    for scene_id, scene_dir in enumerate(train_dirs):
        all_raw_rssi = []
        print(f"\n读取场景 {scene_id} : {scene_dir}")
        down_df_raw = load_down_ap_dir(scene_dir, ap_count=ap_count)
        up_df_raw = load_up_and_merge(scene_dir, scene_dir, ap_count=ap_count)

        down_df, r_fill_down = handle_disconnects_down(down_df_raw)
        up_df, r_fill_up, u_fill_up = handle_disconnects_up(up_df_raw)

        # 计算全局 RSSI 范围时只用原始非零 RSSI
        rr = pd.to_numeric(down_df["avg_beacon_rssi"], errors="coerce")
        rr = rr[rr != 0].dropna()
        if len(rr) > 0:
            all_raw_rssi = rr.tolist()

        #  RSSI 归一化范围
        if len(all_raw_rssi) > 0:
            rmin_global = float(min(all_raw_rssi))
            rmax_global = float(max(all_raw_rssi))
        else:
            rmin_global, rmax_global = -120.0, -30.0

        if rmax_global == rmin_global:
            rmax_global = rmin_global + 1.0

        print(f"\n场景 {scene_id} RSSI 范围: rmin={rmin_global}, rmax={rmax_global}")

        scene_items.append({
            "scene_id": scene_id,
            "scene_dir": scene_dir,
            "down_df": down_df,
            "up_df": up_df,
            "r_fill_down": r_fill_down,
            "r_fill_up": r_fill_up,
            "u_fill_up": u_fill_up,
            "r_min": rmin_global,
            "r_max": rmax_global,
            "ap_scores_video": ap_scores_video_list[scene_id],
            "ap_scores_game": ap_scores_game_list[scene_id],
        })


    # 构建所有场景的 pairwise 样本
    all_pairs = []

    for scene_id, item in enumerate(scene_items):
        print(f"\n构建场景 {scene_id} 的 pairwise 样本...")

        pairs_down, _, _ = build_pairs_down(
            item["down_df"],
            item["ap_scores_video"],
            rssi_min=item["r_min"],
            rssi_max=item["r_max"],
            score_max=score_max
        )
        pairs_up, _, _ = build_pairs_up(
            item["up_df"],
            item["ap_scores_game"],
            rssi_min=item["r_min"],
            rssi_max=item["r_max"],
            score_max=score_max
        )

        if len(pairs_down) > 0:
            pairs_down["scene_id"] = scene_id
            pairs_down["scene_dir"] = item["scene_dir"]
            all_pairs.append(pairs_down)

        if len(pairs_up) > 0:
            pairs_up["scene_id"] = scene_id
            pairs_up["scene_dir"] = item["scene_dir"]
            all_pairs.append(pairs_up)

        print(f"  down pairs: {len(pairs_down)}")
        print(f"  up pairs:   {len(pairs_up)}")

    if len(all_pairs) == 0:
        raise RuntimeError("没有生成任何训练样本，请检查输入场景数据")

    pairs_all = pd.concat(all_pairs, ignore_index=True)
    print("\n训练样本总数（多场景 down+up）:", len(pairs_all))

    feature_cols = [
        "rssi_a", "rssi_b", "rssi_diff",
        "score_a", "score_b", "score_diff",
        "prod_a", "prod_b",
        "a_conn_down", "b_conn_down", "a_conn_up", "b_conn_up",
        "biz"
    ]

    X = pairs_all[feature_cols].values
    y = pairs_all["label"].values

    scaler = StandardScaler()
    Xs = scaler.fit_transform(X)

    dtrain = lgb.Dataset(Xs, label=y)
    params = {
        "objective": "binary",
        "metric": "auc",
        "learning_rate": 0.05,
        "num_leaves": 31,
        "verbose": -1,
        "seed": 42
    }

    print("开始训练联合 LightGBM（多场景合并训练，无验证集）...")
    bst = lgb.train(params, dtrain, num_boost_round=500)

    model_obj = {
        "model": bst,
        "scaler": scaler,
        "feature_cols": feature_cols,
        #"rssi_min": rmin_global,
        #"rssi_max": rmax_global,
        #"rssi_denom": (rmax_global - rmin_global),
        "score_max": score_max,
        "scene_dirs": train_dirs,
        "ap_scores_video_list": ap_scores_video_list,
        "ap_scores_game_list": ap_scores_game_list
    }

    with open(output_model, "wb") as f:
        pickle.dump(model_obj, f)

    print("训练完成并保存模型到:", output_model)
    return model_obj

if __name__ == "__main__":
    train_combined_model(train_dirs, ap_count, ap_scores_video_list, ap_scores_game_list)