"""Records what the recognizer hears of a recitation, for PhonemeFollowerTest.

  python export_fixture.py MODEL_DIR OUT.txt --sura 67 [--ayahs 1 10] [--drop 2] [--repeat 2]
  python export_fixture.py MODEL_DIR OUT.txt --sura 67 --order 1 2 4 3 4 5 20:115

Minshawi's recitation and its word timings are downloaded and cached by data.py (see
RECITATION_DATA). MODEL_DIR holds Quran-Lab's zipformer_p_arabic_v3.1.int8.onnx (or another
version) and its tokens.txt, run with sherpa-onnx as the app runs it (480 ms chunks after 480 ms
of silence). --drop leaves an ayah out, --repeat recites it twice; --order recites the ayahs
given, in that order (SURA:AYAH for another sura's).

Each output line is the recognizer's tokens after one chunk: how many of the previous line's
tokens it keeps, then its other tokens (space separated). The first line is "sura first_ayah".
"""
import argparse
import glob
import numpy as np
import sherpa_onnx

from data import SAMPLE_RATE, sura_audio, sura_words

CHUNK = 7680


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("model_dir")
  parser.add_argument("out")
  parser.add_argument("--sura", type=int, required=True)
  parser.add_argument("--ayahs", type=int, nargs=2)
  parser.add_argument("--drop", type=int)
  parser.add_argument("--repeat", type=int)
  parser.add_argument("--order", nargs="+")
  args = parser.parse_args()

  audio = {args.sura: sura_audio(args.sura)}
  words = {args.sura: sura_words(args.sura)}
  ayahs = sorted({w.ayah for w in words[args.sura]})
  order = [(int(a.split(":")[0]), int(a.split(":")[1])) if ":" in a else (args.sura, int(a))
           for a in args.order] if args.order else None
  first, last = (order[0][1], order[-1][1]) if order else args.ayahs or (ayahs[0], ayahs[-1])

  def span(sura, ayah):  # ayah audio from its first word's start to the next ayah's start
    if sura not in audio:
      audio[sura], words[sura] = sura_audio(sura), sura_words(sura)
    ws = words[sura]
    start = min(w.start for w in ws if w.ayah == ayah)
    later = [w.start for w in ws if w.ayah == ayah + 1]
    end = later[0] if later else max(w.end for w in ws if w.ayah == ayah) + 0.5
    return audio[sura][int(start * SAMPLE_RATE):int(end * SAMPLE_RATE)]

  start = min(w.start for w in words[args.sura] if w.ayah == first)
  opening = audio[args.sura][:int(start * SAMPLE_RATE)] if first == 1 else np.zeros(0, np.float32)
  pieces = [opening]
  if order:
    pieces += [span(sura, ayah) for sura, ayah in order]
  else:
    for ayah in range(first, last + 1):
      if ayah == args.drop:
        continue
      pieces.append(span(args.sura, ayah))
      if ayah == args.repeat:
        pieces.append(span(args.sura, ayah))
  pieces.append(np.zeros(SAMPLE_RATE, np.float32))
  recitation = np.concatenate(pieces).astype(np.float32)

  d = args.model_dir
  rec = sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(
    tokens=f"{d}/tokens.txt", model=glob.glob(f"{d}/zipformer_p_arabic_v3*.int8.onnx")[0], num_threads=2,
    sample_rate=SAMPLE_RATE, feature_dim=80, enable_endpoint_detection=False)
  stream = rec.create_stream()
  stream.accept_waveform(SAMPLE_RATE, np.zeros(CHUNK, np.float32))
  while rec.is_ready(stream):
    rec.decode_stream(stream)
  with open(args.out, "w", encoding="utf-8") as out:
    out.write(f"{args.sura} {first}\n")
    previous = []
    for s in range(0, len(recitation) - CHUNK + 1, CHUNK):
      stream.accept_waveform(SAMPLE_RATE, recitation[s:s + CHUNK])
      while rec.is_ready(stream):
        rec.decode_stream(stream)
      tokens = list(rec.get_result_all(stream).tokens)
      keep = 0
      while keep < min(len(previous), len(tokens)) and previous[keep] == tokens[keep]:
        keep += 1
      out.write(" ".join([str(keep)] + tokens[keep:]) + "\n")
      previous = tokens
  print(f"{len(recitation) / SAMPLE_RATE:.1f}s of audio -> {args.out}")


if __name__ == "__main__":
  main()
