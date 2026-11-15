"""
Convert SEHAT Recorder sessions into the .npz that sehat_pipeline/motion_train.py expects.

Usage:
    # unzip every shared session zip into one folder, then:
    python sessions_to_npz.py --sessions path/to/sessions --out motion.npz

Each session folder holds sensors.csv, events.csv, meta.json (written by the app).

Output arrays:
    X        (N, 100, 7) float32   channels: ax ay az gx gy gz baro_delta   (50 Hz, 2 s)
    y        (N,)  0 = normal activity, 1 = fall / struggle / shove
    subject  (N,)  volunteer id      (motion_train.py splits BY subject)
    activity (N,)  original label
    phone    (N,)  phone model
    position (N,)  phone position
"""
import argparse, glob, json, os
import numpy as np
import pandas as pd

FS, WIN, STEP = 50, 100, 25       # 50 Hz, 2 s windows, 0.5 s step
TRIM = 1.0                        # seconds trimmed from each end of normal activities
MAX_GAP = 0.2                     # seconds; windows spanning a longer sensor gap are dropped
POSITIVE = {"fall_forward", "fall_backward", "fall_side", "struggle", "shove"}
FALLS = {"fall_forward", "fall_backward", "fall_side"}


def resample(s, code, cols, grid):
    sub = s[s.s == code].sort_values("t_ns").drop_duplicates("t_ns")
    if sub.empty:
        return None, None
    t = sub.t_ns.values / 1e9
    return np.stack([np.interp(grid, t, sub[c].values.astype(float)) for c in cols], 1), t


def gap_mask(t, grid):
    """True where the grid sits inside a gap in the original sensor timestamps."""
    bad = np.zeros(len(grid), bool)
    for i in np.where(np.diff(t) > MAX_GAP)[0]:
        bad |= (grid > t[i]) & (grid < t[i + 1])
    return bad


def intervals(ev):
    opened, out, marks = {}, [], []
    for r in ev.itertuples():
        t = r.t_ns / 1e9
        if r.type == "start":
            opened[r.label] = t
        elif r.type == "end" and r.label in opened:
            out.append((r.label, opened.pop(r.label), t))
        elif r.type == "mark":
            marks.append(t)
    return out, np.array(marks)


def convert(d):
    meta = json.load(open(f"{d}/meta.json"))
    s = pd.read_csv(f"{d}/sensors.csv")
    ev = pd.read_csv(f"{d}/events.csv")
    acc_raw = s[s.s == "a"].t_ns.values
    gyr_raw = s[s.s == "g"].t_ns.values
    if len(acc_raw) < 10 or len(gyr_raw) < 10:
        print(f"  skip {d}: missing accelerometer/gyroscope data")
        return []

    t0 = max(acc_raw.min(), gyr_raw.min()) / 1e9
    t1 = min(acc_raw.max(), gyr_raw.max()) / 1e9
    grid = np.arange(t0, t1, 1 / FS)
    acc, ta = resample(s, "a", ["v0", "v1", "v2"], grid)
    gyr, tg = resample(s, "g", ["v0", "v1", "v2"], grid)
    bar, _ = resample(s, "p", ["v0"], grid)
    if bar is None:
        bar = np.zeros((len(grid), 1))
    data = np.concatenate([acc, gyr, bar], 1).astype("float32")
    bad = gap_mask(ta, grid) | gap_mask(tg, grid)

    ivs, marks = intervals(ev)
    rows = []
    for label, a, b in ivs:
        lo, hi = (a, b) if label in FALLS else (a + TRIM, b - TRIM)
        for i in range(0, len(grid) - WIN + 1, STEP):
            ts, te = grid[i], grid[i + WIN - 1]
            if ts < lo or te > hi or bad[i:i + WIN].any():
                continue
            if label in FALLS:
                inside = marks[(marks > a) & (marks < b)]
                if len(inside):  # keep only windows with the impact mid-window
                    if not ((inside >= ts + 0.4) & (inside <= te - 0.4)).any():
                        continue
            w = data[i:i + WIN].copy()
            w[:, 6] -= w[0, 6]  # barometer -> change relative to window start
            rows.append((w, int(label in POSITIVE), label, meta["volunteer"],
                         meta["phone"], meta["position"]))
    if any(l in FALLS for l, _, _ in ivs) and len(marks) == 0:
        print(f"  warning {d}: fall recorded but no MARK pressed; kept all windows in the interval")
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sessions", required=True)
    ap.add_argument("--out", default="motion.npz")
    a = ap.parse_args()

    rows = []
    for d in sorted(glob.glob(os.path.join(a.sessions, "*", ""))):
        if os.path.exists(os.path.join(d, "meta.json")):
            print("session:", d)
            rows += convert(d.rstrip("/"))
    if not rows:
        raise SystemExit("No windows produced. Check that sessions contain labelled activities.")

    X = np.stack([r[0] for r in rows]).astype("float32")
    y = np.array([r[1] for r in rows], dtype="int32")
    np.savez(a.out, X=X, y=y,
             subject=np.array([r[3] for r in rows]),
             activity=np.array([r[2] for r in rows]),
             phone=np.array([r[4] for r in rows]),
             position=np.array([r[5] for r in rows]))
    print(f"\nsaved {a.out}: X{X.shape}  positives={int(y.sum())}  negatives={int((1 - y).sum())}")
    print("volunteers:", len(set(r[3] for r in rows)))
    print(pd.Series([r[2] for r in rows]).value_counts().to_string())


if __name__ == "__main__":
    main()
