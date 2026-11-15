"""
Grouped 5-fold cross-validation by source recording for the SEHAT-ERS YAMNet audio pipeline.

Key properties:
1. Every clip is in the validation fold exactly once (out-of-fold evaluation).
2. StratifiedGroupKFold ensures all 49 glass_break chunks are evaluated across folds,
   preventing 0-support folds while strictly keeping source recordings intact.
3. Fast: Reuses or creates unaugmented chunk embeddings in yamnet_raw_cache.npz,
   so the entire 5-fold CV runs in ~15-20 seconds on CPU.
4. Cluster bootstrap by SOURCE: Resamples recording sources (not individual chunks)
   with replacement to compute honest 95% confidence intervals for macro-F1 and AUC.
5. Evaluates binary distress P(distress) = 1 - P(background) and computes sensitivity
   at 1%, 2%, and 5% False Alarm rates.
6. False-alarm breakdown per background sound type (cough, laugh, sneeze, yawn, breath, DCASE, ESC-50).

Usage:
    python audio_cv.py --root data\\audio --out exp_cv ^
        --nz-csv "C:\\...\\metadata of train set .csv" "C:\\...\\metadata of test set.csv" ^
        --esc50-csv "C:\\...\\meta\\esc50.csv"
"""
import argparse, csv, glob, json, os, sys
import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras import layers, models
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from sklearn.model_selection import StratifiedGroupKFold, GroupShuffleSplit
from sklearn.metrics import f1_score, classification_report, confusion_matrix, roc_auc_score, roc_curve

import audio_train as at
import audio_yamnet as ay


def get_raw_embeddings(root, classes, yamnet_handle, cache_path="yamnet_raw_cache.npz"):
    """
    Loads or extracts YAMNet embeddings for all unaugmented 2s chunks.
    If yamnet_cache.npz (from audio_yamnet.py) already exists with the exact same fingerprint,
    reconstructs the unaugmented matrix instantly.
    """
    fp = at.data_fingerprint(root, classes) + "|raw|" + yamnet_handle
    if os.path.exists(cache_path):
        z = np.load(cache_path, allow_pickle=True)
        if str(z["fp"]) == fp and list(z["classes"]) == classes:
            print(f"Loaded cached raw embeddings from {cache_path}")
            return z["E"], z["y"], z["g"], z["names"]

    W, y, g, names = at.load_dataset(root)
    print(f"Loaded {len(W)} total chunks across {len(np.unique(g))} unique source recordings.")

    # Check if existing single-split cache has the unaugmented slices
    default_split_cache = "yamnet_cache.npz"
    if os.path.exists(default_split_cache):
        try:
            z_s = np.load(default_split_cache, allow_pickle=True)
            if list(z_s["classes"]) == classes:
                # Reconstruct split indices to map back to W
                gss = GroupShuffleSplit(1, test_size=0.30, random_state=int(z_s["seed"]))
                tr, rest = next(gss.split(W, y, g))
                va_i, te_i = next(GroupShuffleSplit(1, test_size=0.5, random_state=int(z_s["seed"]))
                                  .split(W[rest], y[rest], g[rest]))
                va, te = rest[va_i], rest[te_i]

                E = np.zeros((len(W), 2048), dtype=np.float32)
                E[tr] = z_s["Etr"][:len(tr)]
                E[va] = z_s["Eva"]
                E[te] = z_s["Ete"]
                if np.all(np.isfinite(E)) and not np.all(E == 0):
                    print("Extracted unaugmented embeddings from existing yamnet_cache.npz instantly.")
                    np.savez(cache_path, E=E, y=y, g=g, names=names, fp=fp, classes=np.array(classes))
                    return E, y, g, names
        except Exception as e:
            print(f"Could not reuse {default_split_cache}: {e}. Extracting via YAMNet...")

    print("Extracting raw YAMNet embeddings for all chunks (done once)...", flush=True)
    yam = ay.load_yamnet(yamnet_handle)
    E = ay.embed(yam, W, "all_chunks")
    np.savez(cache_path, E=E, y=y, g=g, names=names, fp=fp, classes=np.array(classes))
    print(f"Saved raw embeddings to {cache_path}")
    return E, y, g, names


def bootstrap_source_metrics(g, y, oof_probs, n_boot=1000, seed=0):
    """
    Cluster bootstrap resampling by SOURCE recording ID to compute honest 95% CIs.
    """
    rng = np.random.default_rng(seed)
    unique_groups = np.unique(g)
    group_to_indices = {grp: np.where(g == grp)[0] for grp in unique_groups}

    boot_macro_f1 = []
    boot_auc = []
    boot_sens_1fa = []
    boot_sens_2fa = []
    boot_sens_5fa = []

    for _ in range(n_boot):
        sampled_groups = rng.choice(unique_groups, size=len(unique_groups), replace=True)
        idx = np.concatenate([group_to_indices[grp] for grp in sampled_groups])

        y_b = y[idx]
        probs_b = oof_probs[idx]
        pred_b = probs_b.argmax(1)

        boot_macro_f1.append(f1_score(y_b, pred_b, average="macro", zero_division=0))

        y_bin = (y_b != 0).astype(int)
        p_distress = 1.0 - probs_b[:, 0]
        if len(np.unique(y_bin)) == 2:
            boot_auc.append(roc_auc_score(y_bin, p_distress))
            fpr, tpr, _ = roc_curve(y_bin, p_distress)
            i1 = np.where(fpr <= 0.01)[0]
            i2 = np.where(fpr <= 0.02)[0]
            i5 = np.where(fpr <= 0.05)[0]
            boot_sens_1fa.append(float(tpr[i1[-1]]) if len(i1) else 0.0)
            boot_sens_2fa.append(float(tpr[i2[-1]]) if len(i2) else 0.0)
            boot_sens_5fa.append(float(tpr[i5[-1]]) if len(i5) else 0.0)

    def ci(arr):
        return [round(float(np.percentile(arr, 2.5)), 4), round(float(np.percentile(arr, 97.5)), 4)]

    return {
        "macro_f1_95ci": ci(boot_macro_f1),
        "auc_95ci": ci(boot_auc),
        "sens@1%FA_95ci": ci(boot_sens_1fa),
        "sens@2%FA_95ci": ci(boot_sens_2fa),
        "sens@5%FA_95ci": ci(boot_sens_5fa),
    }


def analyze_bg_false_alarms(df, nz_csvs, esc50_csv):
    """
    Computes false alarm rates for specific background categories.
    """
    nz_class = {}
    for p in nz_csvs:
        if os.path.exists(p):
            m = pd.read_csv(p)
            fc = next(c for c in m.columns if "filename" in c.lower().replace("_", " "))
            cc = next(c for c in m.columns if "classname" in c.lower().replace("_", " "))
            nz_class.update(dict(zip(m[fc].astype(str).str.strip(), m[cc].astype(str).str.strip())))

    esc_class = {}
    if esc50_csv and os.path.exists(esc50_csv):
        em = pd.read_csv(esc50_csv)
        esc_class = dict(zip(em["filename"].astype(str), em["category"].astype(str)))

    def get_sound_type(clip_name):
        base = clip_name.split("@")[0]
        if base.startswith("nz"):
            orig = base.split("_", 1)[1] if "_" in base else base
            return f"Nonspeech7k: {nz_class.get(orig, 'unknown')}"
        elif base.startswith("es"):
            return f"ESC-50: {esc_class.get(base, 'ambient')}"
        elif base.startswith("dcbg"):
            return "DCASE: acoustic scene"
        return "other"

    bg_mask = df["true"] == "background"
    bg_df = df[bg_mask].copy()
    bg_df["sound_type"] = bg_df["clip"].apply(get_sound_type)
    bg_df["false_alarm"] = bg_df["pred"] != "background"

    summary = []
    for st, grp in bg_df.groupby("sound_type"):
        total = len(grp)
        fa = int(grp["false_alarm"].sum())
        fa_rate = fa / max(1, total)
        summary.append({"sound_type": st, "total_chunks": total, "false_alarms": fa, "fa_rate": round(fa_rate, 4)})

    res_df = pd.DataFrame(summary).sort_values("fa_rate", ascending=False)
    return res_df


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="data/audio")
    ap.add_argument("--out", default="exp_cv")
    ap.add_argument("--yamnet", default=ay.DEFAULT_HANDLE)
    ap.add_argument("--epochs", type=int, default=80)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--n-splits", type=int, default=5)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--weight-power", type=float, default=0.5)
    ap.add_argument("--hidden", type=int, default=128)
    ap.add_argument("--dropout", type=float, default=0.4)
    ap.add_argument("--label-smoothing", type=float, default=0.0)
    ap.add_argument("--cache", default="yamnet_raw_cache.npz")
    ap.add_argument("--nz-csv", nargs="*", default=[])
    ap.add_argument("--esc50-csv", default="")
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    tf.keras.utils.set_random_seed(a.seed)

    classes = [c for c in at.ALL_CLASSES if glob.glob(f"{a.root}/{c}/*.wav")]
    if "background" not in classes or len(classes) < 2:
        raise SystemExit(f"Need background and at least one other class under {a.root}; found {classes}")
    at.CLASSES = classes

    E, y, g, names = get_raw_embeddings(a.root, classes, a.yamnet, a.cache)
    K = len(classes)

    print(f"\n===== Grouped {a.n_splits}-Fold Cross-Validation by Source =====")
    print(f"Total chunks: {len(y)} | Unique sources: {len(np.unique(g))}")
    for ci, c in enumerate(classes):
        print(f"  {c:12s}: {(y == ci).sum():5d} chunks ({len(np.unique(g[y == ci])):4d} sources)")

    sgkf = StratifiedGroupKFold(n_splits=a.n_splits, shuffle=True, random_state=a.seed)
    oof_probs = np.zeros((len(y), K), dtype=np.float32)
    fold_macro_f1s = []

    for fold, (tr_idx, val_idx) in enumerate(sgkf.split(E, y, groups=g)):
        # Normalize using training fold statistics only
        mu, sd = E[tr_idx].mean(0), E[tr_idx].std(0) + 1e-6
        E_tr = (E[tr_idx] - mu) / sd
        E_val = (E[val_idx] - mu) / sd

        # Class weights for training fold
        cnt = np.bincount(y[tr_idx], minlength=K)
        cw = (len(tr_idx) / (K * np.maximum(cnt, 1))) ** a.weight_power

        if a.label_smoothing > 0:
            y_tr_target = tf.keras.utils.to_categorical(y[tr_idx], K)
            y_val_target = tf.keras.utils.to_categorical(y[val_idx], K)
            loss_fn = tf.keras.losses.CategoricalCrossentropy(label_smoothing=a.label_smoothing)
        else:
            y_tr_target = y[tr_idx]
            y_val_target = y[val_idx]
            loss_fn = "sparse_categorical_crossentropy"

        head = models.Sequential([
            layers.Input((E.shape[1],)),
            layers.Dense(a.hidden, activation="relu"),
            layers.Dropout(a.dropout),
            layers.Dense(K, activation="softmax"),
        ], name=f"fold_{fold}_head")
        head.compile("adam", loss_fn, metrics=["accuracy"])

        head.fit(E_tr, y_tr_target, validation_data=(E_val, y_val_target),
                 epochs=a.epochs, batch_size=a.batch, class_weight=dict(enumerate(cw)),
                 callbacks=[
                     tf.keras.callbacks.EarlyStopping(patience=10, restore_best_weights=True),
                     tf.keras.callbacks.ReduceLROnPlateau(factor=0.5, patience=4, min_lr=1e-5),
                 ], verbose=0)

        p_val = head.predict(E_val, verbose=0)
        oof_probs[val_idx] = p_val
        fold_f1 = f1_score(y[val_idx], p_val.argmax(1), average="macro")
        fold_macro_f1s.append(fold_f1)
        print(f"  Fold {fold + 1}/{a.n_splits}: val macro-F1 = {fold_f1:.4f} (val chunks = {len(val_idx)})")

    oof_preds = oof_probs.argmax(1)
    oof_macro_f1 = float(f1_score(y, oof_preds, average="macro"))
    print("\n" + "=" * 60)
    print(f"OOF Macro-F1: {oof_macro_f1:.4f}  (Fold spread: {np.mean(fold_macro_f1s):.4f} +/- {np.std(fold_macro_f1s):.4f})")
    print("=" * 60)

    rep = classification_report(y, oof_preds, target_names=classes, digits=4, zero_division=0)
    print("\nOut-of-Fold Classification Report (Every chunk tested once):")
    print(rep)

    rep_dict = classification_report(y, oof_preds, target_names=classes, output_dict=True, zero_division=0)
    cm = confusion_matrix(y, oof_preds)

    # Distress ROC metrics
    y_bin = (y != 0).astype(int)
    p_distress = 1.0 - oof_probs[:, 0]
    auc = float(roc_auc_score(y_bin, p_distress))
    fpr, tpr, _ = roc_curve(y_bin, p_distress)
    sens_at = lambda fa: float(tpr[np.where(fpr <= fa)[0][-1]]) if len(np.where(fpr <= fa)[0]) else 0.0

    s1 = sens_at(0.01)
    s2 = sens_at(0.02)
    s5 = sens_at(0.05)

    print(f"Binary Distress AUC: {auc:.4f}")
    print(f"Sensitivity @ 1% FA: {s1:.4f} | @ 2% FA: {s2:.4f} | @ 5% FA: {s5:.4f}")

    # Cluster Bootstrap by Source Recording
    print("\nComputing Cluster Bootstrap 95% Confidence Intervals (resampling by SOURCE recording)...")
    boot_ci = bootstrap_source_metrics(g, y, oof_probs, n_boot=1000, seed=a.seed)
    print(f"  Macro-F1 95% CI: [{boot_ci['macro_f1_95ci'][0]:.4f}, {boot_ci['macro_f1_95ci'][1]:.4f}]")
    print(f"  Distress AUC 95% CI: [{boot_ci['auc_95ci'][0]:.4f}, {boot_ci['auc_95ci'][1]:.4f}]")
    print(f"  Sens@1%FA 95% CI: [{boot_ci['sens@1%FA_95ci'][0]:.4f}, {boot_ci['sens@1%FA_95ci'][1]:.4f}]")

    # Save OOF predictions
    oof_rows = []
    for nm, grp, t, p, probs in zip(names, g, y, oof_preds, oof_probs):
        r = {"clip": nm, "source": grp, "true": classes[t], "pred": classes[p]}
        for ci, c in enumerate(classes):
            r[f"p_{c}"] = round(float(probs[ci]), 4)
        r["p_distress"] = round(float(1.0 - probs[0]), 4)
        oof_rows.append(r)
    df_oof = pd.DataFrame(oof_rows)
    df_oof.to_csv(f"{a.out}/oof_predictions.csv", index=False)

    # False-alarm breakdown
    bg_fa_df = analyze_bg_false_alarms(df_oof, a.nz_csv, a.esc50_csv)
    bg_fa_df.to_csv(f"{a.out}/background_false_alarm_rates.csv", index=False)
    print("\nTop False-Alarm Rates on Background Sounds (OOF):")
    print(bg_fa_df.head(10).to_string(index=False))

    # Metrics JSON
    metrics = {
        "oof_macro_f1": oof_macro_f1,
        "fold_macro_f1_mean": float(np.mean(fold_macro_f1s)),
        "fold_macro_f1_std": float(np.std(fold_macro_f1s)),
        "fold_macro_f1s": [round(float(v), 4) for v in fold_macro_f1s],
        "distress_auc": auc,
        "sensitivity_at_1pct_fa": s1,
        "sensitivity_at_2pct_fa": s2,
        "sensitivity_at_5pct_fa": s5,
        "bootstrap_95ci": boot_ci,
        "per_class": {c: {k: round(float(rep_dict[c][k]), 4) for k in ("precision", "recall", "f1-score")}
                          | {"support": int(rep_dict[c]["support"])} for c in classes},
        "confusion_matrix": cm.tolist(),
    }
    with open(f"{a.out}/cv_metrics.json", "w") as fh:
        json.dump(metrics, fh, indent=2)

    # Confusion matrix plot
    fig, ax = plt.subplots(figsize=(5.5, 4.5))
    ax.imshow(cm, cmap="Blues")
    for i in range(K):
        for j in range(K):
            ax.text(j, i, int(cm[i, j]), ha="center", va="center", fontsize=9)
    ax.set_xticks(range(K))
    ax.set_xticklabels(classes, rotation=30, ha="right")
    ax.set_yticks(range(K))
    ax.set_yticklabels(classes)
    ax.set_xlabel("Predicted")
    ax.set_ylabel("Actual")
    ax.set_title(f"YAMNet 5-Fold OOF Confusion Matrix (Macro-F1={oof_macro_f1:.3f})")
    fig.tight_layout()
    fig.savefig(f"{a.out}/confusion_matrix_cv.png", dpi=200)
    plt.close(fig)

    print(f"\nAll CV results written to {a.out}/")


if __name__ == "__main__":
    main()

