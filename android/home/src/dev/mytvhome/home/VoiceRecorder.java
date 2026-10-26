package dev.mytvhome.home;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Push-to-talk capture from the TV's microphone. This TCL firmware's Google voice-input screen never opens
 * the mic for apps (TCL's mic manager owns the remote/far-field path), so My Home records itself and lets
 * Gemini transcribe. 16 kHz mono PCM16, simple energy-based end-of-speech detection; nothing is stored.
 */
final class VoiceRecorder {
    interface Listener {
        void onLevel(float level);          // 0..1, for the on-screen meter
        void onDone(byte[] wav);            // null if no speech was heard
        void onError(String message);
    }

    private static final int RATE = 16000, FRAME = 480;              // 30 ms frames
    private static final int MAX_MS = 10000, NO_SPEECH_MS = 6000, END_SILENCE_MS = 1000, CALIBRATE_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running, stopEarly;

    boolean running() { return running; }

    void stop() { stopEarly = true; }

    void start(final Listener l) {
        if (running) return;
        running = true;
        stopEarly = false;
        new Thread(() -> {
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("mic unavailable");
                rec.startRecording();
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                short[] buf = new short[FRAME];
                byte[] bytes = new byte[FRAME * 2];
                double floor = Double.MAX_VALUE, peak = 0, smooth = -1;
                int frames = 0, calibFrames = CALIBRATE_MS / 30, speechFrames = 0, silentFrames = 0, above = 0;
                int firstLoud = -1, lastLoud = -1;
                java.util.ArrayList<Integer> levels = new java.util.ArrayList<>();   // for tuning the detector
                boolean speaking = false, retried = false;
                while (!stopEarly) {
                    int n = rec.read(buf, 0, FRAME);
                    if (n <= 0) continue;
                    // TCL's mic manager can still hold the mic for a moment; apps then read pure zeros. Reopen once.
                    if (!retried && frames == calibFrames && peak < 3) {
                        retried = true;
                        rec.stop();
                        rec.release();
                        Thread.sleep(400);
                        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
                        rec.startRecording();
                        pcm.reset();
                        levels.clear();
                        frames = 0;
                        smooth = -1;
                        Log.i("MyHome", "voice: mic was silent, reopened");
                        continue;
                    }
                    double sum = 0;
                    for (int i = 0; i < n; i++) {
                        sum += (double) buf[i] * buf[i];
                        bytes[2 * i] = (byte) buf[i];
                        bytes[2 * i + 1] = (byte) (buf[i] >> 8);
                    }
                    pcm.write(bytes, 0, n * 2);
                    double rms = Math.sqrt(sum / n);
                    peak = Math.max(peak, rms);
                    levels.add((int) rms);
                    frames++;
                    // Measured on this TV: the first ~300 ms after opening the mic is a burst (~1300-1800 rms),
                    // the quiet room is ~40-70 and speech ~1300-9000. So skip the burst, track the floor as the
                    // lowest smoothed level, and call >5x the floor speech.
                    smooth = smooth < 0 ? rms : smooth * 0.6 + rms * 0.4;
                    if (frames <= calibFrames) continue;
                    floor = Math.min(floor, smooth);
                    double threshold = Math.max(floor * 5, 450);
                    final float level = (float) Math.min(1, rms / (threshold * 4));
                    main.post(() -> l.onLevel(level));
                    if (rms > threshold) {
                        speechFrames++; above++; silentFrames = 0;
                        if (speechFrames >= 3) speaking = true;
                        if (firstLoud < 0) firstLoud = frames;
                        lastLoud = frames;
                    }
                    else { silentFrames++; speechFrames = 0; }
                    int ms = frames * 30;
                    if (speaking && silentFrames * 30 >= END_SILENCE_MS) break;
                    if (!speaking && ms >= NO_SPEECH_MS) break;
                    if (ms >= MAX_MS) break;
                }
                // Send if speech was detected, the user ended it, or anything clearly above the room was heard;
                // Gemini reports "no speech" when it was only noise.
                boolean send = speaking || (stopEarly && frames > calibFrames);
                Log.i("MyHome", "voice: " + frames * 30 + " ms, floor " + Math.round(floor) + ", peak " + Math.round(peak)
                        + ", loud frames " + above + ", speech " + speaking + ", send " + send);
                java.util.Collections.sort(levels);
                if (!levels.isEmpty()) Log.i("MyHome", "voice levels p10/p50/p90/p99: " + levels.get(levels.size() / 10) + "/"
                        + levels.get(levels.size() / 2) + "/" + levels.get(levels.size() * 9 / 10) + "/" + levels.get(levels.size() * 99 / 100));
                byte[] all = pcm.toByteArray();
                if (send && speaking && firstLoud >= 0) {     // keep speech -0.4/+0.6 s: smaller upload, faster answer
                    int from = Math.max(0, (firstLoud - 1 - 13) * FRAME * 2);
                    int to = Math.min(all.length, (lastLoud + 20) * FRAME * 2);
                    if (to > from) all = java.util.Arrays.copyOfRange(all, from, to);
                }
                Log.i("MyHome", "voice: upload " + all.length / 1024 + " KB");
                final byte[] wav = send ? wav(all) : null;
                main.post(() -> l.onDone(wav));
            } catch (Exception e) {
                Log.w("MyHome", "voice capture failed", e);
                main.post(() -> l.onError("The microphone isn't available."));
            } finally {
                if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
                running = false;
            }
        }, "voice").start();
    }

    private static byte[] wav(byte[] pcm) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(pcm.length + 44);
        int byteRate = RATE * 2;
        writeStr(o, "RIFF"); writeInt(o, 36 + pcm.length); writeStr(o, "WAVE");
        writeStr(o, "fmt "); writeInt(o, 16); writeShort(o, 1); writeShort(o, 1); writeInt(o, RATE);
        writeInt(o, byteRate); writeShort(o, 2); writeShort(o, 16);
        writeStr(o, "data"); writeInt(o, pcm.length);
        o.write(pcm, 0, pcm.length);
        return o.toByteArray();
    }

    private static void writeStr(ByteArrayOutputStream o, String s) { for (char c : s.toCharArray()) o.write(c); }

    private static void writeInt(ByteArrayOutputStream o, int v) { for (int i = 0; i < 4; i++) o.write(v >> (8 * i)); }

    private static void writeShort(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }
}
