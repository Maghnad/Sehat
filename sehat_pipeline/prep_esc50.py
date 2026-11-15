"""
Add ESC-50 to the audio_train.py folder layout.

ESC-50 (2,000 five-second clips, 50 classes, 40 clips per class).
Expected folder (from the GitHub zip):
    <esc50-dir>/audio/*.wav
    <esc50-dir>/meta/esc50.csv     columns: filename, fold, target, category, esc10, src_file, take

Mapping used here:
    crying_baby    -> cry
    glass_breaking -> glass_break
    every other class -> background
There is no suitable ESC-50 class for "impact" (door knocks and similar are not distress sounds),
so impact is left out here. Record it yourself or add it from another source.

Files are copied to  <out>/<class>/es<src_file>_<original name>.wav
audio_train.py treats the text before the first "_" as the SOURCE ID, so clips cut from the
same Freesound recording (same src_file) always stay in the same split (no leakage).

Usage:
    python prep_esc50.py --esc50-dir "D:/data/ESC-50-master" --out data/audio
Run it after (or before) prep_nonspeech7k.py with the same --out folder; they add to each other.
"""
import argparse, os, shutil
import pandas as pd

CLASS_MAP = {"crying_baby": "cry", "glass_breaking": "glass_break"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--esc50-dir", required=True, help="folder containing audio/ and meta/")
    ap.add_argument("--out", default="data/audio")
    a = ap.parse_args()

    csv_path = os.path.join(a.esc50_dir, "meta", "esc50.csv")
    audio_dir = os.path.join(a.esc50_dir, "audio")
    if not (os.path.exists(csv_path) and os.path.isdir(audio_dir)):
        raise SystemExit(f"Expected {csv_path} and {audio_dir}. Point --esc50-dir at the unzipped ESC-50 folder.")

    df = pd.read_csv(csv_path)
    stats, missing = {}, 0
    for _, r in df.iterrows():
        src = os.path.join(audio_dir, r["filename"])
        if not os.path.exists(src):
            missing += 1
            continue
        cls = CLASS_MAP.get(str(r["category"]).strip(), "background")
        if "src_file" in df.columns:
            sid = r["src_file"]
        else:  # filename pattern: fold-srcID-take-target.wav
            sid = str(r["filename"]).split("-")[1]
        dst_dir = os.path.join(a.out, cls)
        os.makedirs(dst_dir, exist_ok=True)
        shutil.copy2(src, os.path.join(dst_dir, f"es{sid}_{r['filename']}"))
        stats[cls] = stats.get(cls, 0) + 1

    if missing:
        print(f"WARNING: {missing} files listed in esc50.csv were not found in {audio_dir}")
    print("ESC-50 clips copied per class:", stats)
    print("Next: python audio_train.py --root", a.out)


if __name__ == "__main__":
    main()
