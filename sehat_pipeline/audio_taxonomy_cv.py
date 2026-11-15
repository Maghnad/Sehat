"""
Class Taxonomy Design Experiment (Step 5 of SEHAT-ERS Audio Plan):

Compares three class taxonomies under identical grouped 5-fold cross-validation by source:
1. Baseline (4-class):
     [background, scream, cry, glass_break]
     P(distress) = 1 - P(background)

2. Taxonomy 1 (5-class with explicit human non-distress):
     [ambient_bg, human_nondistress, scream, cry, glass_break]
     human_nondistress = laugh, cough, sneeze, yawn from Nonspeech7k
     ambient_bg = ESC-50 + DCASE scenes + breath
     P(distress) = P(scream) + P(cry) + P(glass_break)

3. Taxonomy 2 (4-class with merged vocal distress):
     [ambient_bg, human_nondistress, vocal_distress, glass_break]
     vocal_distress = scream + cry merged
     P(distress) = P(vocal_distress) + P(glass_break)

All runs use the best head configuration from Step 3 (Y4: label smoothing 0.1, 128 units, dropout 0.4).
Evaluation criteria:
- Binary Distress AUC and Sensitivity at 1%, 2%, 5% False Alarms
- False alarm rate specifically on laugh, cough, sneeze, yawn, and ambient background
"""
import argparse, json, os
import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras import layers, models
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.metrics import roc_auc_score, roc_curve, f1_score


def eval_binary_metrics(y_true_binary, p_distress):
    auc = float(roc_auc_score(y_true_binary, p_distress))
    fpr, tpr, thresholds = roc_curve(y_true_binary, p_distress)
    sens_at = lambda fa: float(tpr[np.where(fpr <= fa)[0][-1]]) if len(np.where(fpr <= fa)[0]) else 0.0
    return {
        "AUC": round(auc, 4),
        "sens@1%FA": round(sens_at(0.01), 4),
        "sens@2%FA": round(sens_at(0.02), 4),
        "sens@5%FA": round(sens_at(0.05), 4),
    }


def compute_fa_table(sound_types, y_true_binary, p_distress, threshold=0.5):
    """
    Computes false alarm rate (p_distress >= threshold) on non-distress chunks by sound type.
    """
    bg_mask = (y_true_binary == 0)
    df = pd.DataFrame({
        "type": np.array(sound_types)[bg_mask],
        "fa": (p_distress[bg_mask] >= threshold).astype(int),
    })
    res = {}
    for st, grp in df.groupby("type"):
        res[st] = round(float(grp["fa"].mean()), 4)
    res["overall_bg_fa"] = round(float(df["fa"].mean()), 4)
    return res


def train_and_eval_cv(E, y, g, class_names, get_p_distress, is_distress_class,
                      epochs=80, batch_size=64, hidden=128, dropout=0.4,
                      weight_power=0.5, label_smoothing=0.1, n_splits=5, seed=0):
    K = len(class_names)
    sgkf = StratifiedGroupKFold(n_splits=n_splits, shuffle=True, random_state=seed)
    oof_probs = np.zeros((len(y), K), dtype=np.float32)

    for fold, (tr_idx, val_idx) in enumerate(sgkf.split(E, y, groups=g)):
        mu = E[tr_idx].mean(0)
        sd = E[tr_idx].std(0) + 1e-6
        E_tr = (E[tr_idx] - mu) / sd
        E_val = (E[val_idx] - mu) / sd

        cnt = np.bincount(y[tr_idx], minlength=K)
        cw = (len(tr_idx) / (K * np.maximum(cnt, 1))) ** weight_power

        y_tr_cat = tf.keras.utils.to_categorical(y[tr_idx], K)
        y_val_cat = tf.keras.utils.to_categorical(y[val_idx], K)

        head = models.Sequential([
            layers.Input((E.shape[1],)),
            layers.Dense(hidden, activation="relu"),
            layers.Dropout(dropout),
            layers.Dense(K, activation="softmax"),
        ])
        loss_fn = tf.keras.losses.CategoricalCrossentropy(label_smoothing=label_smoothing) if label_smoothing > 0 else "categorical_crossentropy"
        head.compile("adam", loss_fn, metrics=["accuracy"])

        head.fit(E_tr, y_tr_cat, validation_data=(E_val, y_val_cat),
                 epochs=epochs, batch_size=batch_size, class_weight=dict(enumerate(cw)),
                 callbacks=[
                     tf.keras.callbacks.EarlyStopping(patience=10, restore_best_weights=True),
                     tf.keras.callbacks.ReduceLROnPlateau(factor=0.5, patience=4, min_lr=1e-5),
                 ], verbose=0)

        oof_probs[val_idx] = head.predict(E_val, verbose=0)

    p_distress = get_p_distress(oof_probs)
    y_distress = np.isin(y, [i for i, c in enumerate(class_names) if is_distress_class(c)]).astype(int)
    oof_preds = oof_probs.argmax(1)
    macro_f1 = float(f1_score(y, oof_preds, average="macro"))

    return oof_probs, oof_preds, p_distress, y_distress, macro_f1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default="yamnet_raw_cache.npz")
    ap.add_argument("--nz-train-csv", default="C:/Users/debna/Downloads/sehat_data/Nonspeech7k/metadata of train set .csv")
    ap.add_argument("--nz-test-csv", default="C:/Users/debna/Downloads/sehat_data/Nonspeech7k/metadata of test set.csv")
    ap.add_argument("--esc50-csv", default="C:/Users/debna/Downloads/sehat_data/ESC-50-master/meta/esc50.csv")
    ap.add_argument("--out", default="exp_taxonomy")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    print("Loading cached raw embeddings...")
    z = np.load(a.cache, allow_pickle=True)
    E, y_orig, g, names = z["E"], z["y"], z["g"], z["names"]
    classes_orig = list(z["classes"])  # ['background', 'scream', 'cry', 'glass_break']
    print(f"Loaded {len(E)} chunks across {len(np.unique(g))} sources.")

    # Load Nonspeech7k metadata to categorize background sounds
    m1 = pd.read_csv(a.nz_train_csv)
    m2 = pd.read_csv(a.nz_test_csv)
    meta_df = pd.concat([m1, m2], ignore_index=True)
    fc = next(c for c in meta_df.columns if "filename" in c.lower().replace("_", " "))
    cc = next(c for c in meta_df.columns if "classname" in c.lower().replace("_", " "))
    nz_map = dict(zip(meta_df[fc].astype(str).str.strip(), meta_df[cc].astype(str).str.strip().str.lower()))

    # Build sound_type per chunk
    sound_types = []
    for nm in names:
        base = nm.split("@")[0]
        if base.startswith("nz"):
            orig_fn = base.split("_", 1)[1] if "_" in base else base
            sound_types.append(nz_map.get(orig_fn, "nz_unknown"))
        elif base.startswith("es"):
            sound_types.append("esc50_ambient")
        elif base.startswith("dcbg"):
            sound_types.append("dcase_scene")
        else:
            sound_types.append("other")
    sound_types = np.array(sound_types)

    print("\nBackground sound type breakdown:")
    for st, cnt in sorted(pd.Series(sound_types[y_orig == 0]).value_counts().items()):
        print(f"  {st:15s}: {cnt:5d} chunks")

    results = []
    fa_summaries = {}

    # =========================================================================
    # TAXONOMY 0: Baseline 4-Class
    # [background, scream, cry, glass_break]
    # =========================================================================
    print("\n" + "=" * 60)
    print("Running TAXONOMY 0 (Baseline 4-class)...")
    print("Classes: [background, scream, cry, glass_break]")
    cnames_0 = ["background", "scream", "cry", "glass_break"]
    y_0 = y_orig.copy()

    p_distress_fn_0 = lambda probs: 1.0 - probs[:, 0]
    is_distress_fn_0 = lambda c: c in ["scream", "cry", "glass_break"]

    oof_probs_0, oof_preds_0, pd_0, yd_0, mf1_0 = train_and_eval_cv(
        E, y_0, g, cnames_0, p_distress_fn_0, is_distress_fn_0, seed=0
    )
    m0 = eval_binary_metrics(yd_0, pd_0)
    m0["Macro-F1"] = round(mf1_0, 4)
    m0["Taxonomy"] = "0: Baseline 4-Class (bg/scream/cry/glass)"
    results.append(m0)
    fa_summaries["Baseline 4-Class"] = compute_fa_table(sound_types, yd_0, pd_0)

    # =========================================================================
    # TAXONOMY 1: 5-Class (Explicit human_nondistress class)
    # [ambient_bg, human_nondistress, scream, cry, glass_break]
    # =========================================================================
    print("\n" + "=" * 60)
    print("Running TAXONOMY 1 (5-class: explicit human_nondistress)...")
    print("Classes: [ambient_bg, human_nondistress, scream, cry, glass_break]")
    cnames_1 = ["ambient_bg", "human_nondistress", "scream", "cry", "glass_break"]

    # Remap y:
    # If y_orig == 0 (background):
    #   if sound_type in [laugh, cough, sneeze, yawn, yawm] -> human_nondistress (1)
    #   else -> ambient_bg (0)
    # If y_orig == 1 (scream) -> 2
    # If y_orig == 2 (cry) -> 3
    # If y_orig == 3 (glass_break) -> 4
    y_1 = np.zeros_like(y_orig)
    human_nd_mask = (y_orig == 0) & np.isin(sound_types, ["laugh", "cough", "sneeze", "yawn", "yawm"])
    ambient_mask = (y_orig == 0) & (~human_nd_mask)

    y_1[ambient_mask] = 0
    y_1[human_nd_mask] = 1
    y_1[y_orig == 1] = 2  # scream
    y_1[y_orig == 2] = 3  # cry
    y_1[y_orig == 3] = 4  # glass_break

    for ci, c in enumerate(cnames_1):
        print(f"  {c:18s}: {(y_1 == ci).sum():5d} chunks")

    # P(distress) = P(scream) + P(cry) + P(glass_break) = 1 - P(ambient_bg) - P(human_nondistress)
    p_distress_fn_1 = lambda probs: probs[:, 2] + probs[:, 3] + probs[:, 4]
    is_distress_fn_1 = lambda c: c in ["scream", "cry", "glass_break"]

    oof_probs_1, oof_preds_1, pd_1, yd_1, mf1_1 = train_and_eval_cv(
        E, y_1, g, cnames_1, p_distress_fn_1, is_distress_fn_1, seed=0
    )
    m1 = eval_binary_metrics(yd_1, pd_1)
    m1["Macro-F1"] = round(mf1_1, 4)
    m1["Taxonomy"] = "1: 5-Class (+ human_nondistress)"
    results.append(m1)
    fa_summaries["5-Class (human_nondistress)"] = compute_fa_table(sound_types, yd_1, pd_1)

    # =========================================================================
    # TAXONOMY 2: 4-Class (Merged Vocal Distress)
    # [ambient_bg, human_nondistress, vocal_distress, glass_break]
    # =========================================================================
    print("\n" + "=" * 60)
    print("Running TAXONOMY 2 (4-class: merged vocal distress)...")
    print("Classes: [ambient_bg, human_nondistress, vocal_distress, glass_break]")
    cnames_2 = ["ambient_bg", "human_nondistress", "vocal_distress", "glass_break"]

    # Remap y:
    # ambient_bg = 0
    # human_nondistress = 1
    # scream (1) or cry (2) -> vocal_distress (2)
    # glass_break (3) -> glass_break (3)
    y_2 = np.zeros_like(y_orig)
    y_2[ambient_mask] = 0
    y_2[human_nd_mask] = 1
    y_2[(y_orig == 1) | (y_orig == 2)] = 2  # vocal_distress (scream + cry)
    y_2[y_orig == 3] = 3  # glass_break

    for ci, c in enumerate(cnames_2):
        print(f"  {c:18s}: {(y_2 == ci).sum():5d} chunks")

    # P(distress) = P(vocal_distress) + P(glass_break)
    p_distress_fn_2 = lambda probs: probs[:, 2] + probs[:, 3]
    is_distress_fn_2 = lambda c: c in ["vocal_distress", "glass_break"]

    oof_probs_2, oof_preds_2, pd_2, yd_2, mf1_2 = train_and_eval_cv(
        E, y_2, g, cnames_2, p_distress_fn_2, is_distress_fn_2, seed=0
    )
    m2 = eval_binary_metrics(yd_2, pd_2)
    m2["Macro-F1"] = round(mf1_2, 4)
    m2["Taxonomy"] = "2: 4-Class (+ human_nd, merged vocal)"
    results.append(m2)
    fa_summaries["4-Class (merged vocal)"] = compute_fa_table(sound_types, yd_2, pd_2)

    # =========================================================================
    # Comparison Tables
    # =========================================================================
    df_res = pd.DataFrame(results)[["Taxonomy", "AUC", "sens@1%FA", "sens@2%FA", "sens@5%FA", "Macro-F1"]]
    print("\n" + "=" * 80)
    print("CLASS TAXONOMY COMPARISON (Grouped 5-Fold OOF Predictions, All 17,101 Chunks):")
    print("=" * 80)
    print(df_res.to_string(index=False))
    df_res.to_csv(f"{a.out}/taxonomy_comparison.csv", index=False)

    df_fa = pd.DataFrame(fa_summaries)
    print("\n" + "=" * 80)
    print("FALSE-ALARM RATE BY BACKGROUND SOUND TYPE (Threshold = 0.5):")
    print("=" * 80)
    print(df_fa.to_string())
    df_fa.to_csv(f"{a.out}/false_alarm_by_sound_type.csv")

    with open(f"{a.out}/taxonomy_summary.json", "w") as fh:
        json.dump({
            "metrics": results,
            "false_alarms": fa_summaries,
        }, fh, indent=2)

    print(f"\nSaved all taxonomy results to {a.out}/")


if __name__ == "__main__":
    main()

