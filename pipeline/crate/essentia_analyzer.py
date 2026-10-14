"""Audio analysis with Essentia + TensorFlow models. Imported only when analysis actually runs.

Install with ``uv sync --extra audio``. Models are CC BY-NC-SA 4.0: personal, non-commercial use only.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np

from . import models
from .analyse import AudioFeatures

# head -> the class we want the probability of. Class order differs per head (e.g. mood_happy is
# [happy, non_happy] but mood_sad is [non_sad, sad]), so the index is looked up in the head's JSON.
BINARY_HEADS = {
    "mood_aggressive": "aggressive",
    "mood_party": "party",
    "mood_relaxed": "relaxed",
    "mood_happy": "happy",
    "mood_sad": "sad",
    "voice_instrumental": "voice",
    "danceability": "danceable",
}


class EssentiaAnalyzer:
    """Loads every model once; ``analyse(path)`` runs them on one preview file."""

    def __init__(self, model_dir: Path | None = None):
        import essentia

        essentia.log.warningActive = False
        essentia.log.infoActive = False
        import essentia.standard as es

        self.es = es
        d = models.ensure(model_dir or models.DEFAULT_DIR)
        self.style_labels = models.labels(d, "genre_discogs400-discogs-effnet-1.json")
        self.mood_labels = models.labels(d, "mtg_jamendo_moodtheme-discogs-effnet-1.json")
        self.effnet = es.TensorflowPredictEffnetDiscogs(
            graphFilename=str(d / "discogs-effnet-bs64-1.pb"), output="PartitionedCall:1"
        )
        self.genre = es.TensorflowPredict2D(
            graphFilename=str(d / "genre_discogs400-discogs-effnet-1.pb"),
            input="serving_default_model_Placeholder",
            output="PartitionedCall:0",
        )
        self.jamendo = es.TensorflowPredict2D(
            graphFilename=str(d / "mtg_jamendo_moodtheme-discogs-effnet-1.pb"), output="model/Sigmoid"
        )
        self.binary = {
            name: es.TensorflowPredict2D(graphFilename=str(d / f"{name}-discogs-effnet-1.pb"), output="model/Softmax")
            for name in BINARY_HEADS
        }
        self.positive = {
            name: models.labels(d, f"{name}-discogs-effnet-1.json").index(label) for name, label in BINARY_HEADS.items()
        }
        self.musicnn = es.TensorflowPredictMusiCNN(graphFilename=str(d / "msd-musicnn-1.pb"), output="model/dense/BiasAdd")
        self.deam = es.TensorflowPredict2D(graphFilename=str(d / "deam-msd-musicnn-2.pb"), output="model/Identity")
        self.tempocnn = es.TempoCNN(graphFilename=str(d / "deeptemp-k16-3.pb"))

    def analyse(self, path: Path) -> AudioFeatures:
        es = self.es
        a16 = es.MonoLoader(filename=str(path), sampleRate=16000, resampleQuality=4)()
        a11 = es.MonoLoader(filename=str(path), sampleRate=11025, resampleQuality=4)()
        a44 = es.MonoLoader(filename=str(path), sampleRate=44100)()

        emb = self.effnet(a16)  # patches × 1280
        styles = np.asarray(self.genre(emb)).mean(axis=0)
        jam = np.asarray(self.jamendo(emb)).mean(axis=0)
        bins = {name: float(np.asarray(m(emb)).mean(axis=0)[self.positive[name]]) for name, m in self.binary.items()}
        av = np.asarray(self.deam(self.musicnn(a16))).mean(axis=0)  # (valence, arousal), DEAM scale 1–9

        tempocnn_bpm, _, _ = self.tempocnn(a11)
        rhythm_bpm = self.es.RhythmExtractor2013(method="multifeature")(a44)[0]

        electronic = float(sum(p for lbl, p in zip(self.style_labels, styles, strict=True) if lbl.startswith("Electronic---")))
        electronic /= float(styles.sum()) or 1.0
        key, scale, strength = es.KeyExtractor(profileType="edma" if electronic >= 0.5 else "temperley")(a44)

        _, _, integrated, _ = es.LoudnessEBUR128(sampleRate=44100)(es.StereoMuxer()(a44, a44))
        _, onset_rate = es.OnsetRate()(a44)

        mood_outputs = {
            "mtg_jamendo_moodtheme": dict(zip(self.mood_labels, map(float, jam), strict=True)),
            "mood_aggressive": {"aggressive": bins["mood_aggressive"]},
            "mood_party": {"party": bins["mood_party"]},
            "mood_relaxed": {"relaxed": bins["mood_relaxed"]},
            "mood_happy": {"happy": bins["mood_happy"]},
            "mood_sad": {"sad": bins["mood_sad"]},
        }
        return AudioFeatures(
            styles=dict(zip(self.style_labels, map(float, styles), strict=True)),
            embedding=np.asarray(emb).mean(axis=0).astype(np.float32),
            mood_outputs=mood_outputs,
            vocal=bins["voice_instrumental"],
            danceability=bins["danceability"],
            arousal=float(av[1]),
            loudness_lufs=float(integrated),
            onset_rate=float(onset_rate),
            tempocnn_bpm=float(tempocnn_bpm),
            rhythm_bpm=float(rhythm_bpm),
            key_tonic=key,
            key_scale=scale,
            key_strength=float(strength),
        )
