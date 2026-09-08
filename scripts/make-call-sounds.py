#!/usr/bin/env python3
"""
Regenerates the call ringtones in feature/calls/src/jvmMain/resources/callsounds.

The tones are a port of the web client's synthesized sounds
(zillit_web/src/lineTwo/ui/callSounds.ts) so that a call sounds the same
whichever client you answer it on. Web builds them live in WebAudio; the
desktop cannot, because it rings from `javax.sound.sampled` with no browser
up, so the same waveform is baked into a WAV here.

CallRinger loops each clip whole (`Clip.LOOP_CONTINUOUSLY`), so each file must
hold EXACTLY ONE period of the cadence, trailing silence included. That is why
the durations below are the loop periods and not the length of the audible part.

LEVEL: web's raw gains (0.12 / 0.08) are about 8 dB below what this app has
always rung at, and a ringtone nobody hears is a missed call. Both tones are
scaled by one common factor, so web's relative balance between the ringtone and
the quieter ringback is preserved exactly while the absolute loudness stays
where desktop users already have it.

    python3 scripts/make-call-sounds.py
"""
import math
import struct
import wave
from pathlib import Path

RATE = 22050          # matches the files this replaces
LEVEL = 0.299 / 0.12  # web's gain -> this app's established ringtone loudness

OUT = Path(__file__).resolve().parent.parent / "feature/calls/src/jvmMain/resources/callsounds"

# (frequency Hz, start s, duration s, gain) — verbatim from callSounds.ts
INCOMING = ("incoming.wav", 2.0, [
    (740, 0.0, 0.25, 0.12),
    (880, 0.3, 0.35, 0.12),
    (740, 0.9, 0.25, 0.12),
    (880, 1.2, 0.35, 0.12),
])
OUTGOING = ("outgoing.wav", 3.0, [
    (425, 0.0, 1.0, 0.08),
])

ATTACK = 0.02   # gain.linearRampToValueAtTime(gainV, at + 0.02)
RELEASE = 0.03  # gain.setValueAtTime(gainV, at + dur - 0.03)


def envelope(t, dur):
    """WebAudio's gain schedule in callSounds.ts: 20 ms in, 30 ms out."""
    if t < 0 or t > dur:
        return 0.0
    if t < ATTACK:
        return t / ATTACK
    if t > dur - RELEASE:
        return max(0.0, (dur - t) / RELEASE)
    return 1.0


def render(period, beeps):
    frames = [0.0] * int(round(period * RATE))
    for freq, at, dur, gain in beeps:
        for i in range(int(round(at * RATE)), min(int(round((at + dur) * RATE)), len(frames))):
            t = i / RATE - at
            frames[i] += envelope(t, dur) * math.sin(2 * math.pi * freq * t) * gain * LEVEL
    return frames


def write(name, frames):
    path = OUT / name
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(
            struct.pack("<h", max(-32768, min(32767, int(round(s * 32767))))) for s in frames
        ))
    peak = max(abs(s) for s in frames)
    print(f"{name}: {len(frames)/RATE:.3f}s  peak={peak:.3f} full scale")


for name, period, beeps in (INCOMING, OUTGOING):
    write(name, render(period, beeps))
