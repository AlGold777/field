#!/usr/bin/env python3
"""Генерирует тестовый стерео-трек SoundField (48 кГц, 16 бит, ~40 с).

Левый канал: аккорды-арпеджио + бас. Правый: колокольчики-мелодия + хай-хэт.
Вступление (0–4 с): «пинг-понг» — удары поочерёдно слева и справа,
чтобы сразу было слышно, какой телефон левый, а какой правый.
"""
import numpy as np, wave, sys

SR = 48000
BPM = 100
BEAT = 60 / BPM
DUR = 40.0
N = int(SR * DUR)
L = np.zeros(N); R = np.zeros(N)

def note_hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)

def env(n, a=0.005, d=1.2):
    t = np.arange(n) / SR
    e = np.exp(-t / d)
    na = max(1, int(a * SR)); e[:na] *= np.linspace(0, 1, na)
    return e

def add(buf, start_s, sig):
    s = int(start_s * SR)
    if s >= N: return
    e = min(N, s + len(sig)); buf[s:e] += sig[: e - s]

def piano(m, length=1.2, vel=0.25):
    n = int(length * SR); t = np.arange(n) / SR; f = note_hz(m)
    s = sum(np.sin(2*np.pi*f*k*t) / k**1.6 for k in range(1, 7))
    return vel * s * env(n, 0.004, 0.45)

def bell(m, length=1.6, vel=0.22):
    n = int(length * SR); t = np.arange(n) / SR; f = note_hz(m)
    s = np.sin(2*np.pi*f*t) + 0.5*np.sin(2*np.pi*f*2.76*t)*np.exp(-t/0.3) + 0.25*np.sin(2*np.pi*f*5.4*t)*np.exp(-t/0.15)
    return vel * s * env(n, 0.002, 0.6)

def bass(m, length=0.5, vel=0.35):
    n = int(length * SR); t = np.arange(n) / SR; f = note_hz(m)
    s = np.sin(2*np.pi*f*t) + 0.3*np.sin(2*np.pi*2*f*t)
    return vel * s * env(n, 0.01, 0.35)

def hat(vel=0.08):
    n = int(0.06 * SR); rng = np.random.default_rng(len(hat.__name__))
    s = rng.standard_normal(n); s = np.diff(np.concatenate([[0], s]))
    return vel * s * env(n, 0.001, 0.015)

def knock(f, vel=0.5):
    n = int(0.25 * SR); t = np.arange(n) / SR
    return vel * np.sin(2*np.pi*f*t) * env(n, 0.001, 0.05)

# Вступление: пинг-понг L/R
for i in range(8):
    add(L if i % 2 == 0 else R, 0.25 + i * 0.45, knock(660 if i % 2 == 0 else 990))

start = 4.0
chords = [[57, 60, 64], [53, 57, 60], [48, 52, 55], [55, 59, 62]]   # Am F C G
melody = [76, 74, 72, 74, 76, 76, 76, None, 74, 74, 76, 79, 79, None, 72, 71]
bar = 4 * BEAT
nbars = int((DUR - start - 2) / bar)
for b in range(nbars):
    ch = chords[b % 4]; t0 = start + b * bar
    for i in range(8):                     # арпеджио восьмыми (L)
        add(L, t0 + i * BEAT / 2, piano(ch[i % 3] + (12 if i >= 4 else 0), vel=0.18))
    add(L, t0, bass(ch[0] - 12, 1.0)); add(L, t0 + 2*BEAT, bass(ch[0] - 12, 1.0))
    for i in range(8):                     # хай-хэт (R)
        add(R, t0 + i * BEAT / 2, hat(0.10 if i % 2 else 0.05))
    if b >= 2:                             # мелодия колокольчиков (R)
        for i in range(4):
            m = melody[((b - 2) * 4 + i) % len(melody)]
            if m is not None: add(R, t0 + i * BEAT, bell(m))

# Финал: аккорд в оба канала
tail = start + nbars * bar
for m in [57, 60, 64, 69]:
    add(L, tail, piano(m, 2.0, 0.15)); add(R, tail, piano(m + 12, 2.0, 0.12))

# Лёгкая реверберация (разные задержки для ширины)
def verb(x, d_ms, g):
    d = int(d_ms * SR / 1000); y = x.copy()
    for k in range(1, 4): y[d*k:] += (g**k) * x[:-d*k]
    return y
L = verb(L, 83, 0.25); R = verb(R, 97, 0.25)

peak = max(np.abs(L).max(), np.abs(R).max())
L *= 0.7 / peak; R *= 0.7 / peak
fade = int(0.5 * SR); L[-fade:] *= np.linspace(1, 0, fade); R[-fade:] *= np.linspace(1, 0, fade)
pcm = np.empty(2 * N, dtype='<i2'); pcm[0::2] = (L * 32767).astype('<i2'); pcm[1::2] = (R * 32767).astype('<i2')
out = sys.argv[1] if len(sys.argv) > 1 else 'test_track.wav'
with wave.open(out, 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
print(out, N / SR, 's')
