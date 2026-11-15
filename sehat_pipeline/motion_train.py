"""
SEHAT-ERS motion model training.

Input: an .npz file with
    X       float32 (N, T, C)   windows, e.g. T=100 (2 s @ 50 Hz), C=7
                                channels: acc xyz, gyro xyz, baro delta
    y       int     (N,)        0 = normal activity (ADL), 1 = fall / struggle
    subject int/str (N,)        volunteer id  (splits are done BY SUBJECT)

Usage:
    python motion_train.py --data motion.npz --arch cnn --epochs 60
    python motion_train.py --data motion.npz --arch bilstm --quant dynamic

Outputs (in --out): model.keras, model.tflite, norm.json, probs_val.npz,
probs_test.npz (feed these to fusion_calibrate.py), metrics.json
"""
import argparse, json, os
import numpy as np
import tensorflow as tf
from tensorflow.keras import layers, models
from sklearn.model_selection import GroupShuffleSplit
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from sklearn.metrics import confusion_matrix, roc_auc_score, roc_curve
from sklearn.utils.class_weight import compute_class_weight


def subject_split(y, groups, seed=0):
    """~70/15/15 split with no subject appearing in more than one set."""
    gss = GroupShuffleSplit(n_splits=1, test_size=0.30, random_state=seed)
    tr, rest = next(gss.split(np.zeros(len(y)), y, groups))
    gss2 = GroupShuffleSplit(n_splits=1, test_size=0.50, random_state=seed)
    va, te = next(gss2.split(np.zeros(len(rest)), y[rest], groups[rest]))
    return tr, rest[va], rest[te]



def gravity_align(X):
    """Rotation-independent channels, so the model does not depend on how the phone sits in the pocket.

    X is (N, T, C) with C = 3 (accelerometer) or C >= 6 (accelerometer, gyroscope, extra channels).
    The gravity direction of each window is estimated as the mean acceleration over the window, and
    each reading is split into a part ALONG gravity (vertical) and a part ACROSS it (horizontal size):
        accelerometer: vertical, horizontal size, total magnitude
        gyroscope    : rotation about the vertical, rotation size about horizontal axes
    Turning the phone in any direction leaves these numbers unchanged (checked with random rotations).
    The Android app must compute exactly the same thing from its 2 s window.
    """
    acc = X[:, :, :3]
    g = acc.mean(1)
    n = np.linalg.norm(g, axis=1, keepdims=True)
    gh = np.where(n > 1e-3, g / np.maximum(n, 1e-6), np.array([[0.0, 0.0, 1.0]]))[:, None, :]
    av = (acc * gh).sum(2)
    ah = np.linalg.norm(acc - av[:, :, None] * gh, axis=2)
    out = [av, ah, np.linalg.norm(acc, axis=2)]
    if X.shape[2] >= 6:
        gy = X[:, :, 3:6]
        wv = (gy * gh).sum(2)
        out += [wv, np.linalg.norm(gy - wv[:, :, None] * gh, axis=2)]
        out += [X[:, :, k] for k in range(6, X.shape[2])]
    return np.stack(out, 2).astype("float32")


def augment(X, rng):
    """Cheap augmentation: jitter, per-window scaling, small time shift."""
    X = X + rng.normal(0, 0.02, X.shape).astype("float32")
    X = X * rng.uniform(0.9, 1.1, (X.shape[0], 1, 1)).astype("float32")
    shift = rng.integers(-5, 6)
    return np.roll(X, shift, axis=1)


def build_cnn(T, C):
    i = layers.Input((T, C))
    x = i
    for f in (32, 64, 64):
        x = layers.Conv1D(f, 5, padding="same", activation="relu")(x)
        x = layers.BatchNormalization()(x)
        x = layers.MaxPooling1D(2)(x)
    x = layers.GlobalAveragePooling1D()(x)
    x = layers.Dropout(0.3)(x)
    return models.Model(i, layers.Dense(1, activation="sigmoid")(x), name="cnn1d")


def build_bilstm(T, C):
    i = layers.Input((T, C))
    x = layers.Conv1D(32, 5, padding="same", activation="relu")(i)
    x = layers.MaxPooling1D(2)(x)
    x = layers.Conv1D(64, 5, padding="same", activation="relu")(x)
    x = layers.Bidirectional(layers.LSTM(32))(x)
    x = layers.Dropout(0.3)(x)
    return models.Model(i, layers.Dense(1, activation="sigmoid")(x), name="cnn_bilstm")


def build_tcn(T, C):
    i = layers.Input((T, C))
    x = layers.Conv1D(32, 1)(i)
    for d in (1, 2, 4, 8):
        r = x
        x = layers.Conv1D(32, 3, padding="causal", dilation_rate=d, activation="relu")(x)
        x = layers.SpatialDropout1D(0.1)(x)
        x = layers.Conv1D(32, 3, padding="causal", dilation_rate=d, activation="relu")(x)
        x = layers.Add()([x, r])
    x = layers.GlobalAveragePooling1D()(x)
    return models.Model(i, layers.Dense(1, activation="sigmoid")(x), name="tcn")


ARCHS = {"cnn": build_cnn, "bilstm": build_bilstm, "tcn": build_tcn}


def pick_threshold(y, p, min_spec=0.98):
    """Highest-sensitivity threshold that still meets the specificity target."""
    best = 0.5
    for t in np.linspace(0.05, 0.99, 95):
        tn, fp, fn, tp = confusion_matrix(y, p >= t, labels=[0, 1]).ravel()
        spec = tn / max(tn + fp, 1)
        if spec >= min_spec:
            best = t
            break  # thresholds ascend, so first hit = most sensitive
    return float(best)


def evaluate(y, p, t):
    tn, fp, fn, tp = confusion_matrix(y, p >= t, labels=[0, 1]).ravel()
    return {
        "threshold": t,
        "sensitivity": float(tp / max(tp + fn, 1)),
        "specificity": float(tn / max(tn + fp, 1)),
        "auc": float(roc_auc_score(y, p)),
    }



def save_plots(out, hist, y, p, t):
    """Figures for the report: training curves, ROC curve, confusion matrix (test set)."""
    fig, ax = plt.subplots(1, 2, figsize=(9, 3.4))
    ax[0].plot(hist.history["loss"], label="train"); ax[0].plot(hist.history["val_loss"], label="validation")
    ax[0].set_xlabel("epoch"); ax[0].set_ylabel("loss"); ax[0].legend(); ax[0].set_title("Loss")
    ax[1].plot(hist.history["auc"], label="train"); ax[1].plot(hist.history["val_auc"], label="validation")
    ax[1].set_xlabel("epoch"); ax[1].set_ylabel("AUC"); ax[1].legend(); ax[1].set_title("AUC")
    fig.tight_layout(); fig.savefig(f"{out}/training_curves.png", dpi=200); plt.close(fig)

    fpr, tpr, _ = roc_curve(y, p)
    fig, ax = plt.subplots(figsize=(4, 4))
    ax.plot(fpr, tpr, label=f"AUC = {roc_auc_score(y, p):.3f}"); ax.plot([0, 1], [0, 1], "--", c="gray")
    ax.set_xlabel("False positive rate (1 - specificity)"); ax.set_ylabel("True positive rate (sensitivity)")
    ax.set_title("ROC curve (test set)"); ax.legend(loc="lower right")
    fig.tight_layout(); fig.savefig(f"{out}/roc_curve.png", dpi=200); plt.close(fig)

    cm = confusion_matrix(y, p >= t, labels=[0, 1])
    fig, ax = plt.subplots(figsize=(3.8, 3.4))
    ax.imshow(cm, cmap="Blues")
    for i in range(2):
        for j in range(2):
            ax.text(j, i, int(cm[i, j]), ha="center", va="center", color="black", fontsize=12)
    ax.set_xticks([0, 1]); ax.set_xticklabels(["Normal", "Fall"]); ax.set_yticks([0, 1]); ax.set_yticklabels(["Normal", "Fall"])
    ax.set_xlabel("Predicted"); ax.set_ylabel("Actual"); ax.set_title(f"Confusion matrix (threshold {t:.2f})")
    fig.tight_layout(); fig.savefig(f"{out}/confusion_matrix.png", dpi=200); plt.close(fig)

def export_tflite(model, X_rep, path, quant):
    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    if quant == "int8":
        def rep():
            for k in range(min(200, len(X_rep))):
                yield [X_rep[k:k + 1]]
        conv.representative_dataset = rep
        conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
        conv.inference_input_type = tf.int8
        conv.inference_output_type = tf.int8
    else:  # dynamic-range: safer for LSTM layers
        conv.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS, tf.lite.OpsSet.SELECT_TF_OPS]
        conv._experimental_lower_tensor_list_ops = False
    with open(path, "wb") as f:
        f.write(conv.convert())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--arch", choices=ARCHS, default="cnn")
    ap.add_argument("--epochs", type=int, default=60)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--quant", choices=["int8", "dynamic"], default="int8")
    ap.add_argument("--out", default="out_motion")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--orient", choices=["raw", "gravity"], default="raw",
                    help="gravity = orientation-independent channels (recommended when phone placement varies)")
    ap.add_argument("--spec-target", type=float, default=0.98,
                    help="specificity the threshold must reach on the VALIDATION set (try 0.99 for fewer false alarms)")
    ap.add_argument("--channels", default="",
                    help="comma-separated channel indexes to keep, e.g. 0,1,2,3,4,5 to drop the barometer")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    tf.keras.utils.set_random_seed(a.seed)
    rng = np.random.default_rng(a.seed)

    d = np.load(a.data, allow_pickle=True)
    X, y, g = d["X"].astype("float32"), d["y"].astype("int32"), d["subject"]
    act = d["activity"].astype(str) if "activity" in d.files else np.array(["?"] * len(y))
    if a.channels:
        X = X[:, :, [int(i) for i in a.channels.split(",")]]
    if a.orient == "gravity":
        X = gravity_align(X)
    print("data:", X.shape, "| fall windows:", int(y.sum()), "| subjects:", len(set(g)))
    tr, va, te = subject_split(y, g, a.seed)

    mean = X[tr].mean(axis=(0, 1), keepdims=True)
    std = X[tr].std(axis=(0, 1), keepdims=True) + 1e-6
    X = (X - mean) / std
    json.dump({"mean": mean.ravel().tolist(), "std": std.ravel().tolist(), "orient": a.orient},
              open(f"{a.out}/norm.json", "w"))

    Xtr = np.concatenate([X[tr], augment(X[tr], rng)])
    ytr = np.concatenate([y[tr], y[tr]])
    cw = compute_class_weight("balanced", classes=np.array([0, 1]), y=ytr)

    model = ARCHS[a.arch](X.shape[1], X.shape[2])
    model.compile("adam", "binary_crossentropy",
                  metrics=[tf.keras.metrics.AUC(name="auc")])
    hist = model.fit(Xtr, ytr, validation_data=(X[va], y[va]), epochs=a.epochs,
              batch_size=a.batch, class_weight={0: cw[0], 1: cw[1]},
              callbacks=[tf.keras.callbacks.EarlyStopping(
                  patience=8, restore_best_weights=True, monitor="val_auc",
                  mode="max")], verbose=2)

    pv = model.predict(X[va], verbose=0).ravel()
    pt = model.predict(X[te], verbose=0).ravel()
    t = pick_threshold(y[va], pv, a.spec_target)  # threshold chosen on VAL only
    metrics = {"val": evaluate(y[va], pv, t), "test": evaluate(y[te], pt, t),
               "targets": {"sensitivity": 0.95, "specificity": a.spec_target},
               "counts": {"val_windows": int(len(va)), "test_windows": int(len(te)),
                          "test_falls": int(y[te].sum()), "test_subjects": int(len(set(g[te])))}}
    # diagnostic only (uses test labels, so it must NOT be used to pick the threshold):
    fpr, tpr, _ = roc_curve(y[te], pt)
    metrics["test_diagnostic_sensitivity_at_specificity"] = {
        f"{int(sp * 100)}%": float(tpr[np.where(fpr <= 1 - sp)[0][-1]]) for sp in (0.95, 0.98, 0.99)}
    print(json.dumps(metrics, indent=2))
    json.dump(metrics, open(f"{a.out}/metrics.json", "w"), indent=2)

    # per-window predictions and a per-activity table at the chosen threshold
    import csv
    flagged = pt >= t
    with open(f"{a.out}/test_predictions.csv", "w", newline="") as fh:
        wr = csv.writer(fh)
        wr.writerow(["subject", "activity", "true", "p_fall", "flagged"])
        for sj, ac, yy, pp, fl in zip(g[te], act[te], y[te], pt, flagged):
            wr.writerow([sj, ac, int(yy), round(float(pp), 4), int(fl)])
    print(f"\nPer-activity result on the test windows at threshold {t:.2f} "
          "(fall activities: share detected; others: share wrongly flagged = false alarms)")
    for name in sorted(set(act[te])):
        m = act[te] == name
        print(f"  {name:10s} windows={int(m.sum()):5d}  flagged={flagged[m].mean():6.1%}")
    print()

    save_plots(a.out, hist, y[te], pt, t)
    np.savez(f"{a.out}/probs_val.npz", p=pv, y=y[va], subject=g[va])
    np.savez(f"{a.out}/probs_test.npz", p=pt, y=y[te], subject=g[te])
    model.save(f"{a.out}/model.keras")
    export_tflite(model, X[tr], f"{a.out}/model.tflite", a.quant)
    print("TFLite size (KB):", os.path.getsize(f"{a.out}/model.tflite") // 1024)


if __name__ == "__main__":
    main()