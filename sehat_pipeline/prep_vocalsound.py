"""
Add VocalSound (laughter, cough, sneeze, sigh, sniff, throat clearing) to the audio_train.py
folder layout as BACKGROUND hard negatives: human sounds that are NOT distress.

Input:  the audio_16k folder of VocalSound, with files like  f0003_0_cough.wav
        (f = female / m = male, 0003 = speaker id, 0 = collection round, cough = sound type)
Output: <out>/background/vs<speaker>_<original name>.wav
        audio_train.py / audio_yamnet.py treat the text before the first "_" as the SOURCE ID, so
        all clips of one speaker stay in the same train/validation/test split.

Because VocalSound is large (21,024 files), a random subset is used:
    --cap-main   files per type for laughter, cough, sneeze (the sounds the model confuses with distress)
    --cap-other  files per type for sigh, sniff, throat clearing

Usage (PowerShell, one line):
    python prep_vocalsound.py --audio-dir "C:\\...\\vocalsound\\data\\audio_16k" --out "C:\\...\\sehat_project\\sehat_pipeline\\data\\audio"

To undo: delete the files  background\\vs*  from the output folder.
License: CC BY-SA. Cite: Y. Gong, J. Yu, J. Glass, "VocalSound: A Dataset for Improving Human Vocal
Sounds Recognition," ICASSP 2022.
"""
import argparse, glob, os, random, shutil

MAIN = ("laughter", "cough", "sneeze")
OTHER = ("sigh", "sniff", "throatclearing")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--audio-dir", required=True, help="the audio_16k folder")
    ap.add_argument("--out", default="data/audio")
    ap.add_argument("--cap-main", type=int, default=1000)
    ap.add_argument("--cap-other", type=int, default=300)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()

    files = sorted(glob.glob(os.path.join(a.audio_dir, "*.wav")))
    if not files:
        raise SystemExit(f"No wav files found in {a.audio_dir}. Point --audio-dir at the audio_16k folder.")
    by_type = {}
    for f in files:
        stem = os.path.splitext(os.path.basename(f))[0]          # f0003_0_cough
        kind = stem.split("_", 2)[-1].lower()
        by_type.setdefault(kind, []).append(f)
    print("found:", {k: len(v) for k, v in sorted(by_type.items())})

    rng = random.Random(a.seed)
    dst = os.path.join(a.out, "background")
    os.makedirs(dst, exist_ok=True)
    copied = {}
    for kind, items in by_type.items():
        cap = a.cap_main if kind in MAIN else a.cap_other
        pick = items if len(items) <= cap else rng.sample(items, cap)
        for f in pick:
            name = os.path.basename(f)
            spk = name.split("_")[0]
            shutil.copy2(f, os.path.join(dst, f"vs{spk}_{name}"))
        copied[kind] = len(pick)
    print("copied to", dst, ":", copied, "| total", sum(copied.values()))
    print("Next: rerun the experiments (see the instructions) - the feature cache refreshes automatically.")


if __name__ == "__main__":
    main()
