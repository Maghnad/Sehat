"""
SEHAT-ERS fusion calibration + ablation.

Input CSV (one row per labelled EVENT, not per window), columns:
    M, A, C, label
      M = motion model probability, A = audio model probability,
      C = context risk (0-1, rule based), label = 1 real incident / 0 non-incident
Rows should come from the VALIDATION set; report final numbers on a held-out TEST csv.

Usage:
    python fusion_calibrate.py --val val_events.csv --test test_events.csv

It searches weights (w_m, w_a, w_c) on the simplex, picks the band thresholds
(tau1 < tau2 < tau3), prints the ablation (fusion vs best single modality)
and writes fusion_config.json for the Android app.
"""
import argparse, json, itertools
import numpy as np
import pandas as pd
from sklearn.metrics import f1_score, confusion_matrix

MIN_SPEC = 0.98  # specificity target at the CRITICAL threshold


def spec_sens(y, pred):
    tn, fp, fn, tp = confusion_matrix(y, pred, labels=[0, 1]).ravel()
    return tn / max(tn + fp, 1), tp / max(tp + fn, 1)


def best_threshold(y, r, min_spec=MIN_SPEC):
    """Lowest threshold (max sensitivity) that still meets the specificity target."""
    for t in np.unique(np.round(np.linspace(0, 1, 201), 3)):
        s, _ = spec_sens(y, r >= t)
        if s >= min_spec:
            return float(t)
    return 1.0


def best_f1(y, r):
    ts = np.linspace(0.05, 0.95, 91)
    f = [f1_score(y, r >= t) for t in ts]
    return float(max(f)), float(ts[int(np.argmax(f))])


def sens_at_spec(y, r):
    t = best_threshold(y, r)
    return spec_sens(y, r >= t)[1]


def weight_grid(step=0.05):
    n = int(round(1 / step))
    for i, j in itertools.product(range(n + 1), repeat=2):
        if i + j <= n:
            yield i * step, j * step, (n - i - j) * step


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--val", required=True)
    ap.add_argument("--test", default=None)
    ap.add_argument("--out", default="fusion_config.json")
    a = ap.parse_args()

    v = pd.read_csv(a.val)
    y = v["label"].values

    # 1) weight search: maximise sensitivity at the specificity target
    best = max(((sens_at_spec(y, wm * v.M + wa * v.A + wc * v.C), (wm, wa, wc))
                for wm, wa, wc in weight_grid()), key=lambda z: z[0])
    (wm, wa, wc) = best[1]
    r = wm * v.M + wa * v.A + wc * v.C

    # 2) band thresholds from operating points
    tau3 = best_threshold(y, r, 0.98)   # CRITICAL: very few false alarms
    tau2 = best_threshold(y, r, 0.93)   # HIGH
    tau1 = best_threshold(y, r, 0.80)   # MEDIUM (LOW = below this)
    tau1, tau2, tau3 = sorted([tau1, tau2, tau3])

    cfg = {"w_m": wm, "w_a": wa, "w_c": wc, "tau1": tau1, "tau2": tau2, "tau3": tau3}
    json.dump(cfg, open(a.out, "w"), indent=2)
    print("fusion config:", cfg)

    # 3) ablation on TEST (or VAL if no test file given)
    e = pd.read_csv(a.test) if a.test else v
    ye = e["label"].values
    re_ = wm * e.M + wa * e.A + wc * e.C
    print("\nAblation ({} set)".format("test" if a.test else "val"))
    rows = {"motion": (v.M, e.M), "audio": (v.A, e.A),
            "context": (v.C, e.C), "fusion": (r, re_)}
    f1s = {}
    for name, (sv, se) in rows.items():
        # thresholds are tuned on VAL only, then applied to the eval set
        _, t_f1 = best_f1(y, sv.values)
        t_98 = best_threshold(y, sv.values)
        f1s[name] = f1_score(ye, se.values >= t_f1)
        _, sens = spec_sens(ye, se.values >= t_98)
        print(f"{name:8s} F1={f1s[name]:.3f}  sens@spec98={sens:.3f}")
    d = f1s["fusion"] - max(f1s["motion"], f1s["audio"], f1s["context"])
    print(f"\nDelta F1 (fusion - best single) = {d:+.3f}   (must be > 0)")


if __name__ == "__main__":
    main()
