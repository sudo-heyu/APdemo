import os
import re
import argparse
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, BoundaryNorm
import matplotlib.patches as mpatches


def parse_position_to_xy(pos):
    """从 position 字符串中提取前两个数作为整数 x,y；解析失败返回 None"""
    if pd.isna(pos):
        return None
    s = str(pos)
    nums = re.findall(r"-?\d+\.?\d*", s)
    if len(nums) >= 2:
        try:
            x = int(round(float(nums[0])))
            y = int(round(float(nums[1])))
            return x, y
        except:
            return None
    return None


def get_bounds_from_df(df):
    """从 position 列自动提取场景边界"""
    xs, ys = [], []
    for pos in df["position"].dropna().unique():
        xy = parse_position_to_xy(pos)
        if xy is None:
            continue
        x, y = xy
        xs.append(x)
        ys.append(y)

    if not xs:
        return 0, 0, 0, 0

    return min(xs), max(xs), min(ys), max(ys)


def build_grid(df, gt_col, ap_ids=None, y_top_large=False):
    """
    通用 build_grid：返回 grid, parsed_count, ap_used_set, bounds
    参数:
      df: 包含 'position' 和 gt_col 的 DataFrame
      gt_col: 列名 (例如 'gt_best_ap' 或 'gt2_best_ap')
      y_top_large:
          True  -> 图顶端表示大 y
          False -> 图顶端表示小 y
    返回:
      grid: shape (grid_h, grid_w), 值为 0 (no data) 或 ap_id+1
      parsed_count: 成功写入的格子数
      ap_used: 使用到的 AP 集合
      bounds: (x_min, x_max, y_min, y_max)
    """
    x_min, x_max, y_min, y_max = get_bounds_from_df(df)
    grid_w = x_max - x_min + 1
    grid_h = y_max - y_min + 1

    grid = np.zeros((grid_h, grid_w), dtype=np.int16)
    parsed = 0
    ap_used = set()

    for _, row in df.iterrows():
        pos = row.get("position", None)
        if pd.isna(pos):
            continue

        xy = parse_position_to_xy(pos)
        if xy is None:
            continue
        x, y = xy

        ap = row.get(gt_col, None)
        if pd.isna(ap):
            continue
        try:
            ap = int(ap)
        except:
            continue

        if (ap_ids is not None) and (ap not in ap_ids):
            continue

        # 坐标转网格索引

        row_idx = y - y_min
        col_idx = x - x_min

        if not (0 <= col_idx < grid_w and 0 <= row_idx < grid_h):
            continue

        grid[row_idx, col_idx] = ap + 1
        ap_used.add(ap)
        parsed += 1

    return grid, parsed, ap_used, (x_min, x_max, y_min, y_max)


def plot_grid(grid, ap_colors, bounds, title="GT best AP", out_png="gt_grid.png"):
    x_min, x_max, y_min, y_max = bounds

    cmap_colors = ["white"] + ap_colors
    cmap = ListedColormap(cmap_colors)
    norm = BoundaryNorm(np.arange(0, len(cmap_colors) + 1) - 0.5, cmap.N)

    fig, ax = plt.subplots(figsize=(10, 8))

    ax.imshow(
        grid,
        cmap=cmap,
        norm=norm,
        origin="lower",
        extent=[x_min - 0.5, x_max + 0.5, y_min - 0.5, y_max + 0.5]
    )

    ax.set_title(title)
    ax.set_xlim(x_min - 0.5, x_max + 0.5)
    ax.set_ylim(y_min - 0.5, y_max + 0.5)
    ax.set_aspect("equal", adjustable="box")
    ax.set_xlabel("x")
    ax.set_ylabel("y")

    x_span = x_max - x_min + 1
    y_span = y_max - y_min + 1
    x_step = max(1, x_span // 8)
    y_step = max(1, y_span // 8)

    ax.set_xticks(list(range(x_min, x_max + 1, x_step)))
    ax.set_yticks(list(range(y_min, y_max + 1, y_step)))
    #ax.invert_yaxis()

    patches = [mpatches.Patch(color="white", label="no data")]
    for ap_idx, color in enumerate(ap_colors):
        patches.append(mpatches.Patch(color=color, label=f"AP {ap_idx}"))
    ax.legend(handles=patches, loc='lower right', bbox_to_anchor=(1.02, 0.02))

    fig.tight_layout()
    fig.savefig(out_png, dpi=200)
    plt.close(fig)
    return out_png


def main(
    down_csv="./result/down_evaluation_results.csv",
    up_csv="./result/up_evaluation_results.csv",
    out_down_png="./result/down_gt_best_ap_grid.png",
    out_up_png="./result/up_gt_best_ap_grid.png",
    sel_data="gt"
):
    # AP color palette (6 colors for AP 0..5)
    ap_colors = ["#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b"]

    # load down CSV
    if not os.path.exists(down_csv):
        print(f"Error: {down_csv} not found.")
    else:
        df_down = pd.read_csv(down_csv)

        if sel_data == "gt":
            sel_col = "gt_best_ap"
        elif sel_data == "pred":
            sel_col = "model_best_ap"
        else:
            raise ValueError("sel_data must be 'gt' or 'pred'")

        grid_down, parsed_down, ap_used_down, bounds_down = build_grid(
            df_down,
            sel_col,
            ap_ids=list(range(len(ap_colors))),

        )
        print(f"Down: parsed cells = {parsed_down}, distinct APs used = {sorted(ap_used_down)}")
        plot_grid(
            grid_down,
            ap_colors,
            bounds_down,
            title=f"Video {sel_data} best AP",
            out_png=out_down_png

        )
        print(f"Saved down {sel_data} grid to: {out_down_png}")

    # load up CSV
    if not os.path.exists(up_csv):
        print(f"Error: {up_csv} not found.")
    else:
        df_up = pd.read_csv(up_csv)

        if sel_data == "gt":
            sel_col = "gt_best_ap"
        elif sel_data == "pred":
            sel_col = "model_best_ap"
        else:
            raise ValueError("sel_data must be 'gt' or 'pred'")

        grid_up, parsed_up, ap_used_up, bounds_up = build_grid(
            df_up,
            sel_col,
            ap_ids=list(range(len(ap_colors)))

        )
        print(f"Up: parsed cells = {parsed_up}, distinct APs used = {sorted(ap_used_up)}")
        plot_grid(
            grid_up,
            ap_colors,
            bounds_up,
            title=f"Game {sel_data} best AP",
            out_png=out_up_png

        )
        print(f"Saved up {sel_data} grid to: {out_up_png}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Plot GT best AP grids for down/up evaluation CSVs.")
    parser.add_argument("--down_csv", default="./eval_result/video_evaluation_results.csv")
    parser.add_argument("--up_csv", default="./eval_result/game_evaluation_results.csv")
    args = parser.parse_args()

    main(
        down_csv=args.down_csv,
        up_csv=args.up_csv,
        out_down_png="./eval_result/video_gt_best_ap_grid.png",
        out_up_png="./eval_result/game_gt_best_ap_grid.png",
        sel_data="gt"
    )
    main(
        down_csv=args.down_csv,
        up_csv=args.up_csv,
        out_down_png="./eval_result/video_pred_best_ap_grid.png",
        out_up_png="./eval_result/game_pred_best_ap_grid.png",
        sel_data="pred"
    )