"""
Convert the UniMiB SHAR dataset (smartphone accelerometer, 30 subjects,
9 activities of daily living + 8 fall types) into the .npz that motion_train.py expects.

Usage:
    python unimib_to_npz.py --data path/to/UniMiB-SHAR/data --out motion.npz

The folder must contain  acc_data.mat  and  acc_labels.mat.
    acc_data   : N x (3*T)  each row = T x-values, then T y-values, then T z-values (T = 151 at 50 Hz)
    acc_labels : N x 3      [class 1..17, subject 1..30, trial]
Classes 1-9 are daily activities (label 0); classes 10-17 are falls (label 1).

Output arrays:
    X        (N, T, 3) float32
    y        (N,) 0 = normal activity, 1 = fall
    subject  (N,) "S01".."S30"   (motion_train.py splits BY subject)
    activity (N,) original class id 1..17
"""
import argparse, os
import numpy as np
from scipy.io import loadmat


def first_array(path):
    d = loadmat(path)
    keys = [k for k in d if not k.startswith("__")]
    if not keys:
        raise SystemExit(f"No data found in {path}")
    return d[keys[0]], keys[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="folder with acc_data.mat and acc_labels.mat")
    ap.add_argument("--out", default="motion.npz")
    ap.add_argument("--fall-min-class", type=int, default=10)
    a = ap.parse_args()

    raw, k1 = first_array(os.path.join(a.data, "acc_data.mat"))
    lab, k2 = first_array(os.path.join(a.data, "acc_labels.mat"))
    print(f"acc_data['{k1}'] shape {raw.shape} | acc_labels['{k2}'] shape {lab.shape}")
    n, cols = raw.shape
    if cols % 3 == 0:
        blocks = 3
    elif cols % 4 == 0:  # some copies also store the signal magnitude as a 4th block
        blocks = 4
    else:
        raise SystemExit(f"Unexpected number of columns ({cols}); expected 3*T or 4*T")
    T = cols // blocks
    X = raw.reshape(n, blocks, T)[:, :3, :].transpose(0, 2, 1).astype("float32")  # (N, T, 3)

    cls = lab[:, 0].astype(int)
    subj = lab[:, 1].astype(int)
    y = (cls >= a.fall_min_class).astype("int32")

    mag = np.linalg.norm(X, axis=2).mean()
    print(f"samples: {n} | window length: {T} | mean acceleration magnitude: {mag:.2f}")
    if not (8.0 < mag < 12.0 or 0.8 < mag < 1.3):
        print("WARNING: magnitude is not close to 9.8 (m/s^2) or 1 (g). Check the column layout.")
    print("class counts:", {int(c): int((cls == c).sum()) for c in np.unique(cls)})
    print(f"fall windows: {int(y.sum())} | normal windows: {int((1 - y).sum())} | subjects: {len(np.unique(subj))}")

    np.savez(a.out, X=X, y=y, subject=np.array([f"S{s:02d}" for s in subj]), activity=cls)
    print("saved", a.out)


if __name__ == "__main__":
    main()
