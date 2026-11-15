"""
Error analysis for audio_train.py (and audio_cv.py) predictions.

Reads  test_predictions.csv  (or oof_predictions.csv) and shows, for every kind of mistake,
which dataset the clips came from and, for Nonspeech7k, their ORIGINAL sound type
(breath, cough, laugh, ...). It answers questions such as
"which background sounds get mistaken for crying?"

Usage:
    python analyze_errors.py --pred out_audio2\\test_predictions.csv
    python analyze_errors.py --pred out_audio2\\test_predictions.csv ^
        --nz-csv "C:\\...\\nonspeech7k\\metadata of train set .csv" "C:\\...\\nonspeech7k\\metadata of test set.csv"

The optional --nz-csv files are the Nonspeech7k metadata, used to look up each clip's original class.
"""
import argparse
import pandas as pd


def norm(c):
    return " ".join(str(c).lower().replace("_", " ").split())


def origin(clip):
    n = clip.split("@")[0]
    if n.startswith("nz"):
        return "Nonspeech7k"
    if n.startswith("es"):
        return "ESC-50"
    if n.startswith("dcbg"):
        return "DCASE background"
    return "other"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pred", required=True)
    ap.add_argument("--nz-csv", nargs="*", default=[])
    ap.add_argument("--esc50-csv", default="", help="ESC-50 meta/esc50.csv, to name the ESC-50 sound classes")
    ap.add_argument("--top", type=int, default=8)
    ap.add_argument("--min-chunks", type=int, default=20, help="smallest sound type shown in the false-alarm table")
    a = ap.parse_args()

    df = pd.read_csv(a.pred)
    df["origin"] = df["clip"].map(origin)
    df["file"] = df["clip"].str.split("@").str[0]

    # Nonspeech7k original class lookup
    nz_class = {}
    for p in a.nz_csv:
        m = pd.read_csv(p)
        fc = next(c for c in m.columns if "filename" in norm(c))
        cc = next(c for c in m.columns if "classname" in norm(c))
        nz_class.update(dict(zip(m[fc].astype(str).str.strip(), m[cc].astype(str).str.strip())))
    if nz_class:
        def orig_name(f):
            if not f.startswith("nz"):
                return ""
            return nz_class.get(f.split("_", 1)[1] if "_" in f else f, "?")
        df["nz_class"] = df["file"].map(orig_name)

    esc_class = {}
    if a.esc50_csv:
        em = pd.read_csv(a.esc50_csv)
        esc_class = dict(zip(em["filename"].astype(str), em["category"].astype(str)))

    def sound_type(row):
        f = row["file"]
        if row["origin"] == "Nonspeech7k" and nz_class:
            return "Nonspeech7k: " + nz_class.get(f.split("_", 1)[1] if "_" in f else f, "?")
        if row["origin"] == "ESC-50" and esc_class:
            return "ESC-50: " + esc_class.get(f.split("_", 1)[1] if "_" in f else f, "?")
        return row["origin"]
    df["sound"] = df.apply(sound_type, axis=1)

    wrong = df[df["true"] != df["pred"]]
    print(f"{len(df)} test chunks, {len(wrong)} wrong ({len(wrong) / len(df):.1%})\n")

    print("Most common mistakes (actual -> predicted):")
    print(wrong.groupby(["true", "pred"]).size().sort_values(ascending=False).head(10).to_string(), "\n")

    for (t, p), grp in wrong.groupby(["true", "pred"]):
        if len(grp) < 10:
            continue
        print(f"--- actual '{t}' predicted as '{p}' ({len(grp)} chunks): by dataset")
        print(grp["origin"].value_counts().to_string())
        if nz_class:
            nzg = grp[grp["origin"] == "Nonspeech7k"]
            if len(nzg):
                tot = df[(df["true"] == t) & (df["origin"] == "Nonspeech7k")]["nz_class"].value_counts()
                err = nzg["nz_class"].value_counts()
                print("    Nonspeech7k original type: errors / total chunks of that type in test")
                for k, v in err.head(a.top).items():
                    print(f"      {k:10s} {v:4d} / {int(tot.get(k, 0)):4d}")
        if esc_class:
            eg = grp[grp["origin"] == "ESC-50"]
            if len(eg):
                tot = df[(df["true"] == t) & (df["origin"] == "ESC-50")]["sound"].value_counts()
                print("    ESC-50 sound class: errors / total chunks of that class in test")
                for k, v in eg["sound"].value_counts().head(a.top).items():
                    print(f"      {k.replace('ESC-50: ', ''):16s} {v:4d} / {int(tot.get(k, 0)):4d}")
        print()

    bg = df[df["true"] == "background"].copy()
    bg["flagged"] = bg["pred"] != "background"
    t = bg.groupby("sound")["flagged"].agg(["sum", "size"])
    t["false-alarm rate"] = t["sum"] / t["size"]
    t = t[t["size"] >= a.min_chunks].sort_values("false-alarm rate", ascending=False)
    print("False-alarm rate by BACKGROUND sound type (predicted as any distress class; types with enough test chunks):")
    print(t.rename(columns={"sum": "flagged", "size": "chunks"}).head(20).round(3).to_string(), "\n")

    print("Per-dataset accuracy on the test chunks:")
    print((df.assign(ok=df["true"] == df["pred"]).groupby("origin")["ok"].agg(["mean", "size"])
           .rename(columns={"mean": "accuracy", "size": "chunks"})).round(3).to_string())


if __name__ == "__main__":
    main()
