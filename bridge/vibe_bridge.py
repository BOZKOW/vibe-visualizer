"""Vibe Bridge: lets Vibe Visualizer's bars follow everything this computer plays, the Spotify app included.

It listens to the sound going to your speakers or headphones (PipeWire / PulseAudio monitor on Linux, WASAPI
loopback on Windows), turns it into the same frequency data a browser's Web Audio analyser makes, and sends that
to Vibe Visualizer over a local WebSocket (ws://127.0.0.1:47120). Only programs on this computer can connect; no
sound or data leaves it.

    vibe-bridge                 run with the default output device
    vibe-bridge --list          list the output devices
    vibe-bridge --device NAME   follow a specific output (part of its name is enough)
"""
import argparse, asyncio, json, sys, threading, time

import numpy as np
import soundcard as sc
from websockets.asyncio.server import serve

VERSION = "1.0.0"
FFT = 2048              # the same as Vibe Visualizer's analyser (fftSize 2048 -> 1024 bins)
RATE = 48000
SMOOTHING = 0.35        # the analyser's smoothingTimeConstant
MIN_DB, MAX_DB = -100.0, -30.0   # the analyser's default dB range for byte data


class Analyser:
    """A numpy copy of the Web Audio AnalyserNode's getByteFrequencyData()."""
    def __init__(self):
        n = np.arange(FFT)
        a = 0.16            # Blackman window, as the Web Audio spec defines it
        self.win = (1 - a) / 2 - 0.5 * np.cos(2 * np.pi * n / FFT) + a / 2 * np.cos(4 * np.pi * n / FFT)
        self.buf = np.zeros(FFT, dtype=np.float32)
        self.smooth = np.zeros(FFT // 2)

    def push(self, mono):
        k = len(mono)
        if k >= FFT:
            self.buf[:] = mono[-FFT:]
        else:
            self.buf = np.roll(self.buf, -k)
            self.buf[-k:] = mono

    def bytes(self):
        mag = np.abs(np.fft.rfft(self.buf * self.win))[:FFT // 2] / FFT
        self.smooth = SMOOTHING * self.smooth + (1 - SMOOTHING) * mag
        db = 20 * np.log10(np.maximum(self.smooth, 1e-12))
        return np.clip((db - MIN_DB) * (255.0 / (MAX_DB - MIN_DB)), 0, 255).astype(np.uint8).tobytes()


class Capture(threading.Thread):
    """Records the output device's monitor / loopback and keeps the newest analyser frame."""
    def __init__(self, device):
        super().__init__(daemon=True)
        self.want = device
        self.frame, self.seq, self.device, self.error = bytes(FFT // 2), 0, None, None
        self.lock = threading.Lock()

    def speaker(self):
        if self.want:
            for s in sc.all_speakers():
                if self.want.lower() in s.name.lower():
                    return s
        return sc.default_speaker()

    def run(self):
        an = Analyser()
        while True:
            try:
                sp = self.speaker()
                mic = sc.get_microphone(id=str(sp.name), include_loopback=True)
                self.device, self.error = sp.name, None
                print("listening to:", sp.name, flush=True)
                checked = time.time()
                with mic.recorder(samplerate=RATE, channels=2, blocksize=1024) as rec:
                    while True:
                        data = rec.record(numframes=1024)
                        an.push(data.mean(axis=1) if data.ndim > 1 else data)
                        frame = an.bytes()
                        with self.lock:
                            self.frame, self.seq = frame, self.seq + 1
                        if time.time() - checked > 3:   # follow a change of the default output (headphones plugged in…)
                            checked = time.time()
                            if self.speaker().name != sp.name:
                                print("output changed", flush=True)
                                break
            except Exception as e:
                self.error = str(e)
                print("capture problem:", e, "- retrying", flush=True)
                time.sleep(2)


async def main(args):
    cap = Capture(args.device)
    cap.start()
    clients = set()

    async def handler(ws):
        clients.add(ws)
        print("Vibe Visualizer connected (%d open)" % len(clients), flush=True)
        try:
            await ws.send(json.dumps({"type": "hello", "name": "Vibe Bridge", "version": VERSION, "rate": RATE,
                                      "bins": FFT // 2, "device": cap.device}))
            await ws.wait_closed()
        finally:
            clients.discard(ws)
            print("Vibe Visualizer disconnected (%d open)" % len(clients), flush=True)

    async def pump():   # send each new frame to everyone connected, at most `fps` times a second
        last, sent_dev = -1, None
        while True:
            await asyncio.sleep(1 / args.fps)
            if not clients:
                continue
            with cap.lock:
                seq, frame = cap.seq, cap.frame
            if cap.device != sent_dev:
                sent_dev = cap.device
                msg = json.dumps({"type": "device", "device": cap.device})
                for ws in list(clients):
                    try:
                        await ws.send(msg)
                    except Exception:
                        pass
            if seq == last:
                continue
            last = seq
            for ws in list(clients):
                try:
                    await ws.send(frame)
                except Exception:
                    pass

    async with serve(handler, "127.0.0.1", args.port, max_size=2 ** 16):
        print("Vibe Bridge %s is running on ws://127.0.0.1:%d - leave this window open." % (VERSION, args.port), flush=True)
        await pump()


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="Vibe Bridge: computer sound for Vibe Visualizer's bars.")
    p.add_argument("--port", type=int, default=47120)
    p.add_argument("--device", help="follow this output device instead of the default (part of its name)")
    p.add_argument("--fps", type=int, default=50, help="frames sent per second (default 50)")
    p.add_argument("--list", action="store_true", help="list output devices and exit")
    a = p.parse_args()
    if a.list:
        for s in sc.all_speakers():
            print(("* " if s.name == sc.default_speaker().name else "  ") + s.name)
        sys.exit(0)
    try:
        asyncio.run(main(a))
    except KeyboardInterrupt:
        pass
    except OSError as e:
        print("Can't start: %s (is Vibe Bridge already running?)" % e)
        sys.exit(1)
