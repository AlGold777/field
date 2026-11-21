#!/usr/bin/env python3
"""Собирает корпус sflab: скачивает источники из manifest.json (curl), декодирует через afconvert (macOS),
вырезает 20 с начиная с start_s и пишет 48 кГц / 24 бит / стерео WAV в tools/audio-corpus/wav/<id>.wav.
Источники и готовые WAV в git не хранятся (см. .gitignore); их sha256 фиксируются в manifest.json.
Запуск: python3 tools/audio-corpus/fetch.py [--pin]   (--pin записывает sha256 и выбирает start_s по самому громкому окну)."""
import hashlib, json, os, struct, subprocess, sys, wave, array

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'src')
OUT = os.path.join(HERE, 'wav')
CLIP_S = 20
pin = '--pin' in sys.argv
manifest = json.load(open(os.path.join(HERE, 'manifest.json'), encoding='utf-8'))
os.makedirs(SRC, exist_ok=True); os.makedirs(OUT, exist_ok=True)

def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    return h.hexdigest()

def loudest_start(samples, channels, rate):
    """Начало окна CLIP_S с наибольшей медианой 1-секундных RMS (избегаем вступлений и затуханий)."""
    sec = rate * channels
    n = len(samples) // sec
    rms = []
    for i in range(n):
        seg = samples[i * sec:(i + 1) * sec]
        rms.append((sum(v * v for v in seg) / len(seg)) ** 0.5)
    best, best_i = -1, 0
    for i in range(0, max(1, n - CLIP_S + 1), 2):
        w = sorted(rms[i:i + CLIP_S])
        med = w[len(w) // 2]
        if med > best: best, best_i = med, i
    return float(best_i)

failed = 0
for item in manifest:
    ext = item['format'].lower()
    src = os.path.join(SRC, item['id'] + '.' + ext)
    if not os.path.isfile(src):
        print('download', item['id'], item['url'])
        subprocess.run(['curl', '-sSL', '--fail', '-o', src, item['url']], check=True)
    digest = sha(src)
    if item.get('source_sha256') and item['source_sha256'] != digest:
        print('SOURCE HASH MISMATCH', item['id']); failed += 1; continue
    full = os.path.join(SRC, item['id'] + '.full.wav')
    subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI24@48000', '-c', '2', src, full], check=True)
    with wave.open(full, 'rb') as w:
        rate, ch, frames = w.getframerate(), w.getnchannels(), w.getnframes()
        raw = w.readframes(frames)
    if frames < CLIP_S * rate:
        print('TOO SHORT', item['id'], frames / rate, 's'); failed += 1; continue
    if pin:  # грубая оценка громкости по старшему байту 24-битных отсчётов
        hi = array.array('b'); hi.frombytes(raw[2::3])
        item['start_s'] = loudest_start(hi, ch, rate)
    frame_bytes = 3 * ch
    start_frame = int(item['start_s'] * rate)
    if start_frame + CLIP_S * rate > frames:  # иначе клип тихо получился бы короче 20 с
        print('CLIP OUT OF RANGE', item['id'], 'start', item['start_s'], 's +', CLIP_S, 's >', frames / rate, 's'); failed += 1
        os.remove(full); continue
    s0 = start_frame * frame_bytes
    clip = raw[s0:s0 + CLIP_S * rate * frame_bytes]
    dst = os.path.join(OUT, item['id'] + '.wav')
    with wave.open(dst, 'wb') as w:
        w.setnchannels(ch); w.setsampwidth(3); w.setframerate(rate); w.writeframes(clip)
    os.remove(full)
    out_digest = sha(dst)
    if pin:
        item['source_sha256'] = digest; item['wav_sha256'] = out_digest
    elif item.get('wav_sha256') and item['wav_sha256'] != out_digest:
        print('note: decoded clip differs from pinned hash (другая версия декодера macOS?)', item['id'])
    print('ok', item['id'], 'start', item['start_s'], 's')
if pin:
    json.dump(manifest, open(os.path.join(HERE, 'manifest.json'), 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
sys.exit(1 if failed else 0)
