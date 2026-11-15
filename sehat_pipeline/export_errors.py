"""
Export the most CONFIDENT mistakes as short wav files, so you can listen to them.

Very confident mistakes are the most informative: they are either a model weakness
or a wrongly labelled / ambiguous clip (for example a "crying" clip that is mostly silence
or a "laugh" that sounds like a sob). Listening to about 20 of each kind tells you which.

Usage (from the sehat_pipeline folder):
    python export_errors.py --pred exp_Y\\test_predictions.csv --root "C:\\...\\sehat_project\\sehat_pipeline\\data\\audio"
Output: folder  error_clips\\<true>_as_<predicted>\\  with files named  <confidence>_<clip>_<start>s.wav
"""
import argparse, os
import librosa
import pandas as pd
import soundfile as sf

SR, N = 16000, 32000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pred", required=True)
    ap.add_argument("--root", required=True, help="the data\\audio folder")
    ap.add_argument("--out", default="error_clips")
    ap.add_argument("--per-type", type=int, default=15, help="clips exported per kind of mistake")
    ap.add_argument("--min-count", type=int, default=10, help="only mistake types with at least this many chunks")
    a = ap.parse_args()

    df = pd.read_csv(a.pred)
    wrong = df[df["true"] != df["pred"]].copy()
    wrong["conf"] = wrong.apply(lambda r: r[f"p_{r['pred']}"], axis=1)
    done = 0
    for (t, p), grp in wrong.groupby(["true", "pred"]):
        if len(grp) < a.min_count:
            continue
        dst = os.path.join(a.out, f"{t}_as_{p}")
        os.makedirs(dst, exist_ok=True)
        for _, r in grp.sort_values("conf", ascending=False).head(a.per_type).iterrows():
            base, start = r["clip"].split("@")
            src = os.path.join(a.root, t, base)
            if not os.path.exists(src):
                print("missing", src)
                continue
            w, _ = librosa.load(src, sr=SR, mono=True)
            seg = w[int(start):int(start) + N]
            sf.write(os.path.join(dst, f"{r['conf']:.2f}_{os.path.splitext(base)[0]}_{int(start) / SR:.1f}s.wav"), seg, SR)
            done += 1
        print(f"{t} predicted as {p}: exported {min(len(grp), a.per_type)} of {len(grp)} chunks")
    print(f"\n{done} clips written to {a.out}\\  (open the folders and listen)")


if __name__ == "__main__":
    main()
