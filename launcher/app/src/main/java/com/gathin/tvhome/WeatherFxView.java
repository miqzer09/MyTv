package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.Random;

/**
 * Weather across the whole screen: rain streaks over the wallpaper, snow drifting down, and —
 * during a thunderstorm — a brief flash of lightning before the rain intensifies. Home draws it
 * behind the tiles and under the glass, so it lives in the weather and never covers the apps.
 * Runs at {@link #FRAME_MS} like the card, only while Home is on screen; the particle count is
 * fixed at construction, so a frame costs the same however long it has been falling.
 */
final class WeatherFxView extends View {
    /** 24 fps, matching WeatherCardView: smooth for falling weather, kind to this TV's GPU. */
    private static final long FRAME_MS = 42;
    private static final float FRAME_S = FRAME_MS / 1000f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density = getResources().getDisplayMetrics().density;

    /** What is falling now; FX_NONE hides the view and stops the frames. */
    private int fx = Weather.FX_NONE;
    /** Particle positions and speeds, regenerated when the effect changes. */
    private float[] px, py, pv, plen;
    private int count;
    private final Random rnd = new Random();

    private float t;
    private long lastFrameAt;
    /** Thunder only: seconds until the next flash, and how long the flash has been up. */
    private float untilFlash = 6f, flashUp = -1f;

    public WeatherFxView(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
    }

    /**
     * Switches the weather on screen; FX_NONE hides everything. {@code reduced}, from the theme's
     * reduced_motion, halves the fall: the same weather, at a cost this TV's GPU barely feels.
     */
    void setEffect(int effect, boolean reduced) {
        if (effect == fx) return;
        fx = effect;
        if (fx == Weather.FX_NONE) {
            stopFrames();
            setVisibility(GONE);
            return;
        }
        count = Math.max(40, (int) ((fx == Weather.FX_SNOW ? 90 : 140) * (reduced ? 0.5f : 1f)));
        px = new float[count];
        py = new float[count];
        pv = new float[count];
        plen = new float[count];
        for (int i = 0; i < count; i++) seed(i, true);
        untilFlash = 4 + rnd.nextFloat() * 8;
        flashUp = -1f;
        setVisibility(VISIBLE);
        startFrames();
    }

    /** One particle, dropped in from above the screen (or anywhere, on the first fill). */
    private void seed(int i, boolean anywhere) {
        float w = Math.max(getWidth(), 1), h = Math.max(getHeight(), 1);
        px[i] = rnd.nextFloat() * w * 1.2f - w * 0.1f;
        py[i] = anywhere ? rnd.nextFloat() * h : -plen[i] - rnd.nextFloat() * h * 0.15f;
        if (fx == Weather.FX_SNOW) {
            pv[i] = 26 + rnd.nextFloat() * 34;          // snow: slow
            plen[i] = (1.4f + rnd.nextFloat() * 2.2f) * density;
        } else {
            pv[i] = 430 + rnd.nextFloat() * 340;        // rain: fast, with a slant
            plen[i] = (14 + rnd.nextFloat() * 22) * density;
        }
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameAt == 0 ? FRAME_S / 1000f
                    : Math.min((now - lastFrameAt) / 1000f, 0.1f);
            lastFrameAt = now;
            step(dt);
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };
    private boolean framesRunning;

    private void startFrames() {
        if (framesRunning || fx == Weather.FX_NONE) return;
        framesRunning = true;
        lastFrameAt = 0;
        postDelayed(frame, FRAME_MS);
    }

    private void stopFrames() {
        framesRunning = false;
        removeCallbacks(frame);
    }

    /** Moves the particles; rain gets a gust and snow a sway, and thunder counts down to flash. */
    private void step(float dt) {
        t += dt;
        float h = getHeight(), w = getWidth();
        if (h == 0 || px == null) return;
        float gust = fx == Weather.FX_SNOW ? (float) Math.sin(t * 0.5) * 12f
                : 60f + (float) Math.sin(t * 0.9) * 40f;
        for (int i = 0; i < count; i++) {
            py[i] += pv[i] * dt;
            px[i] += gust * dt * (fx == Weather.FX_SNOW ? (0.5f + (i % 5) * 0.2f) : 1f);
            if (py[i] > h + plen[i] || px[i] > w + 40) seed(i, false);
        }
        if (fx == Weather.FX_STORM) {
            if (flashUp >= 0) {
                flashUp += dt;
                if (flashUp > 0.35f) flashUp = -1f;
            } else {
                untilFlash -= dt;
                if (untilFlash <= 0) {
                    flashUp = 0;
                    untilFlash = 5 + rnd.nextFloat() * 9;
                }
            }
        }
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
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (fx == Weather.FX_NONE || px == null) return;
        float w = getWidth(), h = getHeight();
        if (fx == Weather.FX_SNOW) {
            paint.setColor(0xB3FFFFFF);
            for (int i = 0; i < count; i++) {
                float sway = (float) Math.sin(t * 1.1f + i * 1.7f) * 6f * density;
                canvas.drawCircle(px[i] + sway, py[i], plen[i], paint);
            }
        } else {
            float gust = 60f + (float) Math.sin(t * 0.9f) * 40f;
            float slant = gust / Math.max(pv[0], 1f) * plen[0] * 2.2f;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(1.4f * density);
            // Storm rain falls a little harder while the flash is up.
            int alpha = fx == Weather.FX_STORM && flashUp >= 0 ? 0x7A : 0x4D;
            paint.setColor(alpha << 24 | 0xC9E0FF);
            for (int i = 0; i < count; i++) {
                canvas.drawLine(px[i], py[i], px[i] - slant, py[i] + plen[i], paint);
            }
        }
        // The lightning: a wash of light over the screen, brightest at the start of the flash.
        if (fx == Weather.FX_STORM && flashUp >= 0) {
            float f = 1f - flashUp / 0.35f;
            float a = f * f * 0.22f;
            if (a > 0.004f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(((int) (a * 255)) << 24 | 0xFFFFFF);
                canvas.drawRect(0, 0, w, h, paint);
            }
        }
    }
}
