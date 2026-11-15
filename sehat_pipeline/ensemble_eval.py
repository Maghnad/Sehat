"""
Ensemble evaluation: Combine two models (e.g., DS-CNN Model E + YAMNet Model Y)
using validation-calibrated probability blending.

Rule 1 Compliance:
The blending weight w is selected STRICTLY on validation data (maximizing val macro-F1 or val AUC).
Test metrics are evaluated once with the pre-determined w*.

Usage:
    python ensemble_eval.py --m1 exp_E --m2 exp_Y
"""
import argparse, json, os
import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score, roc_curve, f1_score


def eval_binary(y_true, p_distress):
    fpr, tpr, _ = roc_curve(y_true, p_distress)
    auc = float(roc_auc_score(y_true, p_distress))
    sens_at = lambda fa: float(tpr[np.where(fpr <= fa)[0][-1]]) if len(np.where(fpr <= fa)[0]) else 0.0
    return {
        "AUC": round(auc, 4),
        "sens@1%FA": round(sens_at(0.01), 4),
        "sens@2%FA": round(sens_at(0.02), 4),
        "sens@5%FA": round(sens_at(0.05), 4),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--m1", required=True, help="Folder for model 1 (e.g., exp_E)")
    ap.add_argument("--m2", required=True, help="Folder for model 2 (e.g., exp_Y)")
    ap.add_argument("--out", default="", help="Optional output folder for ensemble metrics")
    a = ap.parse_args()

    v1 = np.load(os.path.join(a.m1, "probs_val.npz"))
    v2 = np.load(os.path.join(a.m2, "probs_val.npz"))
    t1 = np.load(os.path.join(a.m1, "probs_test.npz"))
    t2 = np.load(os.path.join(a.m2, "probs_test.npz"))

    y_val, y_test = v1["y"], t1["y"]
    p_v1, p_v2 = v1["p"], v2["p"]
    p_t1, p_t2 = t1["p"], t2["p"]

    # Sweep weights w in [0, 1] on VALIDATION data to find optimal AUC
    best_w, best_val_auc = 0.5, 0.0
    for w in np.linspace(0.0, 1.0, 101):
        p_val_blend = w * p_v1 + (1.0 - w) * p_v2
        auc = roc_auc_score(y_val, p_val_blend)
        if auc > best_val_auc:
            best_val_auc = auc
            best_w = w

    print(f"Validation Tuning (Rule 1):")
    print(f"  Model 1 ({a.m1}) Val AUC: {roc_auc_score(y_val, p_v1):.4f}")
    print(f"  Model 2 ({a.m2}) Val AUC: {roc_auc_score(y_val, p_v2):.4f}")
    print(f"  Optimal blend weight w* = {best_w:.2f} * {a.m1} + {1.0 - best_w:.2f} * {a.m2}")
    print(f"  Blended Val AUC: {best_val_auc:.4f}")

    # Evaluate on TEST set with fixed w*
    p_t_blend = best_w * p_t1 + (1.0 - best_w) * p_t2

    m1_test = eval_binary(y_test, p_t1)
    m2_test = eval_binary(y_test, p_t2)
    ens_test = eval_binary(y_test, p_t_blend)

    # Multi-class if available
    macro_f1s = {}
    if "probs" in t1 and "probs" in t2 and "y_multi" in t1:
        y_m = t1["y_multi"]
        pr_blend = best_w * t1["probs"] + (1.0 - best_w) * t2["probs"]
        macro_f1s["m1_macro_f1"] = round(float(f1_score(y_m, t1["probs"].argmax(1), average="macro")), 4)
        macro_f1s["m2_macro_f1"] = round(float(f1_score(y_m, t2["probs"].argmax(1), average="macro")), 4)
        macro_f1s["ens_macro_f1"] = round(float(f1_score(y_m, pr_blend.argmax(1), average="macro")), 4)

    rows = [
        {"Model": f"Model 1 ({os.path.basename(a.m1)})", **m1_test},
        {"Model": f"Model 2 ({os.path.basename(a.m2)})", **m2_test},
        {"Model": f"Ensemble (w={best_w:.2f})", **ens_test},
    ]
    df = pd.DataFrame(rows)
    print("\nTest Evaluation (with w* chosen on validation):")
    print(df.to_string(index=False))

    out_dir = a.out or f"ens_{os.path.basename(a.m1)}_{os.path.basename(a.m2)}"
    os.makedirs(out_dir, exist_ok=True)
    with open(f"{out_dir}/ensemble_metrics.json", "w") as fh:
        json.dump({
            "model1": a.m1,
            "model2": a.m2,
            "best_w": float(best_w),
            "val_auc_best": float(best_val_auc),
            "test_summary": rows,
            "multi_class_f1": macro_f1s,
        }, fh, indent=2)
    print(f"\nSaved ensemble metrics to {out_dir}/ensemble_metrics.json")


if __name__ == "__main__":
    main()

