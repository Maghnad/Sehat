"""
Prepare a trained motion model for the SEHAT fall-test Android app.

Reads a folder written by motion_train.py (model.keras, norm.json, metrics.json) and writes three
files into the app's assets folder:
    fall_model.tflite   the model as a plain float32 TensorFlow Lite file (no quantization, so the
                        app needs no scale/zero-point handling)
    fall_config.json    window length, sampling rate, feature type, normalisation, threshold, alert rule
    golden_test.json    known inputs with the outputs expected from the Python model. The app's
                        "Self-test" button recomputes them on the phone, which proves that the phone
                        code (resampling, gravity features, TFLite) matches the Python pipeline.

Usage (from the sehat_pipeline folder):
    python export_for_android.py --run out_mobiact_tcn_grav --data motion_mobiact.npz ^
        --assets "C:\\...\\sehat_fall_test\\app\\src\\main\\assets"
"""
import argparse, json, os
import numpy as np
import tensorflow as tf

from motion_train import gravity_align

FS, WIN = 50, 100


def window_from_stream(ta, A, tg, G, t0):
    """Reference resampler: 4x oversampling with linear interpolation, then average groups of 4.
    The Android code must reproduce this exactly (same method as mobiact_to_npz.py)."""
    g = t0 + (np.arange(WIN * 4) + 0.5) / (4.0 * FS)
    acc = np.stack([np.interp(g, ta, A[:, k]) for k in range(3)], 1)
    gyr = np.stack([np.interp(g, tg, G[:, k]) for k in range(3)], 1)
    return np.concatenate([acc, gyr], 1).reshape(WIN, 4, 6).mean(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True, help="output folder of motion_train.py")
    ap.add_argument("--data", required=True, help="the .npz used for that run (to pick example windows)")
    ap.add_argument("--assets", required=True, help="the app's src/main/assets folder")
    ap.add_argument("--persist", type=int, default=2, help="consecutive flagged windows needed for an alert")
    ap.add_argument("--n-examples", type=int, default=6, help="example windows of each kind")
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    os.makedirs(a.assets, exist_ok=True)
    rng = np.random.default_rng(a.seed)

    model = tf.keras.models.load_model(os.path.join(a.run, "model.keras"))
    norm = json.load(open(os.path.join(a.run, "norm.json")))
    thr = float(json.load(open(os.path.join(a.run, "metrics.json")))["test"]["threshold"])
    orient = norm.get("orient", "raw")
    mean = np.array(norm["mean"], dtype="float32").reshape(1, 1, -1)
    std = np.array(norm["std"], dtype="float32").reshape(1, 1, -1)

    def model_input(raw):                       # raw: (n, 100, 6) in m/s^2 and rad/s
        x = gravity_align(raw) if orient == "gravity" else raw
        return ((x - mean) / std).astype("float32")

    # ---- float32 TFLite model
    tfl = tf.lite.TFLiteConverter.from_keras_model(model).convert()
    open(os.path.join(a.assets, "fall_model.tflite"), "wb").write(tfl)
    interp = tf.lite.Interpreter(model_content=tfl)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    print("TFLite input", inp["shape"], inp["dtype"].__name__, "| output", out["shape"], out["dtype"].__name__,
          "| size", len(tfl) // 1024, "KB")

    def tflite_prob(x):
        interp.set_tensor(inp["index"], x[None].astype("float32"))
        interp.invoke()
        return float(interp.get_tensor(out["index"]).ravel()[0])

    # ---- example windows with the Python model's answers
    d = np.load(a.data, allow_pickle=True)
    X, y = d["X"].astype("float32"), d["y"]
    if X.shape[2] != 6:
        raise SystemExit(f"The app expects 6 raw channels (acc xyz, gyro xyz); {a.data} has {X.shape[2]}.")
    idx = np.concatenate([rng.choice(np.where(y == 1)[0], a.n_examples, replace=False),
                          rng.choice(np.where(y == 0)[0], a.n_examples, replace=False)])
    raw = X[idx]
    xin = model_input(raw)
    p_keras = model.predict(xin, verbose=0).ravel()
    p_tfl = np.array([tflite_prob(x) for x in xin])
    print(f"Keras vs TFLite on {len(idx)} windows: max difference {np.abs(p_keras - p_tfl).max():.2e}")
    windows = [{"label": int(y[i]), "raw": raw[j].round(6).tolist(), "expected_prob": float(p_keras[j])}
               for j, i in enumerate(idx)]
    print("example fall windows -> probability:", np.round(p_keras[:a.n_examples], 3).tolist())
    print("example normal windows -> probability:", np.round(p_keras[a.n_examples:], 3).tolist())

    # ---- resampling test: irregular timestamps in, a 100 x 6 window out
    base = 5000.0
    ta = base + np.cumsum(rng.uniform(0.006, 0.014, 420))          # about 100 Hz with jitter
    tg = base + 0.003 + np.cumsum(rng.uniform(0.007, 0.015, 380))   # about 90 Hz, different clock offset
    f = lambda t, ph: np.stack([9.8 * np.sin(2 * np.pi * 0.7 * t + ph) + 3 * np.sin(2 * np.pi * 3.1 * t),
                                4 * np.cos(2 * np.pi * 1.3 * t + ph),
                                9.8 + 2 * np.sin(2 * np.pi * 2.2 * t + 2 * ph)], 1)
    A, G = f(ta, 0.2), 0.5 * f(tg, 1.1)
    t0 = base + 1.0
    expected_win = window_from_stream(ta, A, tg, G, t0)
    stream = {"acc": np.column_stack([ta, A]).tolist(), "gyro": np.column_stack([tg, G]).tolist(),
              "t_start": t0, "expected_window": expected_win.round(6).tolist(),
              "expected_prob": float(model.predict(model_input(expected_win[None].astype("float32")), verbose=0).ravel()[0])}
    json.dump({"windows": windows, "stream": stream}, open(os.path.join(a.assets, "golden_test.json"), "w"))

    cfg = {"window": WIN, "fs": FS, "stride_ms": 500, "orient": orient,
           "mean": [float(v) for v in mean.ravel()], "std": [float(v) for v in std.ravel()],
           "threshold": thr, "persist": a.persist,
           "feature_order": ["vertical_acc", "horizontal_acc", "acc_magnitude", "vertical_gyro", "horizontal_gyro"]
           if orient == "gravity" else ["acc_x", "acc_y", "acc_z", "gyro_x", "gyro_y", "gyro_z"]}
    json.dump(cfg, open(os.path.join(a.assets, "fall_config.json"), "w"), indent=2)
    print(f"\nwrote fall_model.tflite, fall_config.json, golden_test.json to {a.assets}")
    print(f"threshold {thr:.2f} | alert rule: {a.persist} consecutive flagged windows | orient {orient}")


if __name__ == "__main__":
    main()
