"""
Add the DCASE 2017 "TUT Rare sound events" development SOURCE data to the audio_train.py layout.

Inputs (from the Zenodo record 401395):
    --events-dir : folder that contains  babycry/  glassbreak/  gunshot/
                   (...\\data\\source_data\\events)
    --bgs-dir    : folder with the background recordings, 15 everyday scenes
                   (...\\data\\source_data\\bgs\\audio)

Mapping:
    babycry     -> cry
    glassbreak  -> glass_break
    gunshot     -> ignored (not one of our classes)
    backgrounds -> background (a short excerpt from each recording)

Each event file is trimmed of leading/trailing silence. Files are written to
    <out>/<class>/dc<name>_<original name>.wav
audio_train.py treats the text before the first "_" as the SOURCE ID, so everything cut from
one recording stays in the same train/validation/test split.

Usage (PowerShell, one line):
    python prep_dcase2017.py --events-dir "<...>\\source_data\\events" --bgs-dir "<...>\\source_data\\bgs\\audio" --out data\\audio

License of the data: non-commercial. Cite: Mesaros et al., "DCASE 2017 challenge setup: tasks,
datasets and baseline system", DCASE2017 Workshop, 2017.
"""
import argparse, os, re
import librosa
import numpy as np
import soundfile as sf

EVENT_MAP = {"babycry": "cry", "glassbreak": "glass_break"}


def wavs_in(folder):
    out = []
    for root, _, files in os.walk(folder):
        out += [os.path.join(root, f) for f in files if f.lower().endswith(".wav")]
    return sorted(out)


def sid(text):
    return re.sub(r"[^A-Za-z0-9]", "", text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--events-dir", required=True)
    ap.add_argument("--bgs-dir", required=True)
    ap.add_argument("--out", default="data/audio")
    ap.add_argument("--top-db", type=float, default=35, help="silence threshold for trimming events (dB)")
    ap.add_argument("--min-event-s", type=float, default=0.25, help="skip events shorter than this after trimming")
    ap.add_argument("--bg-seconds", type=float, default=8.0, help="seconds taken from the middle of each background file")
    ap.add_argument("--max-bg-files", type=int, default=0, help="limit the number of background files (0 = all)")
    a = ap.parse_args()

    stats = {}
    skipped = 0

    # ---- isolated events ----
    for folder, cls in EVENT_MAP.items():
        d = os.path.join(a.events_dir, folder)
        files = wavs_in(d)
        if not files:
            print(f"WARNING: no wav files found in {d}")
        os.makedirs(os.path.join(a.out, cls), exist_ok=True)
        for f in files:
            w, sr = librosa.load(f, sr=None, mono=True)
            if np.max(np.abs(w)) < 1e-4:  # completely silent file
                skipped += 1
                continue
            t, _ = librosa.effects.trim(w, top_db=a.top_db)
            if len(t) < a.min_event_s * sr:
                skipped += 1
                continue
            stem = os.path.splitext(os.path.basename(f))[0]
            name = f"dc{folder}{sid(stem)}_{stem}.wav"
            sf.write(os.path.join(a.out, cls, name), t, sr)
            stats[cls] = stats.get(cls, 0) + 1

    # ---- backgrounds ----
    bgs = wavs_in(a.bgs_dir)
    if not bgs:
        print(f"WARNING: no wav files found in {a.bgs_dir}")
    if a.max_bg_files:
        bgs = bgs[: a.max_bg_files]
    os.makedirs(os.path.join(a.out, "background"), exist_ok=True)
    for f in bgs:
        w, sr = librosa.load(f, sr=None, mono=True)
        n = int(a.bg_seconds * sr)
        start = max(0, (len(w) - n) // 2)
        seg = w[start:start + n]
        stem = os.path.splitext(os.path.basename(f))[0]
        sf.write(os.path.join(a.out, "background", f"dcbg{sid(stem)}_{stem}.wav"), seg, sr)
        stats["background"] = stats.get("background", 0) + 1

    print("DCASE 2017 clips copied per class:", stats)
    if skipped:
        print(f"skipped {skipped} event files shorter than {a.min_event_s}s after trimming")
    print("Next: python audio_train.py --root", a.out)


if __name__ == "__main__":
    main()
