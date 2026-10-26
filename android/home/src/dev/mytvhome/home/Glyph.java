package dev.mytvhome.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small line-drawn icons (weather + utility buttons); drawn in code so no icon assets/licences are needed. */
final class Glyph extends View {
    static final int SUN = 0, MOON = 1, PARTLY = 2, CLOUD = 3, FOG = 4, DRIZZLE = 5, RAIN = 6, SNOW = 7,
            STORM = 8, IMAGE = 20, GRID = 21, GEAR = 22, HOUSE = 23, MIC = 24, UNKNOWN = 99;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private int kind = UNKNOWN;

    Glyph(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xFFFFFFFF);
        fill.setColor(0xFFFFFFFF);
    }

    void setKind(int k) { kind = k; invalidate(); }

    /** WMO weather interpretation code (Open-Meteo) -> glyph. */
    static int forWmo(int code, boolean day) {
        if (code == 0) return day ? SUN : MOON;
        if (code <= 2) return PARTLY;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        return CLOUD;
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        stroke.setStrokeWidth(Math.max(2f, s * 0.065f));
        switch (kind) {
            case SUN: sun(c, cx, cy, s * 0.2f, s); break;
            case MOON: moon(c, cx, cy, s * 0.28f); break;
            case PARTLY:
                sun(c, cx - s * 0.14f, cy - s * 0.12f, s * 0.14f, s * 0.7f);
                cloud(c, cx + s * 0.06f, cy + s * 0.1f, s * 0.78f);
                break;
            case CLOUD: cloud(c, cx, cy, s); break;
            case FOG:
                for (int i = 0; i < 3; i++) {
                    float y = cy - s * 0.18f + i * s * 0.18f;
                    c.drawLine(cx - s * 0.32f + (i % 2) * s * 0.08f, y, cx + s * 0.32f, y, stroke);
                }
                break;
            case DRIZZLE: case RAIN: case SNOW: case STORM:
                cloud(c, cx, cy - s * 0.12f, s * 0.9f);
                float y0 = cy + s * 0.16f;
                for (int i = -1; i <= 1; i++) {
                    float x = cx + i * s * 0.16f;
                    if (kind == SNOW) c.drawCircle(x, y0 + s * 0.08f, s * 0.035f, fill);
                    else if (kind == STORM && i == 0) {
                        path.reset();
                        path.moveTo(x + s * 0.04f, y0);
                        path.lineTo(x - s * 0.04f, y0 + s * 0.12f);
                        path.lineTo(x + s * 0.03f, y0 + s * 0.12f);
                        path.lineTo(x - s * 0.04f, y0 + s * 0.25f);
                        c.drawPath(path, stroke);
                    } else {
                        float len = kind == DRIZZLE ? 0.07f : 0.15f;
                        c.drawLine(x, y0, x - s * 0.04f, y0 + s * len, stroke);
                    }
                }
                break;
            case IMAGE:
                r.set(cx - s * 0.36f, cy - s * 0.28f, cx + s * 0.36f, cy + s * 0.28f);
                c.drawRoundRect(r, s * 0.08f, s * 0.08f, stroke);
                path.reset();
                path.moveTo(cx - s * 0.3f, cy + s * 0.2f);
                path.lineTo(cx - s * 0.08f, cy - s * 0.04f);
                path.lineTo(cx + s * 0.06f, cy + s * 0.1f);
                path.lineTo(cx + s * 0.14f, cy + s * 0.02f);
                path.lineTo(cx + s * 0.3f, cy + s * 0.2f);
                c.drawPath(path, stroke);
                c.drawCircle(cx + s * 0.16f, cy - s * 0.12f, s * 0.05f, fill);
                break;
            case GRID:
                float g = s * 0.15f, gap = s * 0.06f;
                for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
                    float x = cx - g - gap / 2 + i * (g + gap), y = cy - g - gap / 2 + j * (g + gap);
                    r.set(x, y, x + g, y + g);
                    c.drawRoundRect(r, s * 0.04f, s * 0.04f, stroke);
                }
                break;
            case GEAR:
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    c.drawLine(cx + (float) Math.cos(a) * s * 0.22f, cy + (float) Math.sin(a) * s * 0.22f,
                            cx + (float) Math.cos(a) * s * 0.32f, cy + (float) Math.sin(a) * s * 0.32f, stroke);
                }
                c.drawCircle(cx, cy, s * 0.21f, stroke);
                c.drawCircle(cx, cy, s * 0.08f, stroke);
                break;
            case MIC:
                r.set(cx - s * 0.11f, cy - s * 0.32f, cx + s * 0.11f, cy + s * 0.06f);
                c.drawRoundRect(r, s * 0.11f, s * 0.11f, stroke);
                r.set(cx - s * 0.22f, cy - s * 0.14f, cx + s * 0.22f, cy + s * 0.18f);
                c.drawArc(r, 0, 180, false, stroke);
                c.drawLine(cx, cy + s * 0.18f, cx, cy + s * 0.3f, stroke);
                c.drawLine(cx - s * 0.1f, cy + s * 0.3f, cx + s * 0.1f, cy + s * 0.3f, stroke);
                break;
            case HOUSE:
                path.reset();
                path.moveTo(cx - s * 0.32f, cy - s * 0.02f);
                path.lineTo(cx, cy - s * 0.3f);
                path.lineTo(cx + s * 0.32f, cy - s * 0.02f);
                path.moveTo(cx - s * 0.22f, cy - s * 0.1f);
                path.lineTo(cx - s * 0.22f, cy + s * 0.28f);
                path.lineTo(cx + s * 0.22f, cy + s * 0.28f);
                path.lineTo(cx + s * 0.22f, cy - s * 0.1f);
                c.drawPath(path, stroke);
                break;
            default:
                c.drawCircle(cx, cy, s * 0.06f, fill);
        }
    }

    private void sun(Canvas c, float cx, float cy, float r0, float s) {
        c.drawCircle(cx, cy, r0, stroke);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            float a0 = r0 + s * 0.07f, a1 = r0 + s * 0.13f;
            c.drawLine(cx + (float) Math.cos(a) * a0, cy + (float) Math.sin(a) * a0,
                    cx + (float) Math.cos(a) * a1, cy + (float) Math.sin(a) * a1, stroke);
        }
    }

    private void moon(Canvas c, float cx, float cy, float rad) {
        path.reset();
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        path.arcTo(r, 60, 260);
        r.set(cx - rad * 0.35f, cy - rad * 1.05f, cx + rad * 1.2f, cy + rad * 0.55f);
        path.arcTo(r, 230, -230);
        path.close();
        c.drawPath(path, stroke);
    }

    private void cloud(Canvas c, float cx, float cy, float s) {
        path.reset();
        float w = s * 0.33f, base = cy + s * 0.14f;
        path.moveTo(cx - w, base);
        r.set(cx - w - s * 0.1f, base - s * 0.24f, cx - w + s * 0.14f, base);
        path.arcTo(r, 90, 180);
        r.set(cx - s * 0.2f, cy - s * 0.26f, cx + s * 0.14f, cy + s * 0.08f);
        path.arcTo(r, 190, 160);
        r.set(cx + s * 0.04f, base - s * 0.26f, cx + w + s * 0.08f, base);
        path.arcTo(r, 250, 200);
        path.close();
        c.drawPath(path, stroke);
    }
}
