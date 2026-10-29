"""Reciter audio and reference word timings for building PhonemeFollowerTest fixtures.

Everything is downloaded from the same servers Quran Android uses and cached under the data
directory (RECITATION_DATA, default ./data).
"""
import os
import sqlite3
import subprocess
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np

DATA = Path(os.environ.get("RECITATION_DATA", "data"))
DATABASES_URL = "https://android.quran.com/data/databases/"
SAMPLE_RATE = 16000

QURANICAUDIO_URL = "https://download.quranicaudio.com/quran/"
# Reciters with gapless audio and word-level timings (timings "words" column).
RECITERS = {
  "minshawi_murattal": QURANICAUDIO_URL + "muhammad_siddeeq_al-minshaawee/",
}


@dataclass
class Word:
  sura: int
  ayah: int
  index: int  # 1-based position in the ayah
  text: str  # Uthmani text
  start: float | None = None  # seconds into the sura audio
  end: float | None = None


def _download(url: str, path: Path) -> Path:
  if not path.exists():
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    # The Quran data server refuses Python's default user agent.
    request = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
    with urllib.request.urlopen(request) as response, open(tmp, "wb") as out:
      out.write(response.read())
    tmp.rename(path)
  return path


def _database(name: str, url: str) -> Path:
  db = DATA / "db" / name
  if not db.exists():
    archive = _download(url, DATA / "zips" / (url.rsplit("/", 1)[1]))
    with zipfile.ZipFile(archive) as z:
      z.extractall(db.parent)
  return db


def quran_text() -> sqlite3.Connection:
  db = _database("quran.ar.uthmani.v2.db", DATABASES_URL + "quran.ar.uthmani.v2.db.zip")
  return sqlite3.connect(db)


def sura_words(sura: int, reciter: str = "minshawi_murattal") -> list[Word]:
  """Words of a sura (ayah 1 onwards) with the reciter's reference word timings."""
  text = quran_text()
  timings = sqlite3.connect(_database(reciter + ".db", DATABASES_URL + "audio/" + reciter + ".zip"))
  word_times = {}
  for ayah, words in timings.execute("select ayah, words from timings where sura = ?", (sura,)):
    for entry in (words or "").split(","):
      if entry:
        index, start, end = (int(v) for v in entry.split(":"))
        word_times[(ayah, index)] = (start / 1000, end / 1000)

  result = []
  for ayah, ayah_text in text.execute(
    "select ayah, text from arabic_text where sura = ? order by ayah", (sura,)
  ):
    for index, token in enumerate(ayah_text.split(), start=1):
      start, end = word_times.get((ayah, index), (None, None))
      result.append(Word(sura, ayah, index, token, start, end))
  return result


def sura_audio(sura: int, reciter: str = "minshawi_murattal") -> np.ndarray:
  """Mono 16 kHz float32 audio of a whole sura."""
  base = RECITERS.get(reciter) or QURANICAUDIO_URL + reciter + "/"
  mp3 = _download(f"{base}{sura:03d}.mp3", DATA / "audio" / reciter.replace("/", "_") / f"{sura:03d}.mp3")
  wav = mp3.with_suffix(".f32")
  if not wav.exists():
    subprocess.run(
      ["ffmpeg", "-v", "error", "-y", "-i", str(mp3), "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE),
       "-f", "f32le", str(wav)],
      check=True,
    )
  return np.fromfile(wav, dtype=np.float32)
