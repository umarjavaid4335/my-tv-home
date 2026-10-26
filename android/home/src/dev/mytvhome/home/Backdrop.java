package dev.mytvhome.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Current background photo plus a small, pre-blurred copy of it.
 *
 * Live blur (RenderEffect) needs API 31 and a strong GPU; this TV is API 30 / GLES 2. Instead the
 * blurred copy is computed once per background change and every GlassPanel draws the region of it
 * that sits behind the panel, scaled up with bilinear filtering. Visually equivalent, nearly free.
 */
final class Backdrop {
    interface Listener { void onBackdrop(Bitmap full, Bitmap blurred); }

    static final int BLUR_W = 192, BLUR_H = 108;   // 1/10 of the 1920x1080 screen

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    Bitmap full, blurred;
    private int generation;

    Backdrop(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    void addListener(Listener l) {
        listeners.add(l);
        if (full != null) l.onBackdrop(full, blurred);
    }

    void load(final String assetPath) {
        final int gen = ++generation;
        new Thread(new Runnable() {
            @Override public void run() {
                try (InputStream in = ctx.getAssets().open(assetPath)) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory; photos don't need alpha
                    final Bitmap f = BitmapFactory.decodeStream(in, null, o);
                    final Bitmap b = blur(f);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation) return;
                            full = f;
                            blurred = b;
                            for (Listener l : listeners) l.onBackdrop(f, b);
                        }
                    });
                } catch (Exception e) {
                    Log.w("MyHome", "background load failed: " + assetPath, e);
                }
            }
        }, "backdrop").start();
    }

    /** Downscale, soften (slightly darker, a touch more saturated) and box-blur three times (~gaussian). */
    static Bitmap blur(Bitmap src) {
        Bitmap mid = Bitmap.createScaledBitmap(src, BLUR_W * 2, BLUR_H * 2, true);
        Bitmap small = Bitmap.createBitmap(BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.15f);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.86f, 0.86f, 0.86f, 1f);
        cm.postConcat(dim);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(mid, null, new android.graphics.Rect(0, 0, BLUR_W, BLUR_H), p);
        mid.recycle();
        int[] px = new int[BLUR_W * BLUR_H];
        small.getPixels(px, 0, BLUR_W, 0, 0, BLUR_W, BLUR_H);
        for (int i = 0; i < 3; i++) {
            boxBlurH(px, BLUR_W, BLUR_H, 4);
            boxBlurV(px, BLUR_W, BLUR_H, 4);
        }
        small.setPixels(px, 0, BLUR_W, 0, 0, BLUR_W, BLUR_H);
        return small;
    }

    private static void boxBlurH(int[] px, int w, int h, int r) {
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int off = y * w;
            System.arraycopy(px, off, row, 0, w);
            for (int x = 0; x < w; x++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int c = row[Math.min(w - 1, Math.max(0, x + k))];
                    rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255; n++;
                }
                px[off + x] = 0xff000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
            }
        }
    }

    private static void boxBlurV(int[] px, int w, int h, int r) {
        int[] col = new int[h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) col[y] = px[y * w + x];
            for (int y = 0; y < h; y++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int c = col[Math.min(h - 1, Math.max(0, y + k))];
                    rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255; n++;
                }
                px[y * w + x] = 0xff000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
            }
        }
    }
}
