package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

/**
 * The weather card beside the place's name: a glass panel (its background is a {@link GlassDrawable},
 * set by Home like the pills') holding an animated condition picture on the left — the sun turns,
 * clouds drift, rain and snow fall inside it, lightning flickers — the temperature on the right,
 * which counts up to a new reading, and the hours and days ahead along the bottom. Everything is
 * drawn on one canvas; the animation runs only while Home is on screen, and never faster than
 * {@link #FRAME_MS}, which this TV's GPU can keep up with. {@code reduced_motion} in the theme
 * keeps the drawing but stops the motion: one static frame of the same picture.
 */
final class WeatherCardView extends View {
    /** One animation frame: 24 fps is smooth enough for slow weather motion and kind to weak GPUs. */
    private static final long FRAME_MS = 42;
    /** The reading now shown; the card is blank (and still) until the first one arrives. */
    private Weather.Data data;
    /** Reduced motion: draw one frame, then stop until the reading changes. */
    private boolean still;
    /** Seconds on the animation clock; set at once when still, so the picture is never blank. */
    private static final float STILL_AT = 2.5f;
    private float t;
    private long lastFrameAt;

    /** The temperature as the count-up animation shows it now. */
    private float shownTemp;
    private ValueAnimator tempCount;
    /** Extra size on the icon while a new reading lands, settling back. */
    private float pulse = 1f;
    private ValueAnimator pulseAnim;

    private final float density = getResources().getDisplayMetrics().density;
    private final float sp = getResources().getDisplayMetrics().scaledDensity;
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WeatherCardView(Context c, AttributeSet a) {
        super(c, a);
        text.setTypeface(ResourcesCompat.getFont(c, R.font.outfit));
        text.setColor(0xE6FFFFFF);
        text.setTextSize(15f * sp);
        big.setTypeface(text.getTypeface());
        big.setColor(0xFFFFFFFF);
        big.setTextSize(30f * sp);
        big.setLetterSpacing(-0.02f);
        hair.setColor(0x1AFFFFFF);
        hair.setStrokeWidth(1f * density);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Whether a reading has ever been shown; Home hides the card until one lands. */
    boolean hasReading() {
        return data != null;
    }

    /** Shows a reading; a changed temperature counts up to its new value, the icon gives a pulse. */
    void setReading(Weather.Data d) {
        if (d == null) return;
        boolean first = data == null;
        boolean changed = first || data.code != d.code
                || Math.round(data.temp) != Math.round(d.temp);
        data = d;
        if (tempCount != null) tempCount.cancel();
        float from = shownTemp != 0 && !first ? shownTemp : (float) d.temp;
        // Counting from a long way off (first show, unit change) reads as noise: jump instead.
        if (!first && Math.abs(from - d.temp) <= 20 && !still) {
            tempCount = ValueAnimator.ofFloat(from, (float) d.temp);
            tempCount.setDuration(700);
            tempCount.setInterpolator(new DecelerateInterpolator());
            tempCount.addUpdateListener(a -> {
                shownTemp = (float) a.getAnimatedValue();
                invalidate();
            });
            tempCount.start();
        } else {
            shownTemp = (float) d.temp;
        }
        if (changed && !first && !still) {
            if (pulseAnim != null) pulseAnim.cancel();
            pulseAnim = ValueAnimator.ofFloat(1.12f, 1f);
            pulseAnim.setDuration(600);
            pulseAnim.setInterpolator(new DecelerateInterpolator());
            pulseAnim.addUpdateListener(a -> {
                pulse = (float) a.getAnimatedValue();
                invalidate();
            });
            pulseAnim.start();
        }
        if (still) {
            t = STILL_AT;
            invalidate();
        } else {
            startFrames();
        }
    }

    /** Calmer motion for weak GPUs: the same picture, without the movement. */
    void setStill(boolean s) {
        still = s;
        if (s) {
            stopFrames();
            t = STILL_AT;
            invalidate();
        } else {
            startFrames();
        }
    }

    /** One loop of the animation clock: advance, draw, and schedule the next frame. */
    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (lastFrameAt != 0) t += Math.min((now - lastFrameAt) / 1000f, 0.25f);
            lastFrameAt = now;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };
    private boolean framesRunning;

    private void startFrames() {
        if (framesRunning || still || data == null) return;
        framesRunning = true;
        lastFrameAt = 0;
        postDelayed(frame, FRAME_MS);
    }

    private void stopFrames() {
        framesRunning = false;
        removeCallbacks(frame);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startFrames();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFrames();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int vis) {
        super.onWindowVisibilityChanged(vis);
        if (vis == VISIBLE) startFrames();
        else stopFrames();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // A fixed band under the clock: icon on the left, the reading to its right, strips below.
        // A narrow card (Home sizes it to three grid cells) stacks the two strips, so it is taller.
        int w = MeasureSpec.getSize(wSpec);
        int want = (int) ((w > 0 && w < 460 * density ? 168f : 132f) * density);
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY
                ? Math.min(want, MeasureSpec.getSize(hSpec)) : want;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (data == null) return;
        float pad = 18 * density;
        float w = getWidth(), h = getHeight();
        // Narrow (Home sizes the card to three grid cells): the header keeps its size and the two
        // strips stack under it, each on its own full-width row instead of sharing one.
        boolean narrow = w < 460 * density;

        // The picture in a square on the left; the temperature and words to its right. The radius
        // also keeps the sun's longest rays inside the band instead of clipping at the top edge.
        float cy = narrow ? 40 * density : h * 0.36f;
        float iconR = Math.min(Math.min(h * 0.30f, 40 * density), cy / 1.7f) * pulse;
        float cx = pad + (narrow ? 38 : 46) * density;
        drawIcon(canvas, data.code, cx, cy, iconR);

        float tx = pad + (narrow ? 78 : 92) * density;
        // The temperature and the H/L share one row, its centre a little above the band's middle so
        // the words get their own line underneath. The condition used to be drawn on a baseline that
        // cut through the middle of the digits, so "Overcast" ran straight through "26°".
        Paint.FontMetrics bigFm = big.getFontMetrics();
        float bigMid = cy - 6 * density;
        float baseline = bigMid - (bigFm.ascent + bigFm.descent) / 2f;
        canvas.drawText(Math.round(shownTemp) + "\u00B0", tx, baseline, big);
        float right = w - pad;
        if (data.hi > data.lo) {
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("H " + Math.round(data.hi) + "\u00B0  L " + Math.round(data.lo) + "\u00B0",
                    right, baseline, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        // The condition and the city sit below the digits' feet, clear of them: the words' own
        // ascent is accounted for, so a 30sp digit's feet cannot land on them. The line is one
        // line, so it shrinks to fit rather than running off the card and being cut mid-word.
        Paint.FontMetrics textFm = text.getFontMetrics();
        float ty = baseline - bigFm.descent + 15 * density - textFm.ascent;
        if (!data.condition.isEmpty()) {
            String words = data.condition + (data.city.isEmpty() ? "" : "  \u00B7  " + data.city);
            float avail = right - tx;
            float size = text.getTextSize();
            float measured = text.measureText(words);
            // Only ever shrink; a short string must keep its size.
            if (measured > avail) text.setTextSize(size * avail / measured);
            canvas.drawText(words, tx, ty, text);
            text.setTextSize(size);
        }

        // The strips along the bottom: the hours first, then the days, one hairline above both —
        // side by side when there is room, one full-width row each when the card is narrow.
        float sy = h - pad;
        float hairY = narrow ? sy - 74 * density : sy - 34 * density;
        canvas.drawLine(pad, hairY, right, hairY, hair);
        text.setTextSize(11f * sp);
        float hoursW = narrow ? (right - pad) : (right - pad) * 0.56f;
        strip(canvas, data.hourLabels, data.hourTemps, data.hourCodes, pad, hoursW, sy - (narrow ? 36 * density : 0));
        strip(canvas, data.dayLabels, data.dayRanges, data.dayCodes, pad + hoursW, right - pad - hoursW, sy);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(15f * sp);
    }

    /** One strip row: each slot is its label, the tiny condition mark, and its number, centred. */
    private void strip(Canvas canvas, String[] labels, String[] values, int[] codes,
                      float left, float rowW, float bottom) {
        float slot = rowW / Math.max(labels.length, 1);
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null || labels[i].isEmpty()) break;
            float sx = left + slot * (i + 0.5f);
            canvas.drawText(labels[i], sx, bottom - 22 * density, text);
            drawMini(canvas, codes[i], sx, bottom - 9 * density, 3.4f * density);
            canvas.drawText(values[i], sx, bottom + 2 * density, text);
        }
    }

    // ---- the picture -----------------------------------------------------------------------

    /** The condition as a small moving picture, by its WMO code. */
    private void drawIcon(Canvas canvas, int code, float cx, float cy, float r) {
        switch (code) {
            case 0:
                sun(canvas, cx, cy, r * 0.5f);
                break;
            case 1:
                sun(canvas, cx - r * 0.15f, cy - r * 0.15f, r * 0.42f);
                cloud(canvas, cx + r * 0.35f, cy + r * 0.4f, r * 0.5f, 0.6f);
                break;
            case 2:
                sun(canvas, cx - r * 0.3f, cy - r * 0.35f, r * 0.36f);
                cloud(canvas, cx + r * 0.1f, cy + r * 0.2f, r * 0.78f, 0.8f);
                break;
            case 3:
                cloud(canvas, cx - r * 0.15f, cy, r * 0.85f, 0.7f);
                cloud(canvas, cx + r * 0.35f, cy + r * 0.35f, r * 0.6f, 0.5f);
                break;
            case 45:
            case 48:
                fog(canvas, cx, cy, r);
                break;
            case 95:
            case 96:
            case 99:
                cloud(canvas, cx, cy - r * 0.3f, r * 0.85f, 0.75f);
                drops(canvas, cx, cy + r * 0.15f, r * 0.7f, r * 0.9f, 4, 1.1f);
                bolt(canvas, cx + r * 0.05f, cy + r * 0.1f, r * 0.6f);
                break;
            case 51: case 53: case 55: case 56: case 57:
                cloud(canvas, cx, cy - r * 0.3f, r * 0.85f, 0.75f);
                drops(canvas, cx, cy + r * 0.15f, r * 0.7f, r * 0.9f, 4, 0.5f);
                break;
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82:
                cloud(canvas, cx, cy - r * 0.3f, r * 0.85f, 0.75f);
                drops(canvas, cx, cy + r * 0.15f, r * 0.7f, r * 0.95f, 6, 1f);
                break;
            case 71: case 73: case 75: case 77: case 85: case 86:
                cloud(canvas, cx, cy - r * 0.3f, r * 0.85f, 0.75f);
                flakes(canvas, cx, cy + r * 0.15f, r * 0.7f, r * 0.9f, 5);
                break;
            default:
                cloud(canvas, cx, cy, r * 0.8f, 0.6f);
        }
    }

    /** A turning sun: a disc with rays that breathe as they rotate. */
    private void sun(Canvas canvas, float cx, float cy, float r) {
        float spin = (float) Math.toRadians(t * 14);
        stroke.setStrokeWidth(2.6f * density);
        stroke.setColor(0xB3FFD98E);
        for (int i = 0; i < 8; i++) {
            float a = spin + i * (float) (Math.PI / 4);
            float len = r * (1.5f + 0.16f * (float) Math.sin(t * 1.7 + i));
            canvas.drawLine(cx + (float) Math.cos(a) * r * 1.15f, cy + (float) Math.sin(a) * r * 1.15f,
                    cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, stroke);
        }
        fill.setColor(0xF2FFCE7C);
        canvas.drawCircle(cx, cy, r, fill);
    }

    /** A soft cloud of three lobes, drifting slowly side to side. */
    private void cloud(Canvas canvas, float cx, float cy, float r, float alpha) {
        cx += (float) Math.sin(t * 0.5) * r * 0.1f;
        fill.setColor(((int) (0x8C * alpha)) << 24 | 0xFFFFFF);
        canvas.drawCircle(cx - r * 0.42f, cy + r * 0.08f, r * 0.5f, fill);
        canvas.drawCircle(cx + r * 0.15f, cy - r * 0.18f, r * 0.6f, fill);
        canvas.drawCircle(cx + r * 0.6f, cy + r * 0.12f, r * 0.4f, fill);
        rect.set(cx - r * 0.85f, cy, cx + r * 0.9f, cy + r * 0.48f);
        canvas.drawRoundRect(rect, r * 0.4f, r * 0.4f, fill);
    }

    /** Fog: bars of mist sliding at different speeds. */
    private void fog(Canvas canvas, float cx, float cy, float r) {
        cloud(canvas, cx, cy - r * 0.35f, r * 0.8f, 0.5f);
        fill.setColor(0x99FFFFFF);
        for (int i = 0; i < 3; i++) {
            float y = cy + r * 0.15f + i * r * 0.42f;
            float off = ((t * (16 + i * 8) + i * 41) % (r * 2.6f)) - r * 1.3f;
            rect.set(cx - r * 0.85f + off * 0.35f, y - 2.2f * density,
                    cx + r * 0.85f + off * 0.35f, y + 2.2f * density);
            canvas.drawRoundRect(rect, 3 * density, 3 * density, fill);
        }
    }

    /** Rain strokes falling through the box, looping from the top; heavier falls faster. */
    private void drops(Canvas canvas, float cx, float cy, float halfW, float fall, int n, float weight) {
        fill.setColor(0x99A8D4FF);
        float speed = 240 * weight;
        float len = (10 + 8 * weight) * density;
        for (int i = 0; i < n; i++) {
            float lane = cx - halfW + 2f * halfW * ((i + 0.5f) / n);
            float y = cy + ((t * speed + i * 61) % (fall + len)) - len;
            rect.set(lane - 1.2f * density, y, lane + 1.2f * density, y + len);
            canvas.drawRoundRect(rect, 1.2f * density, 1.2f * density, fill);
        }
    }

    /** Snowflakes tumbling down, each with its own sway. */
    private void flakes(Canvas canvas, float cx, float cy, float halfW, float fall, int n) {
        fill.setColor(0xCCDDF0FF);
        for (int i = 0; i < n; i++) {
            float lane = cx - halfW + 2f * halfW * ((i + 0.5f) / n);
            float y = cy + ((t * 55 + i * 77) % (fall + 8 * density));
            float sway = (float) Math.sin(t * 1.3 + i * 2.1) * 5 * density;
            canvas.drawCircle(lane + sway, y, 2.2f * density, fill);
        }
    }

    /** A lightning bolt, shown only while the flicker is on. */
    private void bolt(Canvas canvas, float cx, float cy, float r) {
        if (Math.sin(t * 2.7) <= 0.7) return;
        path.reset();
        path.moveTo(cx, cy - r * 0.9f);
        path.lineTo(cx - r * 0.42f, cy + r * 0.1f);
        path.lineTo(cx - r * 0.08f, cy + r * 0.04f);
        path.lineTo(cx - r * 0.34f, cy + r * 0.85f);
        path.lineTo(cx + 0.28f * r, cy - 0.08f * r);
        path.lineTo(cx - r * 0.02f, cy - r * 0.02f);
        path.close();
        fill.setColor(0xFFFFE9A8);
        canvas.drawPath(path, fill);
    }

    /** The tiny condition mark under a strip label. */
    private void drawMini(Canvas canvas, int code, float cx, float cy, float r) {
        switch (code) {
            case 0:
            case 1:
                fill.setColor(0xFFD98E);
                canvas.drawCircle(cx, cy, r, fill);
                break;
            case 45:
            case 48:
                fill.setColor(0x99FFFFFF);
                rect.set(cx - r, cy - 1.4f * density, cx + r, cy + 1.4f * density);
                canvas.drawRoundRect(rect, 2 * density, 2 * density, fill);
                break;
            case 71: case 73: case 75: case 77: case 85: case 86:
                fill.setColor(0xCCDDF0FF);
                canvas.drawCircle(cx, cy, r * 0.85f, fill);
                break;
            case -1:
                break;
            default:    // clouds, rain, storm: a small grey cloud
                fill.setColor(0xB3FFFFFF);
                canvas.drawCircle(cx - r * 0.25f, cy - r * 0.1f, r * 0.7f, fill);
                canvas.drawCircle(cx + r * 0.35f, cy, r * 0.55f, fill);
        }
    }
}
