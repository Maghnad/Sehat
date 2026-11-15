"""
Continuous-stream evaluation of a trained motion model on WHOLE MobiAct trials.

The training windows are cut around the fall impact, which makes the task easier than real use.
In real use the model sees a sliding window that hits a fall at an arbitrary position. This script
slides a 2 s window (default stride 0.5 s) over every complete trial of the TEST volunteers and
reports what a phone would actually do:

  * falls     : was there an alert from 1 s before to 3 s after the impact peak (--before/--after)?
                The alert time is reported relative to the peak; negative = it fired during the
                first moments of the fall, before the peak.
  * daily life: how many alerts per hour of ordinary activity (a run of consecutive flagged windows
                counts as ONE alert)?
  * --persist 2 requires two consecutive flagged windows before an alert (fewer false alerts,
                slightly slower).

Usage (from the sehat_pipeline folder; motion_train.py and mobiact_to_npz.py must be there too):
    python motion_eval_continuous.py --root "C:\\...\\MobiAct_Dataset" --run out_mobiact_tcn_grav

--run is a folder written by motion_train.py (model.keras, norm.json, metrics.json, test_predictions.csv).
It must be a run trained with the same data file, so that the test volunteers are known.
"""
import argparse, collections, csv, json, os
import numpy as np
import tensorflow as tf

import mobiact_to_npz as mb
from motion_train import gravity_align

FS, WIN = mb.FS, mb.WIN


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="folder with the MobiAct raw .txt files")
    ap.add_argument("--run", required=True, help="output folder of motion_train.py")
    ap.add_argument("--stride", type=int, default=25, help="samples between windows (25 = 0.5 s)")
    ap.add_argument("--persist", type=int, nargs="*", default=[1, 2],
                    help="consecutive flagged windows required for an alert (several values allowed)")
    ap.add_argument("--before", type=float, default=1.0, help="seconds before the impact peak in which an alert still counts as detecting the fall")
    ap.add_argument("--after", type=float, default=3.0, help="seconds after the impact peak in which an alert counts")
    ap.add_argument("--channels", default="", help="same --channels value that was used for training, if any")
    a = ap.parse_args()

    model = tf.keras.models.load_model(os.path.join(a.run, "model.keras"))
    norm = json.load(open(os.path.join(a.run, "norm.json")))
    thr = json.load(open(os.path.join(a.run, "metrics.json")))["test"]["threshold"]
    mean = np.array(norm["mean"], dtype="float32").reshape(1, 1, -1)
    std = np.array(norm["std"], dtype="float32").reshape(1, 1, -1)
    orient = norm.get("orient", "raw")
    with open(os.path.join(a.run, "test_predictions.csv")) as fh:
        test_subjects = {int(r["subject"].lstrip("S")) for r in csv.DictReader(fh)}
    print(f"run {a.run}: threshold {thr:.2f}, orient={orient}, {len(test_subjects)} test volunteers")

    if not os.path.isdir(a.root):
        raise SystemExit(f"The folder does not exist: {a.root}\nUse the same --root path that you used for mobiact_to_npz.py "
                         "(Shift + right-click the folder, 'Copy as path').")
    trials = collections.defaultdict(dict)
    n_files = n_match = 0
    seen_subjects = set()
    for dp, _, files in os.walk(a.root):
        for f in files:
            n_files += 1
            m = mb.PAT.match(f)
            if m:
                n_match += 1
                seen_subjects.add(int(m.group(3)))
                if int(m.group(3)) in test_subjects:
                    trials[(m.group(1).upper(), int(m.group(3)), int(m.group(4)))][m.group(2).lower()] = os.path.join(dp, f)
    if n_files == 0:
        raise SystemExit(f"The folder exists but contains no files: {a.root}\nUse the same --root path that you used for mobiact_to_npz.py.")
    if n_match == 0:
        raise SystemExit(f"{n_files} files found in {a.root}, but none are named like WAL_acc_5_1.txt. "
                         "Point --root at the folder with the raw .txt files (the same one used for mobiact_to_npz.py).")
    if not trials:
        raise SystemExit(f"Found {n_match} raw files for {len(seen_subjects)} volunteers, but none for the test volunteers "
                         f"{sorted(test_subjects)}. Is --run from a different data file than this dataset?")
    trials = {k: v for k, v in trials.items() if "acc" in v and "gyro" in v and (k[0] in mb.FALLS or k[0] in mb.ADLS)}
    print(f"{len(trials)} complete trials of the test volunteers\n")

    fall_res = {k: [] for k in a.persist}                # per rule: list of (detected, alert time relative to impact)
    adl = {k: collections.defaultdict(lambda: [0.0, 0, 0, 0]) for k in a.persist}   # hours, alerts, trials, trials with an alert
    side = {k: 0 for k in a.persist}                      # alerts away from the fall in fall trials
    for (lab, sub, tr), p in sorted(trials.items()):
        X = mb.load_trial(p["acc"], p["gyro"])
        if X is None or len(X) < WIN:
            continue
        if a.channels:
            X = X[:, [int(i) for i in a.channels.split(",")]]
        starts = np.arange(0, len(X) - WIN + 1, a.stride)
        W = np.stack([X[s:s + WIN] for s in starts]).astype("float32")
        if orient == "gravity":
            W = gravity_align(W)
        prob = model.predict((W - mean) / std, verbose=0).ravel()
        flags = prob >= thr
        ends = (starts + WIN) / FS                       # an alert can fire when its window has ended
        hours = len(X) / FS / 3600
        ip = None
        if lab in mb.FALLS:
            sm = np.convolve(np.linalg.norm(X[:, :3], axis=1), np.ones(5) / 5, mode="same")
            ip = int(np.argmax(sm))
        for k in a.persist:
            sat = satisfied(flags, k)
            runs = runs_of(sat)
            if ip is None:
                row = adl[k][lab]
                row[0] += hours; row[1] += len(runs); row[2] += 1; row[3] += int(len(runs) > 0)
                continue
            t_ip = ip / FS
            zone = sat & (ends >= t_ip - a.before) & (ends <= t_ip + a.after)
            if zone.any():
                fall_res[k].append((True, float(ends[int(np.argmax(zone))] - t_ip)))
            else:
                fall_res[k].append((False, None))
            related = [r for r in runs if zone[r[0]:r[1] + 1].any()]
            side[k] += len(runs) - len(related)

    for k in a.persist:
        print("=" * 78)
        print(f"ALERT RULE: {k} consecutive flagged window(s)  (stride {a.stride / FS:.1f} s, window 2.0 s)")
        res = fall_res[k]
        det = [r for r in res if r[0]]
        lat = [r[1] for r in det]
        if det:
            print(f"falls: {len(det)} of {len(res)} fall trials detected ({len(det) / len(res):.1%}); "
                  f"alert time relative to the impact peak: median {np.median(lat):+.1f} s, "
                  f"range {np.min(lat):+.1f} to {np.max(lat):+.1f} s (negative = fired before the peak)")
        else:
            print(f"falls: 0 of {len(res)} detected")
        print(f"(a fall counts as detected if an alert fires between {a.before:.1f} s before and {a.after:.1f} s after the impact peak)")
        print(f"alerts raised in the quiet parts of fall trials (away from the impact): {side[k]}")
        tot_h = sum(v[0] for v in adl[k].values())
        tot_a = sum(v[1] for v in adl[k].values())
        print(f"daily activities: {tot_h:.2f} hours analysed, {tot_a} alerts  ->  {tot_a / max(tot_h, 1e-9):.1f} alerts per hour\n")
        print(f"  {'activity':8s} {'trials':>6s} {'hours':>7s} {'alerts':>7s} {'alerts/hour':>12s} {'trials with an alert':>22s}")
        for lab, (h, n, nt, nta) in sorted(adl[k].items(), key=lambda kv: -kv[1][1] / max(kv[1][0], 1e-9)):
            print(f"  {lab:8s} {nt:6d} {h:7.2f} {n:7d} {n / max(h, 1e-9):12.1f} {nta:12d} ({nta / max(nt, 1):.0%})")
        print()


def satisfied(flags, k):
    """True where the last k windows are all flagged."""
    f = np.asarray(flags, bool)
    if k == 1:
        return f
    out = np.zeros(len(f), bool)
    for i in range(k - 1, len(f)):
        out[i] = f[i - k + 1:i + 1].all()
    return out


def runs_of(sat):
    """(first, last) index of every run of consecutive True values; each run is ONE alert."""
    runs, i = [], 0
    while i < len(sat):
        if sat[i]:
            j = i
            while j + 1 < len(sat) and sat[j + 1]:
                j += 1
            runs.append((i, j))
            i = j + 1
        else:
            i += 1
    return runs


if __name__ == "__main__":
    main()