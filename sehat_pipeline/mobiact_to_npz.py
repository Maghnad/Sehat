"""
Convert the MobiAct dataset (RAW .txt files) into the .npz that motion_train.py expects.

MobiAct raw files are named  <LABEL>_<sensor>_<subject>_<trial>.txt  (for example WAL_acc_5_1.txt,
FKL_gyro_3_2.txt) with one sensor per file: acc (timestamp ns, x, y, z in m/s^2, gravity included)
and gyro (timestamp ns, x, y, z in rad/s). This script pairs the acc and gyro file of each trial,
puts both on a common 50 Hz grid (4x oversampling + averaging as a simple anti-alias filter) and
cuts 2 s windows (100 samples).

Labelling (y):
    falls  FOL, FKL, BSC, SDL : windows centred on the IMPACT (the largest acceleration peak, shifted
                                by -0.6 ... +0.6 s) = 1
                                windows well before the impact (standing) and well after it (lying) = 0
    ADLs   STD WAL JOG JUM STU STN SCH SIT CHU CSI CSO LYI : all windows = 0
    scenario recordings (SLH, SBW, ...) are skipped: their raw files carry no per-activity labels.

Usage (PowerShell, one line):
    python mobiact_to_npz.py --root "C:\\...\\MobiAct_Dataset" --out motion_mobiact.npz

Output arrays: X (N,100,6) float32 [acc xyz, gyro xyz], y (N,), subject (N,) "S01".., activity (N,)
Then:  python motion_train.py --data motion_mobiact.npz --arch cnn --out out_mobiact_cnn

MobiAct may only be used under the signed usage agreement: research only, no sharing of the data,
and prior approval from the BMI lab before publishing results derived from it.
"""
import argparse, collections, os, re
import numpy as np

FS, WIN, STRIDE = 50, 100, 25
FALLS = {"FOL", "FKL", "BSC", "SDL"}
ADLS = {"STD", "WAL", "JOG", "JUM", "STU", "STN", "SCH", "SIT", "CHU", "CSI", "CSO", "LYI"}
PAT = re.compile(r"^([A-Za-z]{3})_(acc|gyro|ori)_(\d+)_(\d+)\.txt$", re.I)


def read_xyz(path):
    """timestamp + 3 values per line; header/comment lines are skipped."""
    rows = []
    with open(path, "r", errors="ignore") as f:
        for line in f:
            s = line.strip()
            if not s or not (s[0].isdigit() or s[0] in "-+."):
                continue
            parts = [p for p in re.split(r"[,\s;]+", s) if p]
            if len(parts) < 4:
                continue
            try:
                rows.append([float(p) for p in parts[:4]])
            except ValueError:
                continue
    if len(rows) < 10:
        return None
    a = np.array(rows, dtype=np.float64)
    a = a[np.argsort(a[:, 0])]
    keep = np.concatenate([[True], np.diff(a[:, 0]) > 0])
    return a[keep]


def to_50hz(t, v, n):
    """Linear interpolation onto a 4x finer grid, then average groups of 4 (cheap low-pass)."""
    g = (np.arange(n * 4) + 0.5) / (4 * FS)
    out = np.stack([np.interp(g, t, v[:, k]) for k in range(v.shape[1])], 1)
    return out.reshape(n, 4, -1).mean(1)


def load_trial(acc_path, gyro_path):
    acc, gyro = read_xyz(acc_path), read_xyz(gyro_path)
    if acc is None or gyro is None:
        return None
    t0 = min(acc[0, 0], gyro[0, 0])
    ta, tg = (acc[:, 0] - t0) / 1e9, (gyro[:, 0] - t0) / 1e9
    start, end = max(ta[0], tg[0]), min(ta[-1], tg[-1])
    if end - start < 3.0:
        return None
    n = int((end - start) * FS)
    # shift so that the common grid starts at 0
    A = to_50hz(ta - start, acc[:, 1:4], n)
    G = to_50hz(tg - start, gyro[:, 1:4], n)
    return np.concatenate([A, G], 1).astype("float32")


def windows_for_trial(label, X, rng, max_win):
    n = len(X)
    out = []
    if label in FALLS:
        mag = np.linalg.norm(X[:, :3], axis=1)
        sm = np.convolve(mag, np.ones(5) / 5, mode="same")
        ip = int(np.argmax(sm))
        for sh in (-30, -15, 0, 15, 30):  # -0.6 ... +0.6 s around the impact
            s = ip + sh - WIN // 2
            if 0 <= s <= n - WIN:
                out.append((s, 1, label))
        pre, post = [], []
        for s in range(0, n - WIN + 1, STRIDE):
            e = s + WIN
            if e <= ip - 75:
                pre.append((s, 0, "pre-fall"))
            elif s >= ip + 125:
                post.append((s, 0, "post-fall"))
        for lst in (pre, post):
            rng.shuffle(lst)
            out += lst[:3]
        return out, float(sm[ip])
    trim = 100 if n > 3000 else 25  # 2 s trimmed from long recordings, 0.5 s from short ones
    starts = list(range(trim, n - WIN - trim + 1, STRIDE)) or list(range(0, n - WIN + 1, STRIDE))
    if len(starts) > max_win:
        starts = sorted(rng.choice(starts, max_win, replace=False).tolist())
    return [(s, 0, label) for s in starts], None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="folder containing the MobiAct raw .txt files (searched recursively)")
    ap.add_argument("--out", default="motion_mobiact.npz")
    ap.add_argument("--max-windows-per-trial", type=int, default=60,
                    help="cap for long recordings (the 5 minute standing/walking trials)")
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    rng = np.random.default_rng(a.seed)

    trials, exts, examples = collections.defaultdict(dict), collections.Counter(), []
    for dp, _, files in os.walk(a.root):
        for f in files:
            exts[os.path.splitext(f)[1].lower()] += 1
            if len(examples) < 6:
                examples.append(os.path.join(dp, f))
            m = PAT.match(f)
            if m:
                lab, sen, sub, tr = m.group(1).upper(), m.group(2).lower(), int(m.group(3)), int(m.group(4))
                trials[(lab, sub, tr)][sen] = os.path.join(dp, f)
    use = {k: v for k, v in trials.items() if "acc" in v and "gyro" in v and (k[0] in FALLS or k[0] in ADLS)}
    print(f"files by extension: {dict(exts)}")
    print(f"trials with acc + gyro: {len(use)} (labels {sorted({k[0] for k in use})}); "
          f"scenario/other trials skipped: {len(trials) - len(use)}")
    if not use:
        print("No usable trials found. Example files seen:", *examples, sep="\n  ")
        raise SystemExit("Point --root at the folder with the raw .txt files named like WAL_acc_5_1.txt")

    Xs, ys, subs, acts = [], [], [], []
    peaks, skipped = [], 0
    for (lab, sub, tr), p in sorted(use.items()):
        X = load_trial(p["acc"], p["gyro"])
        if X is None or len(X) < WIN:
            skipped += 1
            continue
        wins, peak = windows_for_trial(lab, X, rng, a.max_windows_per_trial)
        if peak is not None:
            peaks.append(peak)
        for s, y, name in wins:
            Xs.append(X[s:s + WIN])
            ys.append(y)
            subs.append(f"S{sub:02d}")
            acts.append(name)
    if not Xs:
        raise SystemExit("No windows were produced.")
    X = np.stack(Xs).astype("float32")
    y = np.array(ys, dtype="int32")
    np.savez(a.out, X=X, y=y, subject=np.array(subs), activity=np.array(acts))
    print(f"\nsaved {a.out}: X{X.shape} | fall windows {int(y.sum())} | normal windows {int((1 - y).sum())} "
          f"| subjects {len(set(subs))} | trials skipped (too short/unreadable): {skipped}")
    if peaks:
        print(f"fall impact peak (smoothed |acc|, m/s^2): median {np.median(peaks):.1f}, "
              f"min {np.min(peaks):.1f}  (sanity check: should be clearly above the ~9.8 of a phone at rest)")
    print("windows per activity:", dict(sorted(collections.Counter(acts).items())))


if __name__ == "__main__":
    main()