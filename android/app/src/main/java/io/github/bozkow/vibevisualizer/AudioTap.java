package io.github.bozkow.vibevisualizer;

import android.media.audiofx.Visualizer;
import android.util.Base64;

/**
 * Vibe Bridge on the phone: Android's Visualizer listens to everything the phone plays (the Spotify app included) and
 * turns it into the same frequency data a browser's Web Audio analyser makes (1024 bins up to 24 kHz, 0-255 on a dB
 * scale), so Vibe Visualizer's bars, background and desync detection work exactly as with the desktop Vibe Bridge.
 * Only the spectrum is read; no sound is recorded or sent anywhere.
 */
final class AudioTap implements Visualizer.OnDataCaptureListener {
    private static final int BINS = 1024;                 // what Vibe Visualizer expects (fftSize 2048)
    private static final float NYQUIST = 24000f;          // ... spread over 0-24 kHz, as from a 48 kHz analyser
    private static final float SMOOTHING = 0.35f;         // the analyser's smoothingTimeConstant
    private static final float MIN_DB = -48f;             // Visualizer data is 8-bit: about 48 dB of range

    private Visualizer vis;
    private final byte[] out = new byte[BINS];
    private float[] smooth = new float[0];
    private int seq, taken = -1;
    String error;

    synchronized boolean running() { return vis != null; }

    synchronized boolean start() {
        if (vis != null) return true;
        error = null;
        try {
            vis = new Visualizer(0);                          // session 0: the phone's whole output mix
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, range[1]));
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);   // the same level at any volume
            vis.setDataCaptureListener(this, Visualizer.getMaxCaptureRate(), false, true);
            vis.setEnabled(true);
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            stop();
            return false;
        }
    }

    synchronized void stop() {
        if (vis == null) return;
        try { vis.setEnabled(false); } catch (Throwable ignored) { }
        try { vis.release(); } catch (Throwable ignored) { }
        vis = null;
    }

    @Override public void onWaveFormDataCapture(Visualizer v, byte[] wave, int rate) { }

    @Override public void onFftDataCapture(Visualizer v, byte[] fft, int samplingRateMilliHz) {
        // fft: [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...], n/2 bins over 0 .. rate/2
        final int n = fft.length / 2;
        if (n < 2) return;
        final float half = samplingRateMilliHz / 2000f;
        synchronized (this) {
            if (smooth.length != n) smooth = new float[n];
            for (int k = 0; k < n; k++) {
                float m = k == 0 ? Math.abs(fft[0]) : (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
                smooth[k] = smooth[k] * SMOOTHING + m * (1 - SMOOTHING);
            }
            for (int i = 0; i < BINS; i++) {
                float x = (i * NYQUIST / BINS) / half * n;   // this bin's frequency, in Visualizer bins
                int j = (int) x;
                float m = j >= n - 1 ? 0 : smooth[j] + (smooth[j + 1] - smooth[j]) * (x - j);
                float db = (float) (20 * Math.log10(Math.max(m, 1e-3f) / 128f));
                int b = Math.round((db - MIN_DB) / -MIN_DB * 255f);
                out[i] = (byte) Math.max(0, Math.min(255, b));
            }
            seq++;
        }
    }

    /** The newest frame as base64, or "" when there's nothing new since the last call. */
    synchronized String take() {
        if (seq == taken) return "";
        taken = seq;
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }
}
