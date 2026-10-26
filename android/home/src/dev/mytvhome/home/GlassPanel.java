package dev.mytvhome.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.FrameLayout;

/**
 * Frosted-glass surface: draws the part of the pre-blurred backdrop that lies behind it, then a soft
 * white veil, a hairline border and a faint top highlight. Children draw on top.
 * Fallback (no backdrop yet): a plain translucent dark fill, still legible.
 */
class GlassPanel extends FrameLayout implements Backdrop.Listener {
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final RectF r = new RectF();
    private final int[] loc = new int[2];
    private final float radius;
    private BitmapShader shader;
    private Bitmap blurred;

    GlassPanel(Context c, float radiusDp) {
        super(c);
        radius = Ui.dp(c, radiusDp);
        setWillNotDraw(false);
        veil.setColor(0x1FFFFFFF);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dp(c, 1));
        border.setColor(0x38FFFFFF);
        highlight.setStyle(Paint.Style.STROKE);
        highlight.setStrokeWidth(Ui.dp(c, 1.2f));
    }

    @Override public void onBackdrop(Bitmap full, Bitmap b) {
        blurred = b;
        shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        glass.setShader(shader);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        highlight.setShader(new LinearGradient(0, 0, 0, h * 0.5f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        r.set(0, 0, getWidth(), getHeight());
        if (shader != null) {
            getLocationInWindow(loc);
            android.view.View root = getRootView();
            float sx = root.getWidth() / (float) blurred.getWidth();
            float sy = root.getHeight() / (float) blurred.getHeight();
            m.setScale(sx, sy);
            m.postTranslate(-loc[0], -loc[1]);
            shader.setLocalMatrix(m);
            canvas.drawRoundRect(r, radius, radius, glass);
            canvas.drawRoundRect(r, radius, radius, veil);
        } else {
            veil.setColor(0x99202530);
            canvas.drawRoundRect(r, radius, radius, veil);
            veil.setColor(0x1FFFFFFF);
        }
        float h = border.getStrokeWidth() / 2;
        r.inset(h, h);
        canvas.drawRoundRect(r, radius, radius, border);
        canvas.drawRoundRect(r, radius, radius, highlight);
    }
}
