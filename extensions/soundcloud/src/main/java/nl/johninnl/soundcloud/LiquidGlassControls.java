package nl.johninnl.soundcloud;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shared LiquidGlassLens component + iOS 26/27 Switch & Slider + Offline VietQR.
 *
 * Root-cause fixes (PHẦN A):
 * - Unified `LiquidGlassLens` shader (`PILL_REFRACTION_DISPERSION_SHADER`) shared across
 *   TabBar, Mini-Player, Switch, and Slider — identical optical refraction & 7-band RGB dispersion.
 * - Clamp-to-edge sampling inside the shader (`clamp(rc, clampMin, clampMax)`) so displaced
 *   rays NEVER fall outside the valid card/track backdrop into black or alpha-0.
 * - Backdrop recorded into RenderNode includes BOTH the actual card background (`#16161C`)
 *   and the vibrant track (orange/grey for Slider, bright green/grey for Switch), scaled smoothly
 *   (`1.0 -> 1.18x`) around thumb center — identical to TabBar's held pill zoom & refraction!
 * - Zero blur, zero dark tint, zero stroke, zero inner shadow on thumb.
 * - Single spring `pressProgress` (0..1) drives expansion, white-to-glass crossfade, zoom & refraction.
 */
@SuppressLint("NewApi")
public final class LiquidGlassControls {

    private LiquidGlassControls() {}

    /**
     * Unified AGSL LiquidGlassLens shader shared by TabBar, Mini-Player, Switch, and Slider.
     * Uses clamp-to-edge on refracted coordinates so samples never fall into out-of-bounds black.
     */
    static final String PILL_REFRACTION_DISPERSION_SHADER =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "    if (coord.x >= 0.0) { return coord.y <= 0.0 ? radii.y : radii.z; }\n" +
            "    else { return coord.y <= 0.0 ? radii.x : radii.w; }\n" +
            "}\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 q = abs(coord) - (halfSize - float2(radius));\n" +
            "    return length(max(q, 0.0)) - radius + min(max(q.x, q.y), 0.0);\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 q = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (q.x >= 0.0 || q.y >= 0.0) return sign(coord) * normalize(max(q, 0.0));\n" +
            "    float gx = step(q.y, q.x);\n" +
            "    return sign(coord) * float2(gx, 1.0 - gx);\n" +
            "}\n" +
            "float circleMap(float x) { return 1.0 - sqrt(max(0.0, 1.0 - x * x)); }\n" +
            "half4 sampleClamped(float2 p, float2 minB, float2 maxB) {\n" +
            "    return content.eval(clamp(p, minB, maxB));\n" +
            "}\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 minB = -offset + float2(1.0);\n" +
            "    float2 maxB = -offset + size - float2(1.0);\n" +
            "    float2 c = (coord + offset) - halfSize;\n" +
            "    float r = radiusAt(c, cornerRadii);\n" +
            "    float sd = sdRoundedRect(c, halfSize, r);\n" +
            "    if (-sd >= refractionHeight || refractionHeight <= 0.5) return sampleClamped(coord, minB, maxB);\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / max(refractionHeight, 0.001)) * refractionAmount;\n" +
            "    float gr = min(r * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(c, halfSize, gr) + depthEffect * normalize(c + float2(0.0001)));\n" +
            "    float2 rc = coord + d * grad;\n" +
            "    if (chromaticAberration > 0.01) {\n" +
            "        float di = chromaticAberration * ((c.x * c.y) / max(halfSize.x * halfSize.y, 1.0));\n" +
            "        float2 dc = d * grad * di;\n" +
            "        half4 col = half4(0.0);\n" +
            "        half4 red = sampleClamped(rc + dc, minB, maxB);          col.r += red.r / 3.5; col.a += red.a / 7.0;\n" +
            "        half4 org = sampleClamped(rc + dc * 0.6667, minB, maxB); col.r += org.r / 3.5; col.g += org.g / 7.0; col.a += org.a / 7.0;\n" +
            "        half4 yel = sampleClamped(rc + dc * 0.3333, minB, maxB); col.r += yel.r / 3.5; col.g += yel.g / 3.5; col.a += yel.a / 7.0;\n" +
            "        half4 grn = sampleClamped(rc, minB, maxB);               col.g += grn.g / 3.5; col.a += grn.a / 7.0;\n" +
            "        half4 cyn = sampleClamped(rc - dc * 0.3333, minB, maxB); col.g += cyn.g / 3.5; col.b += cyn.b / 3.0; col.a += cyn.a / 7.0;\n" +
            "        half4 blu = sampleClamped(rc - dc * 0.6667, minB, maxB); col.b += blu.b / 3.0; col.a += blu.a / 7.0;\n" +
            "        half4 pur = sampleClamped(rc - dc, minB, maxB);          col.r += pur.r / 7.0; col.b += pur.b / 3.0; col.a += pur.a / 7.0;\n" +
            "        return col;\n" +
            "    }\n" +
            "    return sampleClamped(rc, minB, maxB);\n" +
            "}\n";

    /**
     * Reusable LiquidGlassLens helper used by Switch and Slider (same shader & refraction math as TabBar).
     * Records the card + track backdrop with subtle TabBar-style magnification, runs the unified
     * AGSL lens shader with zero blur/tint/stroke, and falls back gracefully on API < 33.
     */
    static final class LiquidGlassLens {
        private RenderNode backdropNode;
        private RenderNode lensNode;
        private RuntimeShader shader;
        private final Path clipPath = new Path();
        private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        interface BackdropDrawer {
            void drawBackdrop(Canvas canvas);
        }

        LiquidGlassLens() {
            if (Build.VERSION.SDK_INT >= 33) {
                shader = new RuntimeShader(PILL_REFRACTION_DISPERSION_SHADER);
            }
        }

        void drawThumbLens(
                Canvas canvas, Context ctx, float density,
                int viewW, int viewH, RectF thumbRect, float radius,
                float glassMix, BackdropDrawer backdropDrawer
        ) {
            if (glassMix <= 0.01f) return;

            if (Build.VERSION.SDK_INT >= 33 && shader != null && canvas.isHardwareAccelerated()) {
                if (backdropNode == null) backdropNode = new RenderNode("lg_thumb_backdrop");
                if (lensNode == null) lensNode = new RenderNode("lg_thumb_lens");
                backdropNode.setPosition(0, 0, viewW, viewH);
                lensNode.setPosition(0, 0, viewW, viewH);

                // Record real card surface + vibrant track with subtle TabBar-style zoom around thumb center
                Canvas bc = backdropNode.beginRecording(viewW, viewH);
                try {
                    bc.drawColor(Color.rgb(22, 22, 28)); // Card surface color (never pitch black!)
                    float zoom = 1f + 0.16f * glassMix;
                    bc.save();
                    bc.scale(zoom, zoom, thumbRect.centerX(), thumbRect.centerY());
                    backdropDrawer.drawBackdrop(bc);
                    bc.restore();
                } finally {
                    backdropNode.endRecording();
                }

                float userRefDp = Math.max(6f, LiquidGlassAuthorPage.getRefractionDp(ctx));
                float userDisp = Math.max(0f, LiquidGlassAuthorPage.getDispersion(ctx));
                // Same refraction formula as TabBar's held pill (negative refractionAmount = convex glass bend)
                float refH = Math.max(1f, userRefDp * 0.42f * density * glassMix);
                float refA = Math.max(1f, userRefDp * 0.56f * density * glassMix);

                shader.setFloatUniform("size", thumbRect.width(), thumbRect.height());
                shader.setFloatUniform("offset", -thumbRect.left, -thumbRect.top);
                shader.setFloatUniform("cornerRadii", new float[]{radius, radius, radius, radius});
                shader.setFloatUniform("refractionHeight", refH);
                shader.setFloatUniform("refractionAmount", -refA);
                shader.setFloatUniform("depthEffect", 1f);
                shader.setFloatUniform("chromaticAberration", userDisp * glassMix);

                // Pure RuntimeShader: zero blur, zero dark tint, zero inner shadow, zero stroke
                lensNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));

                Canvas lc = lensNode.beginRecording(viewW, viewH);
                try {
                    lc.drawRenderNode(backdropNode);
                } finally {
                    lensNode.endRecording();
                }

                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(thumbRect, radius, radius, Path.Direction.CW);
                canvas.clipPath(clipPath);
                canvas.drawRenderNode(lensNode);
                canvas.restore();
            } else {
                // Clean translucent glass fallback for API < 33
                fallbackPaint.setColor(Color.argb(Math.round(55 * glassMix), 255, 255, 255));
                canvas.drawRoundRect(thumbRect, radius, radius, fallbackPaint);
            }
        }
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / Math.max(0.0001f, e1 - e0)));
        return t * t * (3f - 2f * t);
    }

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
        private final Paint modPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        public VietQrView(Context ctx) {
            super(ctx);
            this.density = ctx.getResources().getDisplayMetrics().density;
            bgPaint.setColor(Color.WHITE);
            modPaint.setColor(Color.rgb(12, 22, 48));
            accentPaint.setColor(Color.rgb(0, 102, 179));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = Math.round(220f * density);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int size = Math.min(getWidth(), getHeight());
            float corner = 16f * density;
            canvas.drawRoundRect(0, 0, size, size, corner, corner, bgPaint);

            int n = VIETQR_MATRIX.length;
            float quiet = size * 0.085f;
            float cell = (size - quiet * 2f) / n;

            for (int r = 0; r < n; r++) {
                String row = VIETQR_MATRIX[r];
                for (int c = 0; c < n; c++) {
                    if (row.charAt(c) == '1') {
                        float left = quiet + c * cell;
                        float top = quiet + r * cell;
                        boolean isFinder = (r < 7 && c < 7) || (r < 7 && c >= n - 7) || (r >= n - 7 && c < 7);
                        Paint p = isFinder ? accentPaint : modPaint;
                        canvas.drawRect(left, top, left + cell + 0.4f, top + cell + 0.4f, p);
                    }
                }
            }
        }
    }

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean checked);
    }

    /**
     * iOS 26/27 Liquid Glass Switch using the shared `LiquidGlassLens` component (same shader as TabBar).
     */
    public static final class Ios27GlassSwitch extends View implements Choreographer.FrameCallback {
        private final float density;
        private boolean checked;
        private final OnCheckedChangeListener listener;

        private float pos = 0f;
        private float posVel = 0f;
        private float pressProgress = 0f;
        private float pressTarget = 0f;
        private float pressVel = 0f;
        private boolean dragging = false;
        private float downX = 0f;
        private boolean frameScheduled = false;
        private long lastFrameNs = 0L;

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF thumbRect = new RectF();
        private final LiquidGlassLens glassLens = new LiquidGlassLens();

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
            setMeasuredDimension(Math.round(92f * density), Math.round(54f * density));
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
                    if (Math.abs(dx) > 4f * density) dragging = true;
                    if (dragging) {
                        float left = 20f * density;
                        float usable = Math.max(1f, getWidth() - 40f * density);
                        pos = Math.max(0f, Math.min(1f, (event.getX() - left) / usable));
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
                float c = 2f * 0.76f * (float) Math.sqrt(440f);
                float a = -440f * (pos - target) - c * posVel;
                posVel += a * dt;
                pos = Math.max(0f, Math.min(1f, pos + posVel * dt));
                if (Math.abs(pos - target) > 0.002f || Math.abs(posVel) > 0.005f) moving = true;
            }

            float c2 = 2f * 0.80f * (float) Math.sqrt(420f);
            float a2 = -420f * (pressProgress - pressTarget) - c2 * pressVel;
            pressVel += a2 * dt;
            pressProgress = Math.max(0f, Math.min(1f, pressProgress + pressVel * dt));
            if (Math.abs(pressProgress - pressTarget) > 0.002f || Math.abs(pressVel) > 0.005f) moving = true;

            invalidate();
            if (moving) schedule();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float trackW = 64f * density;
            float trackH = 26f * density;
            float cxView = w * 0.5f;
            float cyView = h * 0.5f;
            trackRect.set(cxView - trackW * 0.5f, cyView - trackH * 0.5f,
                    cxView + trackW * 0.5f, cyView + trackH * 0.5f);
            final float tr = trackH * 0.5f;

            // Clean vibrant #48484A (OFF) -> #34C759 (ON)
            int r = Math.round(72 + (52 - 72) * pos);
            int g = Math.round(72 + (199 - 72) * pos);
            int b = Math.round(74 + (89 - 74) * pos);
            trackPaint.setColor(Color.rgb(r, g, b));

            // 1. Draw track first; thumb always draws on top with zero track clipping
            canvas.drawRoundRect(trackRect, tr, tr, trackPaint);

            // 2. Held Switch thumb is ~42% taller than track (37dp vs 26dp) and wider (54dp),
            // overhanging top, bottom & active outer end so dark card backdrop enters the lens rim!
            float glassMix = smoothstep(0.05f, 0.85f, pressProgress);
            float restW = 38f * density;
            float restH = 22f * density;
            float thumbW = restW + 16f * density * pressProgress;
            float thumbH = restH + 15f * density * pressProgress;

            float minCx = trackRect.left + 2f * density + restW * 0.5f;
            float maxCx = trackRect.right - 2f * density - restW * 0.5f;
            float outwardShift = (pos * 2f - 1f) * 3.5f * density * pressProgress;
            float tcx = minCx + (maxCx - minCx) * pos + outwardShift;
            thumbRect.set(tcx - thumbW * 0.5f, cyView - thumbH * 0.5f,
                    tcx + thumbW * 0.5f, cyView + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            // 3. Shared LiquidGlassLens (exact same shader/uniforms as Slider & TabBar!):
            // Scale track vertically inside backdrop so dark card surround fills the outer bevel rim
            // identically to Slider — creating crisp dark edge refraction + RGB dispersion around the green/grey core!
            glassLens.drawThumbLens(canvas, getContext(), density, w, h, thumbRect, thr, glassMix, bc -> {
                bc.save();
                bc.scale(0.88f, 0.64f, trackRect.centerX(), trackRect.centerY());
                bc.drawRoundRect(trackRect, tr, tr, trackPaint);
                bc.restore();
            });

            // 4. Smooth white pill crossfade driven by pressProgress
            int whiteAlpha = Math.round(255f * (1f - glassMix));
            if (whiteAlpha > 2) {
                thumbWhitePaint.setColor(Color.argb(whiteAlpha, 255, 255, 255));
                canvas.drawRoundRect(thumbRect, thr, thr, thumbWhitePaint);
            }
        }
    }

    public interface OnSliderChangeListener {
        void onValueChanged(float value);
    }

    /**
     * iOS 26/27 Liquid Glass Slider using the exact same shared `LiquidGlassLens` component as TabBar & Switch.
     */
    public static final class Ios27GlassSlider extends View implements Choreographer.FrameCallback {
        private final float density;
        private final float minVal;
        private final float maxVal;
        private float value;
        private final OnSliderChangeListener listener;

        private float pressProgress = 0f;
        private float pressTarget = 0f;
        private float pressVel = 0f;
        private boolean frameScheduled = false;
        private long lastFrameNs = 0L;

        private final Paint trackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF fillRect = new RectF();
        private final RectF thumbRect = new RectF();
        private final LiquidGlassLens glassLens = new LiquidGlassLens();

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
            setMeasuredDimension(w, Math.round(50f * density));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float padH = 28f * density;
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

            float c = 2f * 0.80f * (float) Math.sqrt(420f);
            float a = -420f * (pressProgress - pressTarget) - c * pressVel;
            pressVel += a * dt;
            pressProgress = Math.max(0f, Math.min(1f, pressProgress + pressVel * dt));
            invalidate();
            if (Math.abs(pressProgress - pressTarget) > 0.002f || Math.abs(pressVel) > 0.005f) {
                schedule();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float padH = 28f * density;
            float trackH = (7.5f + 2.5f * pressProgress) * density;
            float cy = h * 0.5f;
            float frac = (maxVal > minVal) ? ((value - minVal) / (maxVal - minVal)) : 0f;

            trackRect.set(padH, cy - trackH * 0.5f, w - padH, cy + trackH * 0.5f);
            final float tr = trackH * 0.5f;

            trackBgPaint.setColor(Color.rgb(66, 66, 74));
            float fillRight = trackRect.left + trackRect.width() * frac;
            fillRect.set(trackRect.left, trackRect.top, Math.max(trackRect.left + trackH, fillRight), trackRect.bottom);
            trackFillPaint.setColor(Color.rgb(255, 108, 38));

            // 1. Draw track + orange fill first
            canvas.drawRoundRect(trackRect, tr, tr, trackBgPaint);
            canvas.drawRoundRect(fillRect, tr, tr, trackFillPaint);

            // 2. Thumb geometry centered on track Y
            float glassMix = smoothstep(0.05f, 0.85f, pressProgress);
            float thumbW = (36f + 14f * pressProgress) * density;
            float thumbH = (21f + 12f * pressProgress) * density;
            float cx = trackRect.left + trackRect.width() * frac;
            thumbRect.set(cx - thumbW * 0.5f, cy - thumbH * 0.5f, cx + thumbW * 0.5f, cy + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            // 3. Shared LiquidGlassLens (zoomed orange/grey track + RGB edge dispersion, zero black block!)
            glassLens.drawThumbLens(canvas, getContext(), density, w, h, thumbRect, thr, glassMix, bc -> {
                bc.drawRoundRect(trackRect, tr, tr, trackBgPaint);
                bc.drawRoundRect(fillRect, tr, tr, trackFillPaint);
            });

            // 4. Smooth white pill crossfade
            int whiteAlpha = Math.round(255f * (1f - glassMix));
            if (whiteAlpha > 2) {
                thumbWhitePaint.setColor(Color.argb(whiteAlpha, 255, 255, 255));
                canvas.drawRoundRect(thumbRect, thr, thr, thumbWhitePaint);
            }
        }
    }
}
