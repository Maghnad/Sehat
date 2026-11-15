"""
Transfer learning for the SEHAT-ERS audio model: YAMNet embeddings + a small classifier head.

YAMNet (Google) is a pretrained audio network trained on AudioSet, which includes screaming,
crying, laughter, coughing and breaking glass. Here it is used as a frozen feature extractor:
each 2 s chunk -> YAMNet -> 1024-d embeddings per 0.48 s frame -> mean and max over frames
(2048 numbers) -> a small MLP that predicts background / scream / cry / glass_break.

The data split is identical to audio_train.py (same seed, same grouping by recording), so the
test chunks are exactly the ones used for runs A, D and E and the results are comparable.

Usage (needs an internet connection the first time, to download YAMNet):
    pip install tensorflow-hub
    python audio_yamnet.py --root data\\audio --out exp_Y

If the download fails, download YAMNet manually, unzip the model folder, and pass it with
--yamnet "C:\\path\\to\\yamnet_folder".

Outputs in --out: head.keras, head.tflite (the small classifier head only), head_norm.npz,
metrics.json, classification_report.txt, confusion_matrix.png, test_predictions.csv,
probs_val.npz, probs_test.npz (same format as audio_train.py).
NOTE: the deployed model would be YAMNet (about 3.7 MB as TFLite) plus this head. The Android
side of that is not built yet.
"""
import argparse, csv, glob, json, os
import numpy as np
import tensorflow as tf
from tensorflow.keras import layers, models
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from sklearn.model_selection import GroupShuffleSplit
from sklearn.metrics import f1_score, classification_report, confusion_matrix

import audio_train as at

DEFAULT_HANDLE = "https://tfhub.dev/google/yamnet/1"


def load_yamnet(handle):
    if os.path.isdir(handle):
        return tf.saved_model.load(handle)
    try:
        import tensorflow_hub as hub
    except ImportError:
        raise SystemExit("YAMNet is downloaded with TensorFlow Hub. Install it with:  pip install tensorflow-hub")
    try:
        return hub.load(handle)
    except Exception as e:  # network problems, redirects, etc.
        raise SystemExit(f"Could not load YAMNet from {handle}\n  {e}\n"
                         "Check your internet connection, or download the model manually and pass its folder with --yamnet.")


def embed(yamnet, waves, label):
    out = []
    for i, w in enumerate(waves):
        _, emb, _ = yamnet(tf.constant(w, dtype=tf.float32))
        emb = emb.numpy()
        out.append(np.concatenate([emb.mean(0), emb.max(0)]))
        if (i + 1) % 1000 == 0:
            print(f"  {label}: embedded {i + 1}/{len(waves)}", flush=True)
    return np.array(out, dtype="float32")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--out", default="out_yamnet")
    ap.add_argument("--yamnet", default=DEFAULT_HANDLE, help="TF Hub handle or a local model folder")
    ap.add_argument("--epochs", type=int, default=80)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--n_aug", type=int, default=1, help="augmented copies per training chunk")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--weight-power", type=float, default=0.5)
    ap.add_argument("--noise-prefix", default="dcbg",
                    help="mix only clips starting with this (real ambient scenes) into training chunks as noise")
    ap.add_argument("--hidden", type=int, default=128)
    ap.add_argument("--extra-dense", type=int, default=0, help="second dense layer size (0 for single layer)")
    ap.add_argument("--dropout", type=float, default=0.4)
    ap.add_argument("--label-smoothing", type=float, default=0.0)
    ap.add_argument("--focal-gamma", type=float, default=0.0)
    ap.add_argument("--l2", type=float, default=0.0)
    ap.add_argument("--cache", default="yamnet_cache.npz", help="embedding cache; delete it to recompute")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    tf.keras.utils.set_random_seed(a.seed)
    rng = np.random.default_rng(a.seed)

    classes = [c for c in at.ALL_CLASSES if glob.glob(f"{a.root}/{c}/*.wav")]
    if "background" not in classes or len(classes) < 2:
        raise SystemExit(f"Need a background folder and at least one other class under {a.root}; found {classes}")
    at.CLASSES = classes
    print("classes used:", classes)

    fp = at.data_fingerprint(a.root, classes) + "|" + a.yamnet
    ok = False
    if a.cache and os.path.exists(a.cache):
        z = np.load(a.cache, allow_pickle=True)
        if (str(z["fp"]) == fp and list(z["classes"]) == classes and int(z["n_aug"]) == a.n_aug
                and int(z["seed"]) == a.seed and str(z["noise_prefix"]) == a.noise_prefix):
            Etr, ytr, Eva, yva, Ete, yte, names_te = (z[k] for k in ("Etr", "ytr", "Eva", "yva", "Ete", "yte", "names_te"))
            ok = True
            print("loaded cached embeddings from", a.cache)
        else:
            print("cache does not match the current data/settings; recomputing")
    if not ok:
        W, y, g, names = at.load_dataset(a.root)
        print("clips per class:", {c: int((y == i).sum()) for i, c in enumerate(classes)})
        # identical split to audio_train.py
        gss = GroupShuffleSplit(1, test_size=0.30, random_state=a.seed)
        tr, rest = next(gss.split(W, y, g))
        va_i, te_i = next(GroupShuffleSplit(1, test_size=0.5, random_state=a.seed)
                          .split(W[rest], y[rest], g[rest]))
        va, te = rest[va_i], rest[te_i]

        is_bg = y[tr] == 0
        if a.noise_prefix:
            is_bg = is_bg & np.char.startswith(names[tr].astype(str), a.noise_prefix)
        pool = W[tr][is_bg]
        if len(pool) == 0:
            print(f"WARNING: no training clips start with '{a.noise_prefix}'; mixing any background clip instead")
            pool = W[tr][y[tr] == 0]
        print(f"noise pool for augmentation: {len(pool)} chunks")

        Wtr, ytr = [W[tr]], [y[tr]]
        for _ in range(a.n_aug):
            Wtr.append(np.stack([at.augment(w, rng, pool) for w in W[tr]]))
            ytr.append(y[tr])
        Wtr, ytr = np.concatenate(Wtr), np.concatenate(ytr)
        yva, yte, names_te = y[va], y[te], names[te]

        yam = load_yamnet(a.yamnet)
        print("extracting YAMNet embeddings (this is the slow step, only done once)...", flush=True)
        Etr = embed(yam, Wtr, "train")
        Eva = embed(yam, W[va], "validation")
        Ete = embed(yam, W[te], "test")
        if a.cache:
            np.savez(a.cache, Etr=Etr, ytr=ytr, Eva=Eva, yva=yva, Ete=Ete, yte=yte, names_te=names_te,
                     fp=fp, classes=np.array(classes), n_aug=a.n_aug, seed=a.seed, noise_prefix=a.noise_prefix)
            print("saved embedding cache to", a.cache)

    mu, sd = Etr.mean(0), Etr.std(0) + 1e-6
    Etr, Eva, Ete = [(e - mu) / sd for e in (Etr, Eva, Ete)]
    np.savez(f"{a.out}/head_norm.npz", mean=mu, std=sd, classes=np.array(classes))

    K = len(classes)
    cnt = np.bincount(ytr, minlength=K)
    cw = (len(ytr) / (K * np.maximum(cnt, 1))) ** a.weight_power
    print("class weights:", {c: round(float(w), 2) for c, w in zip(classes, cw)})

    reg = tf.keras.regularizers.l2(a.l2) if a.l2 > 0 else None
    head_layers = [
        layers.Input((Etr.shape[1],)),
        layers.Dense(a.hidden, activation="relu", kernel_regularizer=reg),
        layers.Dropout(a.dropout),
    ]
    if a.extra_dense > 0:
        head_layers.extend([
            layers.Dense(a.extra_dense, activation="relu", kernel_regularizer=reg),
            layers.Dropout(a.dropout),
        ])
    head_layers.append(layers.Dense(K, activation="softmax", kernel_regularizer=reg))
    head = models.Sequential(head_layers, name="yamnet_head")

    ytr_target, yva_target = ytr, yva
    if a.focal_gamma > 0:
        ytr_target = tf.keras.utils.to_categorical(ytr, K)
        yva_target = tf.keras.utils.to_categorical(yva, K)
        gamma = a.focal_gamma
        def focal_loss(y_true, y_pred):
            y_pred = tf.clip_by_value(y_pred, 1e-7, 1.0 - 1e-7)
            ce = -y_true * tf.math.log(y_pred)
            w = tf.math.pow(1.0 - y_pred, gamma)
            return tf.reduce_sum(w * ce, axis=-1)
        loss_fn = focal_loss
    elif a.label_smoothing > 0:
        ytr_target = tf.keras.utils.to_categorical(ytr, K)
        yva_target = tf.keras.utils.to_categorical(yva, K)
        loss_fn = tf.keras.losses.CategoricalCrossentropy(label_smoothing=a.label_smoothing)
    else:
        loss_fn = "sparse_categorical_crossentropy"

    head.compile("adam", loss_fn, metrics=["accuracy"])
    print("head parameters:", head.count_params())
    head.fit(Etr, ytr_target, validation_data=(Eva, yva_target), epochs=a.epochs, batch_size=a.batch,
             class_weight=dict(enumerate(cw)), verbose=2,
             callbacks=[tf.keras.callbacks.EarlyStopping(patience=10, restore_best_weights=True),
                        tf.keras.callbacks.ReduceLROnPlateau(factor=0.5, patience=4, min_lr=1e-5)])

    pv, pt = head.predict(Eva, verbose=0), head.predict(Ete, verbose=0)
    vf1 = float(f1_score(yva, pv.argmax(1), average="macro"))
    print(f"VALIDATION macro-F1 = {vf1:.3f}  (use this, not the test score, to choose between settings)")
    pred = pt.argmax(1)
    mf1 = float(f1_score(yte, pred, average="macro"))
    labels = list(range(K))
    report = classification_report(yte, pred, labels=labels, target_names=classes, zero_division=0)
    print(report)
    print(f"TEST macro-F1 = {mf1:.3f}  (target >= 0.90)")
    print("test clips per class:", {c: int((yte == i).sum()) for i, c in enumerate(classes)})
    rep = classification_report(yte, pred, labels=labels, target_names=classes, zero_division=0, output_dict=True)
    cm = confusion_matrix(yte, pred, labels=labels)
    json.dump({"val_macro_f1": vf1, "test_macro_f1": mf1, "target": 0.90, "classes": classes,
               "per_class": {c: {k: round(float(rep[c][k]), 4) for k in ("precision", "recall", "f1-score")}
                                | {"support": int(rep[c]["support"])} for c in classes},
               "confusion_matrix_rows_actual_cols_predicted": cm.tolist()},
              open(f"{a.out}/metrics.json", "w"), indent=2)
    open(f"{a.out}/classification_report.txt", "w").write(report + f"\nTEST macro-F1 = {mf1:.3f}\n")

    fig, ax = plt.subplots(figsize=(5.2, 4.4))
    ax.imshow(cm, cmap="Blues")
    for i in range(K):
        for j in range(K):
            ax.text(j, i, int(cm[i, j]), ha="center", va="center", fontsize=10)
    ax.set_xticks(range(K)); ax.set_xticklabels(classes, rotation=30, ha="right")
    ax.set_yticks(range(K)); ax.set_yticklabels(classes)
    ax.set_xlabel("Predicted"); ax.set_ylabel("Actual"); ax.set_title("YAMNet + head: confusion matrix (test)")
    fig.tight_layout(); fig.savefig(f"{a.out}/confusion_matrix.png", dpi=200); plt.close(fig)

    with open(f"{a.out}/test_predictions.csv", "w", newline="") as fh:
        wr = csv.writer(fh)
        wr.writerow(["clip", "true", "pred"] + [f"p_{c}" for c in classes])
        for nm, t_, p_, pr in zip(names_te, yte, pred, pt):
            wr.writerow([nm, classes[t_], classes[p_]] + [round(float(v), 4) for v in pr])
    np.savez(f"{a.out}/probs_val.npz", p=1 - pv[:, 0], y=(yva != 0).astype(int), probs=pv, y_multi=yva)
    np.savez(f"{a.out}/probs_test.npz", p=1 - pt[:, 0], y=(yte != 0).astype(int), probs=pt, y_multi=yte)

    head.save(f"{a.out}/head.keras")
    conv = tf.lite.TFLiteConverter.from_keras_model(head)
    open(f"{a.out}/head.tflite", "wb").write(conv.convert())
    print("head.tflite size (KB):", os.path.getsize(f"{a.out}/head.tflite") // 1024,
          "(plus YAMNet itself, about 3.7 MB, on the phone)")


if __name__ == "__main__":
    main()
