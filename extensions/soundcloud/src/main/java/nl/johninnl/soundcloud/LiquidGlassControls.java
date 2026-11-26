package nl.johninnl.soundcloud;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * Real AGSL + RenderNode iOS 26/27 Liquid Glass Switch & Slider + Offline VietQR.
 *
 * Optical design (matching reference 152009 / 152005):
 * - 1:1 flat crystal-clear core: pixels deeper than `refractionBand` sample `content.eval(coord)`
 *   directly with zero zoom, zero blur, zero tint, and zero inner shadow.
 * - Thin edge refraction band (6..10dp): smooth SDF-normal refraction bends mid-bevel rays outward
 *   (revealing the dark surround as a crisp curved channel) and grazing outer-rim rays inward
 *   (picking up the bright track color + 3-channel RGB chromatic dispersion at the very edge).
 * - No thick white stroke; edge definition comes from pure optical refraction & RGB dispersion
 *   (plus an ultra-faint top specular glint).
 * - Single spring `pressProgress` (0..1) drives expansion, glass crossfade, refraction & dispersion.
 */
@SuppressLint("NewApi")
public final class LiquidGlassControls {

    private LiquidGlassControls() {}

    static final String PILL_REFRACTION_DISPERSION_SHADER =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionBand;\n" +
            "uniform float refractionStrength;\n" +
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
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 c = (coord + offset) - halfSize;\n" +
            "    float r = radiusAt(c, cornerRadii);\n" +
            "    float sd = sdRoundedRect(c, halfSize, r);\n" +
            "    float distIn = -sd;\n" +
            "    // 1. Core region: exact 1:1 pixel sample (no zoom, no blur, no tint, no shadow)\n" +
            "    if (distIn >= refractionBand || refractionBand <= 0.5) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    // 2. Thin edge refraction band (edgeT: 0 at core boundary -> 1 at outer rim)\n" +
            "    float edgeT = clamp(1.0 - (max(distIn, 0.0) / max(refractionBand, 0.001)), 0.0, 1.0);\n" +
            "    float2 n = normalize(gradSdRoundedRect(c, halfSize, r) + float2(0.0001));\n" +
            "    // Smooth dual-zone optical curve:\n" +
            "    // - Mid-bevel bends outward (+n) pulling dark surround into a crisp concave channel\n" +
            "    // - Outer grazing rim bends inward (-n) reflecting the bright track along the very edge\n" +
            "    float outward = smoothstep(0.0, 0.48, edgeT) * (1.0 - smoothstep(0.58, 0.90, edgeT));\n" +
            "    float grazing = smoothstep(0.66, 1.0, edgeT);\n" +
            "    grazing = grazing * grazing;\n" +
            "    float disp = (outward * 0.85 - grazing * 1.15) * refractionStrength;\n" +
            "    float2 basePos = coord + n * disp;\n" +
            "    // 3. RGB chromatic dispersion strictly on the bevel band (3-channel R/G/B offset)\n" +
            "    float dispAmt = chromaticAberration * (0.28 * outward + 0.55 * grazing) * refractionStrength * 0.42;\n" +
            "    if (dispAmt > 0.08) {\n" +
            "        half4 sR = content.eval(basePos + n * dispAmt);\n" +
            "        half4 sG = content.eval(basePos);\n" +
            "        half4 sB = content.eval(basePos - n * dispAmt);\n" +
            "        return half4(sR.r, sG.g, sB.b, 1.0);\n" +
            "    }\n" +
            "    half4 s = content.eval(basePos);\n" +
            "    return half4(s.rgb, 1.0);\n" +
            "}\n";

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
     * iOS 26/27 Liquid Glass Switch (matches 152009 ON / 152005 OFF):
     * - 1:1 flat unblurred core showing the exact track behind the thumb.
     * - Pure SDF-normal edge refraction + 3-channel RGB dispersion (zero white stroke, zero blur, zero tint).
     * - Centered strictly on track Y, drawn on top of track with no track clipping.
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
        private final Paint fallbackGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF thumbRect = new RectF();
        private final Path thumbClipPath = new Path();

        private RenderNode trackNode;
        private RenderNode glassThumbNode;
        private RuntimeShader glassShader;

        public Ios27GlassSwitch(Context ctx, boolean initial, OnCheckedChangeListener listener) {
            super(ctx);
            this.density = ctx.getResources().getDisplayMetrics().density;
            this.checked = initial;
            this.pos = initial ? 1f : 0f;
            this.listener = listener;
            setClickable(true);
            glintPaint.setStyle(Paint.Style.STROKE);
            glintPaint.setStrokeWidth(0.75f * density);
            if (Build.VERSION.SDK_INT >= 33) {
                glassShader = new RuntimeShader(PILL_REFRACTION_DISPERSION_SHADER);
            }
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
            setMeasuredDimension(Math.round(80f * density), Math.round(46f * density));
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
                        float left = 18f * density;
                        float usable = Math.max(1f, getWidth() - 36f * density);
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

            // Single spring pressProgress (0..1) controlling size, glass crossfade & refraction
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

            // 1. Track centered strictly in view (64x28dp)
            float trackW = 64f * density;
            float trackH = 28f * density;
            float cxView = w * 0.5f;
            float cyView = h * 0.5f;
            trackRect.set(cxView - trackW * 0.5f, cyView - trackH * 0.5f,
                    cxView + trackW * 0.5f, cyView + trackH * 0.5f);
            float tr = trackH * 0.5f;

            // #2E2E30 (OFF) -> #34C759 (iOS bright green ON)
            int r = Math.round(46 + (52 - 46) * pos);
            int g = Math.round(46 + (199 - 46) * pos);
            int b = Math.round(48 + (89 - 48) * pos);
            trackPaint.setColor(Color.rgb(r, g, b));

            // Draw track first; thumb always renders on top with zero track clipping
            canvas.drawRoundRect(trackRect, tr, tr, trackPaint);

            // 2. Thumb geometry centered on track Y (cyView):
            // Rest: 38x24dp pill -> Held: 50x35dp (overhangs track top/bottom by 3.5dp, never lopsided)
            float glassMix = smoothstep(0.06f, 0.86f, pressProgress);
            float restW = 38f * density;
            float restH = 24f * density;
            float thumbW = restW + 12f * density * pressProgress;
            float thumbH = restH + 11f * density * pressProgress;

            float minCx = trackRect.left + 2f * density + restW * 0.5f;
            float maxCx = trackRect.right - 2f * density - restW * 0.5f;
            float baseCx = minCx + (maxCx - minCx) * pos;
            // Subtle symmetric anchor so expanded pill sits evenly over the active end of track
            float targetLeftCx = trackRect.left + thumbW * 0.42f;
            float targetRightCx = trackRect.right - thumbW * 0.42f;
            float clampedCx = Math.max(targetLeftCx, Math.min(targetRightCx, baseCx));
            float tcx = baseCx + (clampedCx - baseCx) * pressProgress;

            thumbRect.set(tcx - thumbW * 0.5f, cyView - thumbH * 0.5f,
                    tcx + thumbW * 0.5f, cyView + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            // 3. Pure 1:1 core + edge-band AGSL refraction & RGB dispersion (blur = 0, tint = 0, shadow = 0)
            if (glassMix > 0.01f) {
                if (Build.VERSION.SDK_INT >= 33 && glassShader != null && canvas.isHardwareAccelerated()) {
                    if (trackNode == null) trackNode = new RenderNode("ios27_sw_track");
                    if (glassThumbNode == null) glassThumbNode = new RenderNode("ios27_sw_glass");
                    trackNode.setPosition(0, 0, w, h);
                    glassThumbNode.setPosition(0, 0, w, h);

                    Canvas tc = trackNode.beginRecording(w, h);
                    try {
                        // Deep dark surround outside track so mid-bevel outward refraction forms the crisp dark channel
                        tc.drawColor(Color.rgb(10, 10, 12));
                        tc.drawRoundRect(trackRect, tr, tr, trackPaint);
                    } finally {
                        trackNode.endRecording();
                    }

                    float userRef = Math.max(6f, LiquidGlassAuthorPage.getRefractionDp(getContext()));
                    float refScale = userRef / 24f;
                    float userDisp = LiquidGlassAuthorPage.getDispersion(getContext());

                    glassShader.setFloatUniform("size", thumbRect.width(), thumbRect.height());
                    glassShader.setFloatUniform("offset", -thumbRect.left, -thumbRect.top);
                    glassShader.setFloatUniform("cornerRadii", new float[]{thr, thr, thr, thr});
                    glassShader.setFloatUniform("refractionBand", Math.max(1f, 8.5f * density * glassMix));
                    glassShader.setFloatUniform("refractionStrength", 11.5f * density * refScale * glassMix);
                    glassShader.setFloatUniform("chromaticAberration", userDisp * glassMix);

                    // Zero blur, zero tint: pure crisp RuntimeShader effect
                    glassThumbNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(glassShader, "content"));

                    Canvas gc = glassThumbNode.beginRecording(w, h);
                    try {
                        gc.drawRenderNode(trackNode);
                    } finally {
                        glassThumbNode.endRecording();
                    }

                    canvas.save();
                    thumbClipPath.reset();
                    thumbClipPath.addRoundRect(thumbRect, thr, thr, Path.Direction.CW);
                    canvas.clipPath(thumbClipPath);
                    canvas.drawRenderNode(glassThumbNode);
                    canvas.restore();
                } else {
                    fallbackGlassPaint.setColor(Color.argb(Math.round(45 * glassMix), 255, 255, 255));
                    canvas.drawRoundRect(thumbRect, thr, thr, fallbackGlassPaint);
                }
            }

            // 4. Smooth white pill crossfade (0 -> 1 glassMix without jump)
            int whiteAlpha = Math.round(255f * (1f - glassMix));
            if (whiteAlpha > 2) {
                thumbWhitePaint.setColor(Color.argb(whiteAlpha, 255, 255, 255));
                canvas.drawRoundRect(thumbRect, thr, thr, thumbWhitePaint);
            }

            // 5. Ultra-faint top specular glint only (no thick white border!)
            if (glassMix > 0.05f) {
                glintPaint.setShader(new LinearGradient(
                        thumbRect.left, thumbRect.top,
                        thumbRect.left, thumbRect.bottom,
                        new int[]{
                                Color.argb(Math.round(44 * glassMix), 255, 255, 255),
                                Color.argb(0, 255, 255, 255),
                                Color.argb(Math.round(24 * glassMix), 255, 255, 255)
                        },
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(thumbRect, thr, thr, glintPaint);
                glintPaint.setShader(null);
            }
        }
    }

    public interface OnSliderChangeListener {
        void onValueChanged(float value);
    }

    /**
     * iOS 26/27 Liquid Glass Slider:
     * - Shares the exact same 1:1 core + edge-band refraction & RGB dispersion shader as Switch.
     * - Zero blur, zero tint, zero white stroke, centered strictly on track Y.
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
        private final Paint fallbackGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF fillRect = new RectF();
        private final RectF thumbRect = new RectF();
        private final Path thumbClipPath = new Path();

        private RenderNode trackNode;
        private RenderNode glassThumbNode;
        private RuntimeShader glassShader;

        public Ios27GlassSlider(Context ctx, float min, float max, float initial, OnSliderChangeListener listener) {
            super(ctx);
            this.density = ctx.getResources().getDisplayMetrics().density;
            this.minVal = min;
            this.maxVal = max;
            this.value = Math.max(min, Math.min(max, initial));
            this.listener = listener;
            glintPaint.setStyle(Paint.Style.STROKE);
            glintPaint.setStrokeWidth(0.75f * density);
            if (Build.VERSION.SDK_INT >= 33) {
                glassShader = new RuntimeShader(PILL_REFRACTION_DISPERSION_SHADER);
            }
        }

        public void setValue(float v) {
            this.value = Math.max(minVal, Math.min(maxVal, v));
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(w, Math.round(46f * density));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float padH = 26f * density;
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

            float padH = 26f * density;
            float trackH = (6.5f + 2.0f * pressProgress) * density;
            float cy = h * 0.5f;
            float frac = (maxVal > minVal) ? ((value - minVal) / (maxVal - minVal)) : 0f;

            trackRect.set(padH, cy - trackH * 0.5f, w - padH, cy + trackH * 0.5f);
            float tr = trackH * 0.5f;

            trackBgPaint.setColor(Color.rgb(58, 58, 64));
            float fillRight = trackRect.left + trackRect.width() * frac;
            fillRect.set(trackRect.left, trackRect.top, Math.max(trackRect.left + trackH, fillRight), trackRect.bottom);
            trackFillPaint.setColor(Color.rgb(255, 108, 38));

            canvas.drawRoundRect(trackRect, tr, tr, trackBgPaint);
            canvas.drawRoundRect(fillRect, tr, tr, trackFillPaint);

            float glassMix = smoothstep(0.06f, 0.86f, pressProgress);
            float thumbW = (34f + 12f * pressProgress) * density;
            float thumbH = (20f + 11f * pressProgress) * density;
            float cx = trackRect.left + trackRect.width() * frac;
            thumbRect.set(cx - thumbW * 0.5f, cy - thumbH * 0.5f, cx + thumbW * 0.5f, cy + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            if (glassMix > 0.01f) {
                if (Build.VERSION.SDK_INT >= 33 && glassShader != null && canvas.isHardwareAccelerated()) {
                    if (trackNode == null) trackNode = new RenderNode("ios27_sl_track");
                    if (glassThumbNode == null) glassThumbNode = new RenderNode("ios27_sl_glass");
                    trackNode.setPosition(0, 0, w, h);
                    glassThumbNode.setPosition(0, 0, w, h);

                    Canvas tc = trackNode.beginRecording(w, h);
                    try {
                        tc.drawColor(Color.rgb(10, 10, 12));
                        tc.drawRoundRect(trackRect, tr, tr, trackBgPaint);
                        tc.drawRoundRect(fillRect, tr, tr, trackFillPaint);
                    } finally {
                        trackNode.endRecording();
                    }

                    float userRef = Math.max(6f, LiquidGlassAuthorPage.getRefractionDp(getContext()));
                    float refScale = userRef / 24f;
                    float userDisp = LiquidGlassAuthorPage.getDispersion(getContext());

                    glassShader.setFloatUniform("size", thumbRect.width(), thumbRect.height());
                    glassShader.setFloatUniform("offset", -thumbRect.left, -thumbRect.top);
                    glassShader.setFloatUniform("cornerRadii", new float[]{thr, thr, thr, thr});
                    glassShader.setFloatUniform("refractionBand", Math.max(1f, 7.5f * density * glassMix));
                    glassShader.setFloatUniform("refractionStrength", 10.5f * density * refScale * glassMix);
                    glassShader.setFloatUniform("chromaticAberration", userDisp * glassMix);

                    glassThumbNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(glassShader, "content"));

                    Canvas gc = glassThumbNode.beginRecording(w, h);
                    try {
                        gc.drawRenderNode(trackNode);
                    } finally {
                        glassThumbNode.endRecording();
                    }

                    canvas.save();
                    thumbClipPath.reset();
                    thumbClipPath.addRoundRect(thumbRect, thr, thr, Path.Direction.CW);
                    canvas.clipPath(thumbClipPath);
                    canvas.drawRenderNode(glassThumbNode);
                    canvas.restore();
                } else {
                    fallbackGlassPaint.setColor(Color.argb(Math.round(45 * glassMix), 255, 255, 255));
                    canvas.drawRoundRect(thumbRect, thr, thr, fallbackGlassPaint);
                }
            }

            int whiteAlpha = Math.round(255f * (1f - glassMix));
            if (whiteAlpha > 2) {
                thumbWhitePaint.setColor(Color.argb(whiteAlpha, 255, 255, 255));
                canvas.drawRoundRect(thumbRect, thr, thr, thumbWhitePaint);
            }

            if (glassMix > 0.05f) {
                glintPaint.setShader(new LinearGradient(
                        thumbRect.left, thumbRect.top,
                        thumbRect.left, thumbRect.bottom,
                        new int[]{
                                Color.argb(Math.round(44 * glassMix), 255, 255, 255),
                                Color.argb(0, 255, 255, 255),
                                Color.argb(Math.round(24 * glassMix), 255, 255, 255)
                        },
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(thumbRect, thr, thr, glintPaint);
                glintPaint.setShader(null);
            }
        }
    }
}
