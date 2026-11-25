package nl.johninnl.soundcloud;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;

import java.util.WeakHashMap;

/**
 * Custom iOS 27 Liquid Glass controls (Switch, Slider), verified VietQR renderer
 * (VietinBank 970415 / STK 0989249990), and clean non-overlapping Global LiquidGlass styler.
 */
@SuppressLint("NewApi")
public final class LiquidGlassControls {

    private LiquidGlassControls() {}

    // Verified ISO/IEC 18004 Version 5-L (37x37) QR matrix for VietQR VietinBank STK 0989249990
    private static final String[] VIETQR_MATRIX = new String[]{
            "1111111011000010000011011111001111111",
            "1000001011011000000010101001001000001",
            "1011101001010000001111011011001011101",
            "1011101000011000111010011100001011101",
            "1011101011011000110110010010001011101",
            "1000001011110010000010011010001000001",
            "1111111010101010101010101010101111111",
            "0000000010101000001101000111000000000",
            "1110111111010101110001100100011010010",
            "1011000011010101000010010101110110100",
            "0010101111101010101001000111110100010",
            "0101000111001000000110000001111100000",
            "1111111001011111101001111110011010101",
            "0110010101000100110100100101100101000",
            "0000101011100100001100100111010100110",
            "1111000110011010001010100110100100010",
            "1011001000110011100010110101101110110",
            "1101100011000111101001011101111001111",
            "0011111101100100110100011000101001011",
            "1001110111111011110001100101001001000",
            "0110011100101110010001111011000111110",
            "1110010101100010111101010010010100110",
            "0100011100000111010000001011110000001",
            "1001010111001000111001000000110101100",
            "1101101010111101000101101111011001010",
            "0010100000011010000110101111011101001",
            "1101001011000010101001111101001011110",
            "1100110001011100111100010101001110001",
            "1000101110011111111111000011111110011",
            "0000000011111000100000010010100010110",
            "1111111001000110010000101100101010110",
            "1000001010011000110111100011100011010",
            "1011101001011111010110010111111110000",
            "1011101011100111100000000101010100100",
            "1011101001000000010001101101010110001",
            "1000001011010011110010011001100101000",
            "1111111010101000101010111100111001000"
    };

    /** Crisp offline VietQR view scannable by all Vietnamese banking apps. */
    public static final class VietQrView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint modulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        public VietQrView(Context ctx) {
            super(ctx);
            this.density = ctx.getResources().getDisplayMetrics().density;
            bgPaint.setColor(Color.WHITE);
            modulePaint.setColor(Color.rgb(12, 18, 32));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = Math.round(220f * density);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int size = Math.min(w, h);
            if (size <= 0) return;

            float corner = 18f * density;
            rect.set(0f, 0f, size, size);
            canvas.drawRoundRect(rect, corner, corner, bgPaint);

            int n = VIETQR_MATRIX.length;
            float quiet = 14f * density;
            float cell = (size - quiet * 2f) / n;
            float rad = cell * 0.22f;

            for (int r = 0; r < n; r++) {
                String row = VIETQR_MATRIX[r];
                for (int c = 0; c < n; c++) {
                    if (row.charAt(c) == '1') {
                        float x = quiet + c * cell;
                        float y = quiet + r * cell;
                        rect.set(x, y, x + cell + 0.4f, y + cell + 0.4f);
                        canvas.drawRoundRect(rect, rad, rad, modulePaint);
                    }
                }
            }
        }
    }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean isChecked);
    }

    /**
     * iOS 27 Liquid Glass Switch:
     * Frosted translucent capsule track + crystalline glass thumb that stretches & refracts on press/drag.
     */
    public static final class Ios27GlassSwitch extends View implements Choreographer.FrameCallback {
        private final float density;
        private boolean checked;
        private final OnCheckedChangeListener listener;

        private float pos = 0f;
        private float posVel = 0f;
        private float press = 0f;
        private float pressTarget = 0f;
        private float pressVel = 0f;
        private boolean dragging = false;
        private float downX = 0f;
        private boolean frameScheduled = false;
        private long lastFrameNs = 0L;

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF thumbRect = new RectF();

        public Ios27GlassSwitch(Context ctx, boolean initial, OnCheckedChangeListener listener) {
            super(ctx);
            this.density = ctx.getResources().getDisplayMetrics().density;
            this.checked = initial;
            this.pos = initial ? 1f : 0f;
            this.listener = listener;
            setClickable(true);
        }

        public void setChecked(boolean value, boolean notify) {
            if (this.checked != value) {
                this.checked = value;
                schedule();
                if (notify && listener != null) listener.onCheckedChanged(value);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(Math.round(58f * density), Math.round(32f * density));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    downX = event.getX();
                    dragging = false;
                    pressTarget = 1f;
                    schedule();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    if (Math.abs(dx) > 6f * density) dragging = true;
                    if (dragging) {
                        float w = Math.max(1f, getWidth() - 30f * density);
                        pos = Math.max(0f, Math.min(1f, (event.getX() - 15f * density) / w));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressTarget = 0f;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        boolean newState = dragging ? (pos >= 0.5f) : !checked;
                        setChecked(newState, true);
                    }
                    dragging = false;
                    schedule();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        private void schedule() {
            if (!frameScheduled && isAttachedToWindow()) {
                frameScheduled = true;
                lastFrameNs = SystemClock.elapsedRealtimeNanos();
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            float dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
            lastFrameNs = frameTimeNanos;
            if (dt <= 0f || dt > 0.05f) dt = 0.016f;

            boolean moving = false;
            if (!dragging) {
                float target = checked ? 1f : 0f;
                float c = 2f * 0.72f * (float) Math.sqrt(420f);
                float a = -420f * (pos - target) - c * posVel;
                posVel += a * dt;
                pos = Math.max(0f, Math.min(1f, pos + posVel * dt));
                if (Math.abs(pos - target) > 0.002f || Math.abs(posVel) > 0.005f) moving = true;
            }
            float c2 = 2f * 0.85f * (float) Math.sqrt(520f);
            float a2 = -520f * (press - pressTarget) - c2 * pressVel;
            pressVel += a2 * dt;
            press = Math.max(0f, Math.min(1f, press + pressVel * dt));
            if (Math.abs(press - pressTarget) > 0.002f || Math.abs(pressVel) > 0.005f) moving = true;

            invalidate();
            if (moving) schedule();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            float pad = 1.5f * density;
            trackRect.set(pad, pad, w - pad, h - pad);
            float tr = trackRect.height() * 0.5f;

            int r = Math.round(52 + (255 - 52) * pos);
            int g = Math.round(52 + (92 - 52) * pos);
            int b = Math.round(60 + (24 - 60) * pos);
            trackPaint.setColor(Color.argb(Math.round(170 + 75 * pos), r, g, b));
            canvas.drawRoundRect(trackRect, tr, tr, trackPaint);

            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(1f * density);
            rimPaint.setShader(new LinearGradient(0f, 0f, 0f, h,
                    Color.argb(150, 255, 255, 255),
                    Color.argb(40, 255, 255, 255),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(trackRect, tr, tr, rimPaint);
            rimPaint.setShader(null);

            float inset = 3f * density;
            float thumbH = trackRect.height() - inset * 2f;
            float thumbW = thumbH * 1.28f + 8f * density * press;
            float travel = Math.max(0f, trackRect.width() - inset * 2f - thumbW);
            float tx = trackRect.left + inset + travel * pos;
            float ty = trackRect.top + inset;
            thumbRect.set(tx, ty, tx + thumbW, ty + thumbH);
            float thr = thumbH * 0.5f;

            thumbPaint.setColor(Color.argb(Math.round(245 - 105 * press), 255, 255, 255));
            thumbPaint.setShadowLayer(4f * density, 0f, 1.5f * density, Color.argb(70, 0, 0, 0));
            canvas.drawRoundRect(thumbRect, thr, thr, thumbPaint);
            thumbPaint.clearShadowLayer();

            glossPaint.setStyle(Paint.Style.STROKE);
            glossPaint.setStrokeWidth((1f + 0.8f * press) * density);
            glossPaint.setShader(new LinearGradient(thumbRect.left, thumbRect.top, thumbRect.right, thumbRect.bottom,
                    Color.argb(235, 255, 255, 255),
                    Color.argb(Math.round(80 + 110 * press), 190, 225, 255),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(thumbRect, thr, thr, glossPaint);
            glossPaint.setShader(null);
        }
    }

    public interface OnSliderChangeListener {
        void onValueChanged(float value);
    }

    /**
     * iOS 27 Liquid Glass Slider:
     * Frosted capsule track + glowing fill + crystalline pill thumb that expands & turns translucent on drag.
     */
    public static final class Ios27GlassSlider extends View implements Choreographer.FrameCallback {
        private final float density;
        private final float minVal;
        private final float maxVal;
        private float value;
        private final OnSliderChangeListener listener;

        private float press = 0f;
        private float pressTarget = 0f;
        private float pressVel = 0f;
        private boolean frameScheduled = false;
        private long lastFrameNs = 0L;

        private final Paint trackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF fillRect = new RectF();
        private final RectF thumbRect = new RectF();

        public Ios27GlassSlider(Context ctx, float min, float max, float initial, OnSliderChangeListener listener) {
            super(ctx);
            this.density = ctx.getResources().getDisplayMetrics().density;
            this.minVal = min;
            this.maxVal = max;
            this.value = Math.max(min, Math.min(max, initial));
            this.listener = listener;
        }

        public void setValue(float v) {
            this.value = Math.max(minVal, Math.min(maxVal, v));
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, Math.round(38f * density));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float padH = 18f * density;
            float usable = Math.max(1f, getWidth() - padH * 2f);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    pressTarget = 1f;
                    updateFromX(event.getX(), padH, usable);
                    schedule();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateFromX(event.getX(), padH, usable);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressTarget = 0f;
                    schedule();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        private void updateFromX(float x, float padH, float usable) {
            float frac = Math.max(0f, Math.min(1f, (x - padH) / usable));
            float newVal = minVal + (maxVal - minVal) * frac;
            if (Math.abs(newVal - value) > 0.0001f) {
                value = newVal;
                if (listener != null) listener.onValueChanged(value);
                invalidate();
            }
        }

        private void schedule() {
            if (!frameScheduled && isAttachedToWindow()) {
                frameScheduled = true;
                lastFrameNs = SystemClock.elapsedRealtimeNanos();
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            float dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
            lastFrameNs = frameTimeNanos;
            if (dt <= 0f || dt > 0.05f) dt = 0.016f;

            float c = 2f * 0.8f * (float) Math.sqrt(480f);
            float a = -480f * (press - pressTarget) - c * pressVel;
            pressVel += a * dt;
            press = Math.max(0f, Math.min(1f, press + pressVel * dt));
            invalidate();
            if (Math.abs(press - pressTarget) > 0.002f || Math.abs(pressVel) > 0.005f) {
                schedule();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            float padH = 18f * density;
            float trackH = (7f + 3f * press) * density;
            float cy = h * 0.5f;
            float frac = (maxVal > minVal) ? ((value - minVal) / (maxVal - minVal)) : 0f;

            trackRect.set(padH, cy - trackH * 0.5f, w - padH, cy + trackH * 0.5f);
            float tr = trackH * 0.5f;

            trackBgPaint.setColor(Color.argb(65, 255, 255, 255));
            canvas.drawRoundRect(trackRect, tr, tr, trackBgPaint);

            float fillRight = trackRect.left + trackRect.width() * frac;
            fillRect.set(trackRect.left, trackRect.top, Math.max(trackRect.left + trackH, fillRight), trackRect.bottom);
            trackFillPaint.setShader(new LinearGradient(fillRect.left, 0f, fillRect.right, 0f,
                    Color.rgb(255, 92, 24), Color.rgb(255, 140, 60), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(fillRect, tr, tr, trackFillPaint);
            trackFillPaint.setShader(null);

            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(0.9f * density);
            rimPaint.setColor(Color.argb(90, 255, 255, 255));
            canvas.drawRoundRect(trackRect, tr, tr, rimPaint);

            float thumbW = (28f + 10f * press) * density;
            float thumbH = (19f + 7f * press) * density;
            float cx = trackRect.left + trackRect.width() * frac;
            thumbRect.set(cx - thumbW * 0.5f, cy - thumbH * 0.5f, cx + thumbW * 0.5f, cy + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            thumbPaint.setColor(Color.argb(Math.round(240 - 115 * press), 255, 255, 255));
            thumbPaint.setShadowLayer(6f * density, 0f, 2f * density, Color.argb(85, 0, 0, 0));
            canvas.drawRoundRect(thumbRect, thr, thr, thumbPaint);
            thumbPaint.clearShadowLayer();

            rimPaint.setStrokeWidth((1.1f + 0.7f * press) * density);
            rimPaint.setShader(new LinearGradient(thumbRect.left, thumbRect.top, thumbRect.right, thumbRect.bottom,
                    Color.argb(245, 255, 255, 255),
                    Color.argb(Math.round(90 + 110 * press), 170, 220, 255),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(thumbRect, thr, thr, rimPaint);
            rimPaint.setShader(null);
        }
    }

    /**
     * Global Liquid Glass UI transformer ("Bật LiquidGlass"):
     * Replaces backgrounds IN-PLACE (never double-layering overlapping boxes on search bars + back icons!)
     * with translucent frosted glass + top-to-bottom specular rim. Restores cleanly when disabled.
     */
    public static final class GlobalLiquidGlassStyler {
        private static final WeakHashMap<View, Drawable> ORIGINAL_BG = new WeakHashMap<>();
        private static long lastScanMs = 0L;

        public static void applyIfEnabled(View root, View excludeTabBar, View excludeMiniPill, View excludeAuthorPage, boolean enabled) {
            if (root == null) return;
            if (!enabled) {
                restoreAll();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastScanMs < 500L) return;
            lastScanMs = now;
            float density = root.getResources().getDisplayMetrics().density;
            boolean dark = (root.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            walk(root, excludeTabBar, excludeMiniPill, excludeAuthorPage, density, dark, 0);
        }

        public static void restoreAll() {
            if (ORIGINAL_BG.isEmpty()) return;
            for (View v : new java.util.ArrayList<>(ORIGINAL_BG.keySet())) {
                if (v != null) {
                    v.setBackground(ORIGINAL_BG.get(v));
                }
            }
            ORIGINAL_BG.clear();
        }

        private static boolean hasStyledAncestor(View v) {
            ViewParent p = v.getParent();
            int depth = 0;
            while (p instanceof View && depth < 3) {
                if (ORIGINAL_BG.containsKey(p)) return true;
                p = p.getParent();
                depth++;
            }
            return false;
        }

        private static void applyGlassSurface(View target, float density, boolean dark, boolean strong) {
            if (ORIGINAL_BG.containsKey(target)) return;
            ORIGINAL_BG.put(target, target.getBackground());
            GradientDrawable gd = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    dark
                            ? new int[]{Color.argb(strong ? 115 : 82, 52, 54, 66), Color.argb(strong ? 95 : 64, 24, 24, 30)}
                            : new int[]{Color.argb(140, 255, 255, 255), Color.argb(105, 232, 236, 244)}
            );
            gd.setCornerRadius(Math.min(target.getWidth(), target.getHeight()) * 0.5f);
            gd.setStroke(Math.max(1, Math.round(1f * density)),
                    dark ? Color.argb(120, 255, 255, 255) : Color.argb(150, 255, 255, 255));
            target.setBackground(gd);
        }

        private static void walk(View v, View ex1, View ex2, View ex3, float density, boolean dark, int depth) {
            if (v == null || v == ex1 || v == ex2 || v == ex3 || depth > 14 || v.getVisibility() != View.VISIBLE) return;

            if (!ORIGINAL_BG.containsKey(v) && !hasStyledAncestor(v) && v.getWidth() > 0 && v.getHeight() > 0) {
                if (v instanceof EditText) {
                    // Style the search bar's parent container cleanly instead of overlapping inner EditText + back icon
                    ViewParent parent = v.getParent();
                    if (parent instanceof ViewGroup) {
                        ViewGroup pg = (ViewGroup) parent;
                        if (pg.getHeight() >= Math.round(34f * density) && pg.getHeight() <= Math.round(72f * density)) {
                            applyGlassSurface(pg, density, dark, true);
                            v.setBackgroundColor(Color.TRANSPARENT);
                            return;
                        }
                    }
                    applyGlassSurface(v, density, dark, true);
                } else if (v instanceof Button && !(v instanceof CompoundButton)) {
                    applyGlassSurface(v, density, dark, true);
                } else if (v instanceof CompoundButton) {
                    applyGlassSurface(v, density, dark, false);
                } else if (v instanceof ImageButton) {
                    int w = v.getWidth();
                    int h = v.getHeight();
                    if (w >= Math.round(32f * density) && w <= Math.round(60f * density)
                            && h >= Math.round(32f * density) && h <= Math.round(60f * density)) {
                        applyGlassSurface(v, density, dark, false);
                    }
                }
            }

            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                int count = vg.getChildCount();
                for (int i = 0; i < count; i++) {
                    walk(vg.getChildAt(i), ex1, ex2, ex3, density, dark, depth + 1);
                }
            }
        }
    }
}
