"""
Prepare Nonspeech7k for audio_train.py.

Nonspeech7k classes: breath, cough, crying, laugh, screaming, sneeze, yawn.
Mapping used here:
    screaming -> scream
    crying    -> cry
    everything else (breath, cough, laugh, sneeze, yawn) -> background
        (human non-distress sounds: useful "hard negatives" for scream/cry)

Files are copied to  <out>/<class>/nz<FileID>_<original name>.wav
audio_train.py treats the text before the first "_" as the SOURCE ID, so all clips
cut from the same original recording (same File ID, including augmented copies)
end up in the same train/validation/test split. This prevents leakage.

Usage (Windows paths are fine, use quotes if they contain spaces):
    python prep_nonspeech7k.py --train-dir "D:/data/train" --test-dir "D:/data/test" ^
        --train-csv "D:/data/metadata of train set .csv" --test-csv "D:/data/metadata of test set.csv" ^
        --out data/audio

By default only the original (non-augmented) clips are used, because audio_train.py
does its own augmentation. Add --include-augmented to also use the dataset's augmented copies.
"""
import argparse, os, shutil
import pandas as pd

MAP = {"scream": "scream", "cry": "cry"}


def norm(c):
    """lower-case, treat underscores as spaces, collapse repeated spaces"""
    return " ".join(str(c).lower().replace("_", " ").split())


def find_col(df, *must, exclude=()):
    for c in df.columns:
        n = norm(c)
        if all(m in n for m in must) and not any(e in n for e in exclude):
            return c
    raise SystemExit(f"Could not find a column containing {must} in {list(df.columns)}")


def map_class(name):
    n = str(name).lower()
    if "scream" in n:
        return "scream"
    if "cry" in n:
        return "cry"
    return "background"


def index_wavs(folder):
    idx = {}
    for root, _, files in os.walk(folder):
        for f in files:
            if f.lower().endswith(".wav"):
                idx[f] = os.path.join(root, f)
    return idx


def process(csv_path, audio_dir, out, include_aug, stats):
    df = pd.read_csv(csv_path)
    c_file = find_col(df, "filename")
    c_id = find_col(df, "file id")
    c_cls = find_col(df, "classname")
    # original vs augmented: prefer the "Augmentation type" column ("Orignal"/"Original"),
    # otherwise fall back to the numeric "Augment Id" column (0 = original)
    c_type = next((c for c in df.columns if "augment" in norm(c) and "type" in norm(c)), None)
    c_aug = next((c for c in df.columns if "augment" in norm(c) and "type" not in norm(c)), None)
    print("columns used:", c_file, "|", c_id, "|", c_cls, "|", c_type, "|", c_aug)

    def is_original(row):
        if c_type is not None:
            return norm(row[c_type]).startswith("or")
        if c_aug is not None:
            return int(row[c_aug]) == 0
        return True

    wavs = index_wavs(audio_dir)
    print(f"{os.path.basename(csv_path)}: {len(df)} rows, {len(wavs)} wav files found in {audio_dir}")
    missing = 0
    for _, r in df.iterrows():
        if not include_aug and not is_original(r):
            continue
        src = wavs.get(str(r[c_file]).strip())
        if src is None:
            missing += 1
            continue
        cls = map_class(r[c_cls])
        dst_dir = os.path.join(out, cls)
        os.makedirs(dst_dir, exist_ok=True)
        shutil.copy2(src, os.path.join(dst_dir, f"nz{r[c_id]}_{r[c_file]}"))
        stats[cls] = stats.get(cls, 0) + 1
    if missing:
        print(f"  WARNING: {missing} files listed in the CSV were not found in {audio_dir}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train-dir", required=True)
    ap.add_argument("--test-dir", required=True)
    ap.add_argument("--train-csv", required=True)
    ap.add_argument("--test-csv", required=True)
    ap.add_argument("--out", default="data/audio")
    ap.add_argument("--include-augmented", action="store_true")
    a = ap.parse_args()

    stats = {}
    process(a.train_csv, a.train_dir, a.out, a.include_augmented, stats)
    process(a.test_csv, a.test_dir, a.out, a.include_augmented, stats)
    print("\nclips copied per class:", stats)
    print("Next: python audio_train.py --root", a.out)


if __name__ == "__main__":
    main()
