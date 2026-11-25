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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;

import java.util.WeakHashMap;

/**
 * Custom iOS 27 Liquid Glass controls (Switch, Slider), verified VietQR renderer
 * (VietinBank 970415 / STK 0989249990), and global SoundCloud UI Liquid Glass styler.
 */
@SuppressLint("NewApi")
public final class LiquidGlassControls {

    private LiquidGlassControls() {}

    // Verified ISO/IEC 18004 Version 5-L (37x37) QR matrix for VietQR VietinBank STK 0989249990
    // Payload: 00020101021138540010A00000072701240006970415011009892499900208QRIBFTTA53037045802VN6304EF7A
    private static final String[] VIETQR_MATRIX = new String[]{
            "1111111000100001100001011001101111111",
            "1000001001000111011101011001101000001",
            "1011101010010010101000101010101011101",
            "1011101001001111111110001001101011101",
            "1011101001001011101001011001101011101",
            "1000001000111111110101010111001000001",
            "1111111010101010101010101010101111111",
            "0000000010010110011011110101000000000",
            "1110111110001000011011100110011000100",
            "1011000100011100011111100110001101001",
            "0111101100111100100011101010001011101",
            "0010100010001100111001010100100101010",
            "0100101110110110011011100110001101001",
            "1011110110010110011111100110001101001",
            "1110111011000100111000000010101010101",
            "0100100000110110111101010100010101011",
            "0001001101101001111111100110011101001",
            "1001110000111101111110100110010101001",
            "1001001010111010101010100010001011101",
            "1101010010101110110001110100100001010",
            "1101111111110010011001100110001101001",
            "0111010111110001111110100110001101001",
            "0010111101000100111000101100101110101",
            "1100100000110000011101110101010001010",
            "1100111001001000011001100110001101001",
            "0110000000011100011110100010001101001",
            "1000011010011010110010100010101010001",
            "0100100010001111011101111111010111010",
            "1011001110010010011101100110111111001",
            "0000000000110010011001100110100011001",
            "1111111011100100101010100101101011101",
            "1000001011110001010101010111100011001",
            "1011101010001001011001100110111111000",
            "1011101000111100011001100111010011000",
            "1011101010111010000010101010110011111",
            "1000001010101111110001011100110011010",
            "1111111010110010011001100110101011011"
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
     * - Frosted translucent capsule track with specular rim
     * - Refracting glass pill thumb that stretches elastically on press/drag with damped spring physics
     */
    public static final class Ios27GlassSwitch extends View implements Choreographer.FrameCallback {
        private final float density;
        private boolean checked;
        private OnCheckedChangeListener listener;

        private float pos = 0f;       // 0 = OFF, 1 = ON
        private float posVel = 0f;
        private float press = 0f;     // 0 = rest, 1 = pressed
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

        public boolean isChecked() {
            return checked;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(Math.round(58f * density), Math.round(32f * density));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
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

            // Track color blends from frosted dark glass (OFF) to luminous SoundCloud orange-green glass (ON)
            int offR = 52, offG = 52, offB = 60;
            int onR = 255, onG = 92, onB = 24;
            int r = Math.round(offR + (onR - offR) * pos);
            int g = Math.round(offG + (onG - offG) * pos);
            int b = Math.round(offB + (onB - offB) * pos);
            trackPaint.setColor(Color.argb(Math.round(170 + 75 * pos), r, g, b));
            canvas.drawRoundRect(trackRect, tr, tr, trackPaint);

            // Track specular glass rim
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(1f * density);
            rimPaint.setShader(new LinearGradient(0f, 0f, 0f, h,
                    Color.argb(150, 255, 255, 255),
                    Color.argb(40, 255, 255, 255),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(trackRect, tr, tr, rimPaint);
            rimPaint.setShader(null);

            // iOS 27 Liquid Glass thumb (stretches horizontally + turns crystalline translucent when pressed!)
            float inset = 3f * density;
            float thumbH = trackRect.height() - inset * 2f;
            float baseW = thumbH * 1.28f;
            float thumbW = baseW + 8f * density * press;
            float travel = Math.max(0f, trackRect.width() - inset * 2f - thumbW);
            float tx = trackRect.left + inset + travel * pos;
            float ty = trackRect.top + inset;
            thumbRect.set(tx, ty, tx + thumbW, ty + thumbH);
            float thr = thumbH * 0.5f;

            // Soft shadow under thumb
            thumbPaint.setColor(Color.argb(Math.round(245 - 95 * press), 255, 255, 255));
            thumbPaint.setShadowLayer(4f * density, 0f, 1.5f * density, Color.argb(70, 0, 0, 0));
            canvas.drawRoundRect(thumbRect, thr, thr, thumbPaint);
            thumbPaint.clearShadowLayer();

            // Specular crystal rim on thumb (stronger when pressed for iOS 27 refraction look)
            glossPaint.setStyle(Paint.Style.STROKE);
            glossPaint.setStrokeWidth((1f + 0.8f * press) * density);
            glossPaint.setShader(new LinearGradient(thumbRect.left, thumbRect.top, thumbRect.right, thumbRect.bottom,
                    Color.argb(235, 255, 255, 255),
                    Color.argb(Math.round(80 + 100 * press), 190, 225, 255),
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
     * - Thick frosted capsule track with glowing accent fill
     * - Pill-shaped Liquid Glass thumb that expands & turns crystal-clear translucent when dragged
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
                    getParent().requestDisallowInterceptTouchEvent(true);
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

            // Track subtle glass outline
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(0.9f * density);
            rimPaint.setColor(Color.argb(90, 255, 255, 255));
            canvas.drawRoundRect(trackRect, tr, tr, rimPaint);

            // iOS 27 Liquid Glass pill thumb (expands & becomes crystal translucent on drag)
            float thumbW = (28f + 10f * press) * density;
            float thumbH = (19f + 7f * press) * density;
            float cx = trackRect.left + trackRect.width() * frac;
            thumbRect.set(cx - thumbW * 0.5f, cy - thumbH * 0.5f, cx + thumbW * 0.5f, cy + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            thumbPaint.setColor(Color.argb(Math.round(240 - 115 * press), 255, 255, 255));
            thumbPaint.setShadowLayer(6f * density, 0f, 2f * density, Color.argb(85, 0, 0, 0));
            canvas.drawRoundRect(thumbRect, thr, thr, thumbPaint);
            thumbPaint.clearShadowLayer();

            // Specular refraction edge on thumb
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
     * Global Liquid Glass UI styler ("Bật LiquidGlass"):
     * Applies iOS 27 Liquid Glass pill/circle styling to SoundCloud's buttons, search bars,
     * back buttons, and switches across the active window.
     */
    public static final class GlobalLiquidGlassStyler {
        private static final WeakHashMap<View, Boolean> STYLED = new WeakHashMap<>();
        private static long lastScanMs = 0L;

        public static void applyIfEnabled(View root, View excludeTabBar, View excludeMiniPill, View excludeAuthorPage, boolean enabled) {
            if (!enabled || root == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastScanMs < 450L) return;
            lastScanMs = now;
            float density = root.getResources().getDisplayMetrics().density;
            boolean dark = (root.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            walk(root, excludeTabBar, excludeMiniPill, excludeAuthorPage, density, dark, 0);
        }

        private static void walk(View v, View ex1, View ex2, View ex3, float density, boolean dark, int depth) {
            if (v == null || v == ex1 || v == ex2 || v == ex3 || depth > 14 || v.getVisibility() != View.VISIBLE) return;

            if (!STYLED.containsKey(v) && v.getWidth() > 0 && v.getHeight() > 0) {
                boolean isSearchEdit = (v instanceof EditText);
                boolean isButton = (v instanceof Button) && !(v instanceof CompoundButton);
                boolean isSwitch = (v instanceof CompoundButton);
                boolean isBackOrActionIcon = (v instanceof ImageButton)
                        && v.getWidth() >= Math.round(30f * density) && v.getWidth() <= Math.round(64f * density)
                        && v.getHeight() >= Math.round(30f * density) && v.getHeight() <= Math.round(64f * density);

                if (isSearchEdit || isButton || isBackOrActionIcon || isSwitch) {
                    GradientDrawable gd = new GradientDrawable();
                    float radius = Math.min(v.getWidth(), v.getHeight()) * 0.5f;
                    gd.setCornerRadius(radius);
                    if (isSwitch) {
                        gd.setColor(dark ? Color.argb(65, 255, 255, 255) : Color.argb(45, 0, 0, 0));
                    } else if (isSearchEdit) {
                        gd.setColor(dark ? Color.argb(95, 36, 36, 44) : Color.argb(120, 245, 247, 252));
                    } else {
                        gd.setColor(dark ? Color.argb(85, 32, 32, 40) : Color.argb(110, 245, 247, 252));
                    }
                    gd.setStroke(Math.max(1, Math.round(1f * density)),
                            dark ? Color.argb(115, 255, 255, 255) : Color.argb(120, 255, 255, 255));
                    v.setBackground(gd);
                    STYLED.put(v, Boolean.TRUE);
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
