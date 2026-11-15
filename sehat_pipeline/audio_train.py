"""
SEHAT-ERS audio distress classifier (scream / cry / impact / glass_break / background).

Expected layout:
    data/audio/<class_name>/<sourceid>_<anything>.wav
The text before the first "_" is the SOURCE ID (speaker / recording session).
Splits are made by source so near-duplicate clips never leak across train/test.

Usage:
    python audio_train.py --root data/audio --epochs 40
Outputs (in --out): model.keras, model.tflite, metrics.json, probs_val.npz, probs_test.npz
    probs_*.npz hold P(distress) = 1 - P(background) for fusion calibration.
"""
import argparse, csv, glob, hashlib, json, os
import numpy as np
import librosa
import tensorflow as tf
from tensorflow.keras import layers, models
from sklearn.model_selection import GroupShuffleSplit
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from sklearn.metrics import f1_score, classification_report, confusion_matrix
from sklearn.utils.class_weight import compute_class_weight

ALL_CLASSES = ["background", "scream", "cry", "impact", "glass_break"]
CLASSES = list(ALL_CLASSES)  # reduced in main() to the classes that have clips
SR, DUR, N_MELS, N_FFT, HOP = 16000, 2.0, 64, 1024, 320
N = int(SR * DUR)


def fix_len(w):
    return np.pad(w, (0, max(0, N - len(w))))[:N]


def logmel(w):
    m = librosa.feature.melspectrogram(y=w, sr=SR, n_fft=N_FFT, hop_length=HOP,
                                       n_mels=N_MELS, fmin=50, fmax=8000)
    return librosa.power_to_db(m, ref=1.0, top_db=80).T.astype("float32")  # (T, mels)


def augment(w, rng, bg_pool):
    w = np.roll(w, rng.integers(-N // 8, N // 8))
    w = w * rng.uniform(0.5, 1.5)
    if len(bg_pool) and rng.random() < 0.7:  # mix in background noise at random SNR
        noise = bg_pool[rng.integers(len(bg_pool))]
        w = w + noise * rng.uniform(0.05, 0.5)
    return np.clip(w, -1, 1).astype("float32")


def loud_ref(w, fl=8000, hop=4000):
    """RMS of the loudest 0.5 s of a clip (reference level for the silence check)."""
    if len(w) <= fl:
        return float(np.sqrt(np.mean(w ** 2))) + 1e-9
    return max(float(np.sqrt(np.mean(w[i:i + fl] ** 2))) for i in range(0, len(w) - fl + 1, hop)) + 1e-9


def data_fingerprint(root, classes):
    """Hash of all wav file names and sizes, so a stale feature cache is never reused."""
    items = []
    for c in classes:
        for f in sorted(glob.glob(f"{root}/{c}/*.wav")):
            items.append(f"{c}/{os.path.basename(f)}:{os.path.getsize(f)}")
    return hashlib.md5("\n".join(items).encode()).hexdigest()


def load_dataset(root):
    waves, labels, groups, names = [], [], [], []
    dropped = 0
    for ci, c in enumerate(CLASSES):
        for f in sorted(glob.glob(f"{root}/{c}/*.wav")):
            w, _ = librosa.load(f, sr=SR, mono=True)
            ref = loud_ref(w)
            # split long recordings into 2 s chunks
            for s in range(0, max(len(w) - N // 2, 1), N):
                chunk = fix_len(w[s:s + N])
                # drop near-silent chunks (e.g. silence padding) from EVENT classes so they
                # are not mislabelled as scream / cry / glass_break; keep them for background
                if c != "background" and np.sqrt(np.mean(chunk ** 2)) < 0.1 * ref:
                    dropped += 1
                    continue
                waves.append(chunk)
                labels.append(ci)
                groups.append(os.path.basename(f).split("_")[0])
                names.append(f"{os.path.basename(f)}@{s}")
    print(f"dropped {dropped} near-silent chunks from event classes")
    return np.array(waves), np.array(labels), np.array(groups), np.array(names)


def dsconv(x, f, s=1):
    x = layers.DepthwiseConv2D(3, strides=s, padding="same", use_bias=False)(x)
    x = layers.BatchNormalization()(layers.ReLU()(x))
    x = layers.Conv2D(f, 1, use_bias=False)(x)
    return layers.BatchNormalization()(layers.ReLU()(x))


def build_model(T, M, K, width=1.0):
    f = lambda c: max(8, int(round(c * width)))
    i = layers.Input((T, M, 1))
    x = layers.Conv2D(f(16), 3, strides=2, padding="same", use_bias=False)(i)
    x = layers.BatchNormalization()(layers.ReLU()(x))
    for c, st in ((32, 1), (64, 2), (64, 1), (128, 2)):
        x = dsconv(x, f(c), st)
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dropout(0.3)(x)
    return models.Model(i, layers.Dense(K, activation="softmax")(x), name="audio_dscnn")


def spec_augment(x, y, w, time_mask=16, freq_mask=10, n_masks=2):
    """SpecAugment on a batch of normalised log-mel features (B, T, M, 1):
    random time and frequency stripes are set to 0 (the normalised mean)."""
    B, T, M = tf.shape(x)[0], tf.shape(x)[1], tf.shape(x)[2]
    for _ in range(n_masks):
        for size, length, shape in ((time_mask, T, [1, -1, 1, 1]), (freq_mask, M, [1, 1, -1, 1])):
            wd = tf.random.uniform([B, 1, 1, 1], 0, size + 1, dtype=tf.int32)
            st = tf.cast(tf.random.uniform([B, 1, 1, 1]) * tf.cast(length - wd + 1, tf.float32), tf.int32)
            idx = tf.reshape(tf.range(length), shape)
            x = tf.where((idx >= st) & (idx < st + wd), tf.zeros_like(x), x)
    return x, y, w


def main():
    global CLASSES
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--n_aug", type=int, default=2, help="augmented copies per train clip")
    ap.add_argument("--out", default="out_audio")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--noise-prefix", default="", help="mix only clips whose file name starts with this (e.g. dcbg = real ambient scenes) into training clips as noise; default: any background clip")
    ap.add_argument("--cache", default="", help="feature cache file (.npz); makes repeated experiments much faster")
    ap.add_argument("--width", type=float, default=1.0, help="model width multiplier (1 = 42 KB model, 2 = about 4x larger)")
    ap.add_argument("--specaug", action="store_true", help="apply SpecAugment (time/frequency masking) while training")
    ap.add_argument("--weight-power", type=float, default=1.0,
                    help="class-weight strength: 1 = fully balanced (default), 0.5 = square root, 0 = none")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    tf.keras.utils.set_random_seed(a.seed)
    rng = np.random.default_rng(a.seed)

    CLASSES = [c for c in ALL_CLASSES if glob.glob(f"{a.root}/{c}/*.wav")]
    if "background" not in CLASSES or len(CLASSES) < 2:
        raise SystemExit(f"Need a background folder and at least one other class under {a.root}; found {CLASSES}")
    print("classes used:", CLASSES)
    fp = data_fingerprint(a.root, CLASSES)
    cache_ok = False
    if a.cache and os.path.exists(a.cache):
        z = np.load(a.cache, allow_pickle=True)
        if (str(z["fp"]) == fp and list(z["classes"]) == CLASSES
                and int(z["n_aug"]) == a.n_aug and int(z["seed"]) == a.seed
                and (str(z["noise_prefix"]) if "noise_prefix" in z.files else "") == a.noise_prefix):
            Xtr, ytr, Xva, yva, Xte, yte, names_te = (z[k] for k in ("Xtr", "ytr", "Xva", "yva", "Xte", "yte", "names_te"))
            mu, sd = float(z["mu"]), float(z["sd"])
            cache_ok = True
            print("loaded cached features from", a.cache)
        else:
            print("cache does not match the current data/settings; recomputing")
    if not cache_ok:
        W, y, g, names = load_dataset(a.root)
        print("clips per class:", {c: int((y == i).sum()) for i, c in enumerate(CLASSES)})

        gss = GroupShuffleSplit(1, test_size=0.30, random_state=a.seed)
        tr, rest = next(gss.split(W, y, g))
        va_i, te_i = next(GroupShuffleSplit(1, test_size=0.5, random_state=a.seed)
                          .split(W[rest], y[rest], g[rest]))
        va, te = rest[va_i], rest[te_i]

        is_bg = y[tr] == 0
        if a.noise_prefix:
            is_bg = is_bg & np.char.startswith(names[tr].astype(str), a.noise_prefix)
        bg_pool = W[tr][is_bg]
        if len(bg_pool) == 0:
            print(f"WARNING: no training clips start with '{a.noise_prefix}'; mixing any background clip instead")
            bg_pool = W[tr][y[tr] == 0]
        print(f"noise pool for augmentation: {len(bg_pool)} chunks")
        Wtr, ytr = [W[tr]], [y[tr]]
        for _ in range(a.n_aug):
            Wtr.append(np.stack([augment(w, rng, bg_pool) for w in W[tr]]))
            ytr.append(y[tr])
        Wtr, ytr = np.concatenate(Wtr), np.concatenate(ytr)

        feat = lambda ws: np.stack([logmel(w) for w in ws])[..., None]
        Xtr, Xva, Xte = feat(Wtr), feat(W[va]), feat(W[te])
        mu, sd = Xtr.mean(), Xtr.std() + 1e-6
        Xtr, Xva, Xte = [(x - mu) / sd for x in (Xtr, Xva, Xte)]
        yva, yte, names_te = y[va], y[te], names[te]
        if a.cache:
            np.savez(a.cache, Xtr=Xtr, ytr=ytr, Xva=Xva, yva=yva, Xte=Xte, yte=yte, names_te=names_te,
                     mu=mu, sd=sd, fp=fp, classes=np.array(CLASSES), n_aug=a.n_aug, seed=a.seed,
                     noise_prefix=a.noise_prefix)
            print("saved feature cache to", a.cache)
    json.dump({"mean": float(mu), "std": float(sd), "sr": SR, "n_fft": N_FFT,
               "hop": HOP, "n_mels": N_MELS, "classes": CLASSES},
              open(f"{a.out}/feature_cfg.json", "w"))

    cnt = np.bincount(ytr, minlength=len(CLASSES))
    cw = (len(ytr) / (len(CLASSES) * np.maximum(cnt, 1))) ** a.weight_power
    print("class weights:", {c: round(float(w), 2) for c, w in zip(CLASSES, cw)})
    model = build_model(Xtr.shape[1], Xtr.shape[2], len(CLASSES), a.width)
    model.compile("adam", "sparse_categorical_crossentropy", metrics=["accuracy"])
    print("parameters:", model.count_params())
    cbs = [tf.keras.callbacks.EarlyStopping(patience=8, restore_best_weights=True),
           tf.keras.callbacks.ReduceLROnPlateau(factor=0.5, patience=3, min_lr=1e-5)]
    if a.specaug:
        sw = cw[ytr].astype("float32")
        ds = (tf.data.Dataset.from_tensor_slices((Xtr, ytr, sw)).shuffle(len(Xtr), seed=a.seed)
              .batch(a.batch).map(spec_augment, num_parallel_calls=tf.data.AUTOTUNE).prefetch(1))
        model.fit(ds, validation_data=(Xva, yva), epochs=a.epochs, callbacks=cbs, shuffle=False, verbose=2)
    else:
        model.fit(Xtr, ytr, validation_data=(Xva, yva), epochs=a.epochs,
                  batch_size=a.batch, class_weight=dict(enumerate(cw)), callbacks=cbs, verbose=2)

    pv, pt = model.predict(Xva, verbose=0), model.predict(Xte, verbose=0)
    vf1 = float(f1_score(yva, pv.argmax(1), average="macro"))
    print(f"VALIDATION macro-F1 = {vf1:.3f}  (use this, not the test score, to choose between settings)")
    pred = pt.argmax(1)
    mf1 = float(f1_score(yte, pred, average="macro"))
    report = classification_report(yte, pred, labels=list(range(len(CLASSES))),
                                   target_names=CLASSES, zero_division=0)
    print(report)
    print(f"TEST macro-F1 = {mf1:.3f}  (target >= 0.90)")
    print("test clips per class:", {c: int((yte == i).sum()) for i, c in enumerate(CLASSES)})
    rep = classification_report(yte, pred, labels=list(range(len(CLASSES))),
                                target_names=CLASSES, zero_division=0, output_dict=True)
    cm = confusion_matrix(yte, pred, labels=list(range(len(CLASSES))))
    json.dump({"val_macro_f1": vf1, "test_macro_f1": mf1, "target": 0.90, "classes": CLASSES,
               "per_class": {c: {k: round(float(rep[c][k]), 4) for k in ("precision", "recall", "f1-score")} | {"support": int(rep[c]["support"])} for c in CLASSES},
               "confusion_matrix_rows_actual_cols_predicted": cm.tolist()},
              open(f"{a.out}/metrics.json", "w"), indent=2)
    open(f"{a.out}/classification_report.txt", "w").write(report + f"\nTEST macro-F1 = {mf1:.3f}\n")
    fig, ax = plt.subplots(figsize=(5.2, 4.4))
    ax.imshow(cm, cmap="Blues")
    for i in range(cm.shape[0]):
        for j in range(cm.shape[1]):
            ax.text(j, i, int(cm[i, j]), ha="center", va="center", fontsize=10)
    ax.set_xticks(range(len(CLASSES))); ax.set_xticklabels(CLASSES, rotation=30, ha="right")
    ax.set_yticks(range(len(CLASSES))); ax.set_yticklabels(CLASSES)
    ax.set_xlabel("Predicted"); ax.set_ylabel("Actual"); ax.set_title("Audio model: confusion matrix (test)")
    fig.tight_layout(); fig.savefig(f"{a.out}/confusion_matrix.png", dpi=200); plt.close(fig)

    with open(f"{a.out}/test_predictions.csv", "w", newline="") as fh:
        wr = csv.writer(fh)
        wr.writerow(["clip", "true", "pred"] + [f"p_{c}" for c in CLASSES])
        for nm, t_, p_, pr in zip(names_te, yte, pred, pt):
            wr.writerow([nm, CLASSES[t_], CLASSES[p_]] + [round(float(v), 4) for v in pr])

    # P(distress) = 1 - P(background); binary labels for fusion calibration
    np.savez(f"{a.out}/probs_val.npz", p=1 - pv[:, 0], y=(yva != 0).astype(int))
    np.savez(f"{a.out}/probs_test.npz", p=1 - pt[:, 0], y=(yte != 0).astype(int))
    model.save(f"{a.out}/model.keras")

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.representative_dataset = lambda: ([Xtr[k:k + 1]] for k in range(0, min(len(Xtr), 300)))
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    conv.inference_input_type, conv.inference_output_type = tf.int8, tf.int8
    open(f"{a.out}/model.tflite", "wb").write(conv.convert())
    print("TFLite size (KB):", os.path.getsize(f"{a.out}/model.tflite") // 1024)


if __name__ == "__main__":
    main()
