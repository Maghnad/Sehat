"""
Export the audio model (YAMNet + the trained classifier head) as ONE TensorFlow Lite file for the
Android app.

The exported model takes 2 s of raw mono 16 kHz audio (32000 floats in [-1, 1]) and returns the
probabilities of the classes (background, scream, cry, glass_break). YAMNet's own spectrogram and
embedding steps run INSIDE the model, so the app needs no mel-spectrogram code and cannot get it wrong.
P(distress) = 1 - P(background).

Reads a folder written by audio_yamnet.py (head.keras, head_norm.npz, probs_val.npz) and writes
into the app's assets folder:
    audio_model.tflite   waveform [32000] -> probabilities [1, classes]
    audio_config.json    sample rate, chunk length, class names, suggested thresholds
    audio_golden.json    example audio chunks (16-bit PCM, exactly what the phone's microphone API
                         delivers) with the probabilities expected from Python; the app's self-test
                         must reproduce them

Usage (from the sehat_pipeline folder; needs internet the first time unless --yamnet is a local folder):
    python export_audio_for_android.py --run exp_Y --audio-root data\\audio ^
        --assets "C:\\...\\sehat_fall_test\\app\\src\\main\\assets"
"""
import argparse, base64, glob, json, os
import numpy as np
import tensorflow as tf
from sklearn.metrics import roc_curve

from audio_yamnet import DEFAULT_HANDLE, load_yamnet

SR, N = 16000, 32000


class EndToEnd(tf.Module):
    """waveform -> YAMNet embeddings -> mean+max pooling -> standardise -> classifier head."""

    def __init__(self, yamnet, head, mean, std):
        super().__init__()
        self.yamnet, self.head = yamnet, head
        self.mean = tf.constant(mean, tf.float32)
        self.std = tf.constant(std, tf.float32)

    @tf.function(input_signature=[tf.TensorSpec([N], tf.float32, name="waveform")])
    def serve(self, wav):
        _, emb, _ = self.yamnet(wav)
        f = tf.concat([tf.reduce_mean(emb, 0), tf.reduce_max(emb, 0)], 0)
        f = (f - self.mean) / self.std
        return self.head(f[None], training=False)


def pick_chunk(path):
    """The loudest 2 s of a clip (zero-padded if shorter), as 16-bit PCM and the matching floats."""
    import librosa
    w, _ = librosa.load(path, sr=SR, mono=True)
    if len(w) < N:
        w = np.pad(w, (0, N - len(w)))
    hop = SR // 2
    starts = list(range(0, len(w) - N + 1, hop)) or [0]
    best = max(starts, key=lambda s: float(np.sqrt(np.mean(w[s:s + N] ** 2))))
    return w[best:best + N]


def to_pcm(w):
    pcm = np.clip(np.round(w * 32768.0), -32768, 32767).astype("<i2")
    return pcm, (pcm.astype("float32") / 32768.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True, help="output folder of audio_yamnet.py")
    ap.add_argument("--assets", required=True, help="the app's src/main/assets folder")
    ap.add_argument("--yamnet", default=DEFAULT_HANDLE, help="TF Hub handle or a local YAMNet folder")
    ap.add_argument("--audio-root", default="", help="your data\\audio folder, to take real example clips from")
    ap.add_argument("--per-class", type=int, default=2, help="example clips per class")
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    os.makedirs(a.assets, exist_ok=True)
    rng = np.random.default_rng(a.seed)

    head = tf.keras.models.load_model(os.path.join(a.run, "head.keras"))
    nrm = np.load(os.path.join(a.run, "head_norm.npz"), allow_pickle=True)
    classes = [str(c) for c in nrm["classes"]]
    model = EndToEnd(load_yamnet(a.yamnet), head, nrm["mean"], nrm["std"])
    ref = lambda wav: model.serve(tf.constant(wav, tf.float32)).numpy().ravel()

    # ---- convert
    conc = model.serve.get_concrete_function()
    flex = False
    try:
        conv = tf.lite.TFLiteConverter.from_concrete_functions([conc], model)
        conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
        tfl = conv.convert()
    except Exception as e:
        print("Conversion with built-in ops only FAILED:", str(e).splitlines()[0][:200])
        print("Retrying with TensorFlow ops allowed (the app would then need the select-tf-ops runtime).")
        conv = tf.lite.TFLiteConverter.from_concrete_functions([conc], model)
        conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS, tf.lite.OpsSet.SELECT_TF_OPS]
        tfl = conv.convert()
        flex = True
    open(os.path.join(a.assets, "audio_model.tflite"), "wb").write(tfl)
    interp = tf.lite.Interpreter(model_content=tfl)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    print(f"TFLite input {inp['shape'].tolist()} {inp['dtype'].__name__} | output {out['shape'].tolist()} | "
          f"size {len(tfl) / 1024 / 1024:.1f} MB | needs TensorFlow ops: {flex}")

    def tfl_probs(wav):
        interp.set_tensor(inp["index"], wav.astype("float32"))
        interp.invoke()
        return interp.get_tensor(out["index"]).ravel()

    # ---- golden examples: real 2 s chunks, stored as 16-bit PCM like the phone's microphone delivers
    chunks, names = [], []
    if a.audio_root:
        for c in classes:
            files = sorted(glob.glob(os.path.join(a.audio_root, c, "*.wav")))
            for f in rng.choice(files, min(a.per_class, len(files)), replace=False) if files else []:
                chunks.append(pick_chunk(f))
                names.append(c)
    if not chunks:
        print("No --audio-root clips: using synthetic test sounds instead.")
        t = np.arange(N) / SR
        chunks = [0.3 * np.sin(2 * np.pi * 440 * t), 0.2 * rng.normal(size=N), 0.5 * np.sign(np.sin(2 * np.pi * 3 * t)) * rng.normal(size=N)]
        names = ["tone", "noise", "bursts"]
    golden, worst = [], 0.0
    for name, w in zip(names, chunks):
        pcm, wf = to_pcm(w)
        p_ref, p_tfl = ref(wf), tfl_probs(wf)
        worst = max(worst, float(np.abs(p_ref - p_tfl).max()))
        golden.append({"source_class": name, "pcm16_base64": base64.b64encode(pcm.tobytes()).decode(),
                       "expected_probabilities": [float(v) for v in p_ref]})
    print(f"Python model vs TFLite on {len(golden)} chunks: largest probability difference {worst:.2e}")
    for g in golden:
        print(f"  {g['source_class']:12s} -> " + ", ".join(f"{c} {p:.2f}" for c, p in zip(classes, g['expected_probabilities'])))
    json.dump({"classes": classes, "examples": golden}, open(os.path.join(a.assets, "audio_golden.json"), "w"))

    # ---- suggested thresholds on P(distress) = 1 - P(background), chosen on the VALIDATION set
    thr = {}
    pv = os.path.join(a.run, "probs_val.npz")
    if os.path.exists(pv):
        d = np.load(pv)
        fpr, tpr, th = roc_curve(d["y"], d["p"])
        for fa in (0.01, 0.02, 0.05):
            i = int(np.where(fpr <= fa)[0][-1])
            thr[f"fa_{int(fa * 100)}pct"] = {"threshold": float(th[i]), "validation_sensitivity": float(tpr[i])}
    cfg = {"sample_rate": SR, "chunk_samples": N, "input": "float32 waveform [32000], range -1..1 (int16 PCM / 32768)",
           "output": "probabilities [1, classes]", "classes": classes, "background_class": "background",
           "p_distress": "1 - probability of 'background'", "suggested_thresholds": thr,
           "needs_select_tf_ops": flex}
    json.dump(cfg, open(os.path.join(a.assets, "audio_config.json"), "w"), indent=2)
    print("\nsuggested P(distress) thresholds (chosen on validation data):",
          {k: round(v["threshold"], 3) for k, v in thr.items()})
    print(f"wrote audio_model.tflite, audio_config.json, audio_golden.json to {a.assets}")


if __name__ == "__main__":
    main()
