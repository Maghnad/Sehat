"""
Run several audio_train.py configurations one after another (same data, same split, same
feature cache) and print one comparison table.

Because every run uses the same split and the same cached features, the differences you see
come from the setting being tested, not from a different random test set.

Usage (from the sehat_pipeline folder):
    python run_audio_experiments.py --root data\\audio --epochs 40
    python run_audio_experiments.py --root data\\audio --epochs 40 --only A D E     (a subset)
    python run_audio_experiments.py --root data\\audio --table-only                 (just re-print the table)

Each run writes its results to  exp_<name>\\  (model, metrics.json, confusion_matrix.png, ...).
"""
import argparse, json, os, subprocess, sys
import numpy as np
from sklearn.metrics import roc_auc_score, roc_curve

CONFIGS = {
    "A": ("baseline (fully balanced weights)", "audio_train.py", [], ""),
    "B": ("weights x0.5 (square root)", "audio_train.py", ["--weight-power", "0.5"], ""),
    "C": ("SpecAugment", "audio_train.py", ["--specaug"], ""),
    "D": ("SpecAugment + weights x0.5", "audio_train.py", ["--specaug", "--weight-power", "0.5"], ""),
    "E": ("D + model width x2", "audio_train.py", ["--specaug", "--weight-power", "0.5", "--width", "2"], ""),
    "F": ("D + model width x3", "audio_train.py", ["--specaug", "--weight-power", "0.5", "--width", "3"], ""),
    "G": ("E + ambient-only noise mixing", "audio_train.py",
          ["--specaug", "--weight-power", "0.5", "--width", "2", "--noise-prefix", "dcbg"], "features_cache_dcbg.npz"),
    "Y": ("YAMNet embeddings + MLP head", "audio_yamnet.py", [], "yamnet_cache.npz"),
    "Y1": ("Y + gentler weights (power 0.25)", "audio_yamnet.py", ["--weight-power", "0.25"], "yamnet_cache.npz"),
    "Y2": ("Y + wider head (256, drop 0.5)", "audio_yamnet.py", ["--hidden", "256", "--dropout", "0.5"], "yamnet_cache.npz"),
    "Y3": ("Y + 2-layer MLP (256->64)", "audio_yamnet.py", ["--hidden", "256", "--extra-dense", "64", "--dropout", "0.4"], "yamnet_cache.npz"),
    "Y4": ("Y + label smoothing 0.1", "audio_yamnet.py", ["--label-smoothing", "0.1"], "yamnet_cache.npz"),
    "Y5": ("Y + focal loss (gamma 2.0)", "audio_yamnet.py", ["--focal-gamma", "2.0"], "yamnet_cache.npz"),
}


def summarize(folder):
    m = json.load(open(os.path.join(folder, "metrics.json")))
    d = np.load(os.path.join(folder, "probs_test.npz"))
    y, p = d["y"], d["p"]
    fpr, tpr, _ = roc_curve(y, p)
    sens = lambda f: tpr[np.where(fpr <= f)[0][-1]]
    row = {"valF1": m.get("val_macro_f1", float("nan")), "macroF1": m["test_macro_f1"], "AUC": roc_auc_score(y, p),
           "sens@1%FA": sens(0.01), "sens@2%FA": sens(0.02), "sens@5%FA": sens(0.05)}
    for c, v in m["per_class"].items():
        row[f"F1 {c}"] = v["f1-score"]
    for name in ("model.tflite", "head.tflite"):
        pth = os.path.join(folder, name)
        if os.path.exists(pth):
            row["model KB"] = os.path.getsize(pth) / 1024
            break
    else:
        row["model KB"] = float("nan")
    return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="data/audio")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--n_aug", type=int, default=1)
    ap.add_argument("--cache", default="features_cache.npz")
    ap.add_argument("--yamnet", default="", help="local YAMNet folder or TF Hub handle (run Y only)")
    ap.add_argument("--only", nargs="*", default=list(CONFIGS))
    ap.add_argument("--table-only", action="store_true")
    a = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    rows = {}
    for key in a.only:
        desc, script, extra, cache_override = CONFIGS[key]
        out = f"exp_{key}"
        if not a.table_only:
            print(f"\n===== {key}: {desc} =====", flush=True)
            cmd = [sys.executable, os.path.join(here, script), "--root", a.root,
                   "--epochs", str(a.epochs), "--n_aug", str(a.n_aug),
                   "--cache", cache_override or a.cache, "--out", out] + extra
            if script == "audio_yamnet.py" and a.yamnet:
                cmd += ["--yamnet", a.yamnet]
            subprocess.run(cmd, check=True)
        if os.path.exists(os.path.join(out, "metrics.json")):
            rows[key] = (desc, summarize(out))

    if not rows:
        raise SystemExit("No results found.")
    cols = list(next(iter(rows.values()))[1].keys())
    print("\n" + "=" * 100)
    print(f"{'run':3s} {'setting':34s} " + " ".join(f"{c[:11]:>11s}" for c in cols))
    for k, (desc, r) in rows.items():
        print(f"{k:3s} {desc[:34]:34s} " + " ".join(f"{r[c]:11.3f}" if c != "model KB" else (f"{r[c]:11.0f}" if r[c] == r[c] else f"{'-':>11s}") for c in cols))
    pd_rows = [{"run": k, "setting": d, **r} for k, (d, r) in rows.items()]
    import pandas as pd
    pd.DataFrame(pd_rows).round(4).to_csv("experiment_summary.csv", index=False)
    print("\nsaved experiment_summary.csv")


if __name__ == "__main__":
    main()
