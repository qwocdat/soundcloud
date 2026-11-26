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
 * Real AGSL + RenderNode iOS 26/27 Liquid Glass controls:
 * - Ios27GlassSwitch: long capsule track (66x28dp) with horizontal white pill thumb (39x23dp)
 *   that dissolves into an oversized crystal-clear AGSL refracting & 7-band chromatic-dispersing
 *   glass pill (55x38dp) when held/dragged.
 * - Ios27GlassSlider: horizontal white pill thumb (36x20dp) that expands into the same real
 *   AGSL refracting crystal glass pill (50x32dp) over the slider track.
 * - VietQrView: verified offline VietQR for VietinBank (970415) STK 0989249990.
 */
@SuppressLint("NewApi")
public final class LiquidGlassControls {

    private LiquidGlassControls() {}

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
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 c = (coord + offset) - halfSize;\n" +
            "    float r = radiusAt(c, cornerRadii);\n" +
            "    float sd = sdRoundedRect(c, halfSize, r);\n" +
            "    if (-sd >= refractionHeight) return content.eval(coord);\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / max(refractionHeight, 0.001)) * refractionAmount;\n" +
            "    float gr = min(r * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(c, halfSize, gr) + depthEffect * normalize(c + float2(0.0001)));\n" +
            "    float2 rc = coord + d * grad;\n" +
            "    float di = chromaticAberration * ((c.x * c.y) / max(halfSize.x * halfSize.y, 1.0));\n" +
            "    float2 dc = d * grad * di;\n" +
            "    half4 col = half4(0.0);\n" +
            "    half4 red = content.eval(rc + dc); col.r += red.r / 3.5; col.a += red.a / 7.0;\n" +
            "    half4 org = content.eval(rc + dc * 0.6667); col.r += org.r / 3.5; col.g += org.g / 7.0; col.a += org.a / 7.0;\n" +
            "    half4 yel = content.eval(rc + dc * 0.3333); col.r += yel.r / 3.5; col.g += yel.g / 3.5; col.a += yel.a / 7.0;\n" +
            "    half4 grn = content.eval(rc); col.g += grn.g / 3.5; col.a += grn.a / 7.0;\n" +
            "    half4 cyn = content.eval(rc - dc * 0.3333); col.g += cyn.g / 3.5; col.b += cyn.b / 3.0; col.a += cyn.a / 7.0;\n" +
            "    half4 blu = content.eval(rc - dc * 0.6667); col.b += blu.b / 3.0; col.a += blu.a / 7.0;\n" +
            "    half4 pur = content.eval(rc - dc); col.r += pur.r / 7.0; col.b += pur.b / 3.0; col.a += pur.a / 7.0;\n" +
            "    return col;\n" +
            "}\n";

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
     * Exact iOS 26/27 Liquid Glass Switch:
     * - Rest: long capsule track (66x28dp, #2C2C2E OFF / #34C759 ON) + horizontal white pill (39x23dp).
     * - Held/Dragged: white pill turns completely transparent and expands into an oversized (55x38dp)
     *   crystal-clear AGSL refracting & 7-band chromatic-dispersing lens overhanging the track,
     *   creating the signature dark concave refraction rim + vivid green/grey refracted center.
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
        private final Paint thumbWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF thumbRect = new RectF();
        private final Path clipPath = new Path();

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
            setMeasuredDimension(Math.round(88f * density), Math.round(52f * density));
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
                    if (Math.abs(dx) > 5f * density) dragging = true;
                    if (dragging) {
                        float left = 22f * density;
                        float usable = Math.max(1f, getWidth() - 44f * density);
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
                float c = 2f * 0.72f * (float) Math.sqrt(460f);
                float a = -460f * (pos - target) - c * posVel;
                posVel += a * dt;
                pos = Math.max(0f, Math.min(1f, pos + posVel * dt));
                if (Math.abs(pos - target) > 0.002f || Math.abs(posVel) > 0.005f) moving = true;
            }
            float c2 = 2f * 0.78f * (float) Math.sqrt(520f);
            float a2 = -520f * (press - pressTarget) - c2 * pressVel;
            pressVel += a2 * dt;
            press = Math.max(0f, Math.min(1f, press + pressVel * dt));
            if (Math.abs(press - pressTarget) > 0.002f || Math.abs(pressVel) > 0.005f) moving = true;

            invalidate();
            if (moving) schedule();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // Long iOS 26/27 capsule track (66x28dp)
            float trackW = 66f * density;
            float trackH = 28f * density;
            float cxView = w * 0.5f;
            float cyView = h * 0.5f;
            trackRect.set(cxView - trackW * 0.5f, cyView - trackH * 0.5f,
                    cxView + trackW * 0.5f, cyView + trackH * 0.5f);
            float tr = trackH * 0.5f;

            // #2C2C2E (OFF) -> #34C759 (iOS Green ON)
            int r = Math.round(44 + (52 - 44) * pos);
            int g = Math.round(44 + (199 - 44) * pos);
            int b = Math.round(46 + (89 - 46) * pos);
            trackPaint.setColor(Color.rgb(r, g, b));

            // 1. Record track over deep black backdrop into trackNode for true concave edge refraction
            if (Build.VERSION.SDK_INT >= 33 && press > 0.01f && glassShader != null && canvas.isHardwareAccelerated()) {
                if (trackNode == null) trackNode = new RenderNode("ios27_sw_track");
                trackNode.setPosition(0, 0, w, h);
                Canvas tc = trackNode.beginRecording(w, h);
                try {
                    tc.drawColor(Color.BLACK);
                    tc.drawRoundRect(trackRect, tr, tr, trackPaint);
                } finally {
                    trackNode.endRecording();
                }
            }
            canvas.drawRoundRect(trackRect, tr, tr, trackPaint);

            // 2. Horizontal pill thumb: rest (39x23dp) -> pressed (55x38dp overhanging track!)
            float restW = 39f * density;
            float restH = 23f * density;
            float thumbW = restW + 16f * density * press;
            float thumbH = restH + 15f * density * press;

            float minCx = trackRect.left + 2.5f * density + restW * 0.5f;
            float maxCx = trackRect.right - 2.5f * density - restW * 0.5f;
            float tcx = minCx + (maxCx - minCx) * pos;
            thumbRect.set(tcx - thumbW * 0.5f, cyView - thumbH * 0.5f,
                    tcx + thumbW * 0.5f, cyView + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            // 3. Real AGSL refracting & 7-band chromatic-dispersing crystal glass pill
            // POSITIVE refractionAmount displaces rim rays outward into the black surround,
            // leaving the center clean green/grey while bending the dark edge + rainbow prism inward!
            if (Build.VERSION.SDK_INT >= 33 && press > 0.01f && trackNode != null
                    && glassShader != null && canvas.isHardwareAccelerated()) {
                if (glassThumbNode == null) glassThumbNode = new RenderNode("ios27_sw_glass");
                glassThumbNode.setPosition(0, 0, w, h);

                glassShader.setFloatUniform("size", thumbRect.width(), thumbRect.height());
                glassShader.setFloatUniform("offset", -thumbRect.left, -thumbRect.top);
                glassShader.setFloatUniform("cornerRadii", new float[]{thr, thr, thr, thr});
                glassShader.setFloatUniform("refractionHeight", Math.max(1f, 11.5f * density * press));
                glassShader.setFloatUniform("refractionAmount", 17f * density * press);
                glassShader.setFloatUniform("depthEffect", 0.65f);
                glassShader.setFloatUniform("chromaticAberration", 0.95f * press);
                glassThumbNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(glassShader, "content"));

                Canvas gc = glassThumbNode.beginRecording(w, h);
                try {
                    gc.drawRenderNode(trackNode);
                } finally {
                    glassThumbNode.endRecording();
                }

                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(thumbRect, thr, thr, Path.Direction.CW);
                canvas.clipPath(clipPath);
                canvas.drawRenderNode(glassThumbNode);
                canvas.restore();
            }

            // 4. Solid white pill fades completely to 0 when pressed (pure crystal glass!)
            int whiteAlpha = Math.round(255f * Math.max(0f, 1f - press * 1.35f));
            if (whiteAlpha > 2) {
                thumbWhitePaint.setColor(Color.argb(whiteAlpha, 255, 255, 255));
                canvas.drawRoundRect(thumbRect, thr, thr, thumbWhitePaint);
            }

            // 5. Crisp specular crystal rim when pressed
            if (press > 0.02f) {
                rimPaint.setStyle(Paint.Style.STROKE);
                rimPaint.setStrokeWidth(1.05f * density);
                rimPaint.setShader(new LinearGradient(thumbRect.left, thumbRect.top, thumbRect.left, thumbRect.bottom,
                        Color.argb(Math.round(150 * press), 255, 255, 255),
                        Color.argb(Math.round(70 * press), 255, 255, 255),
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(thumbRect, thr, thr, rimPaint);
                rimPaint.setShader(null);
            }
        }
    }

    public interface OnSliderChangeListener {
        void onValueChanged(float value);
    }

    /**
     * Exact iOS 26/27 Liquid Glass Slider:
     * - Rest: horizontal white pill thumb (36x20dp) on capsule track (6.5dp).
     * - Held/Dragged: white pill dissolves to pure crystal glass (50x32dp) using the exact same
     *   positive-refraction AGSL lens + 7-band chromatic dispersion as the Switch & TabBar!
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
        private final Paint thumbWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF fillRect = new RectF();
        private final RectF thumbRect = new RectF();
        private final Path clipPath = new Path();

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

            float c = 2f * 0.78f * (float) Math.sqrt(500f);
            float a = -500f * (press - pressTarget) - c * pressVel;
            pressVel += a * dt;
            press = Math.max(0f, Math.min(1f, press + pressVel * dt));
            invalidate();
            if (Math.abs(press - pressTarget) > 0.002f || Math.abs(pressVel) > 0.005f) {
                schedule();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float padH = 28f * density;
            float trackH = (6.5f + 2.5f * press) * density;
            float cy = h * 0.5f;
            float frac = (maxVal > minVal) ? ((value - minVal) / (maxVal - minVal)) : 0f;

            trackRect.set(padH, cy - trackH * 0.5f, w - padH, cy + trackH * 0.5f);
            float tr = trackH * 0.5f;

            trackBgPaint.setColor(Color.rgb(52, 52, 60));
            float fillRight = trackRect.left + trackRect.width() * frac;
            fillRect.set(trackRect.left, trackRect.top, Math.max(trackRect.left + trackH, fillRight), trackRect.bottom);
            trackFillPaint.setColor(Color.rgb(255, 102, 34));

            if (Build.VERSION.SDK_INT >= 33 && press > 0.01f && glassShader != null && canvas.isHardwareAccelerated()) {
                if (trackNode == null) trackNode = new RenderNode("ios27_sl_track");
                trackNode.setPosition(0, 0, w, h);
                Canvas tc = trackNode.beginRecording(w, h);
                try {
                    tc.drawColor(Color.BLACK);
                    tc.drawRoundRect(trackRect, tr, tr, trackBgPaint);
                    tc.drawRoundRect(fillRect, tr, tr, trackFillPaint);
                } finally {
                    trackNode.endRecording();
                }
            }

            canvas.drawRoundRect(trackRect, tr, tr, trackBgPaint);
            canvas.drawRoundRect(fillRect, tr, tr, trackFillPaint);

            // Horizontal pill thumb (36x20dp rest -> 50x32dp crystal glass pill held!)
            float thumbW = (36f + 14f * press) * density;
            float thumbH = (20f + 12f * press) * density;
            float cx = trackRect.left + trackRect.width() * frac;
            thumbRect.set(cx - thumbW * 0.5f, cy - thumbH * 0.5f, cx + thumbW * 0.5f, cy + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            if (Build.VERSION.SDK_INT >= 33 && press > 0.01f && trackNode != null
                    && glassShader != null && canvas.isHardwareAccelerated()) {
                if (glassThumbNode == null) glassThumbNode = new RenderNode("ios27_sl_glass");
                glassThumbNode.setPosition(0, 0, w, h);

                glassShader.setFloatUniform("size", thumbRect.width(), thumbRect.height());
                glassShader.setFloatUniform("offset", -thumbRect.left, -thumbRect.top);
                glassShader.setFloatUniform("cornerRadii", new float[]{thr, thr, thr, thr});
                glassShader.setFloatUniform("refractionHeight", Math.max(1f, 9.5f * density * press));
                glassShader.setFloatUniform("refractionAmount", 15f * density * press);
                glassShader.setFloatUniform("depthEffect", 0.65f);
                glassShader.setFloatUniform("chromaticAberration", 0.95f * press);
                glassThumbNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(glassShader, "content"));

                Canvas gc = glassThumbNode.beginRecording(w, h);
                try {
                    gc.drawRenderNode(trackNode);
                } finally {
                    glassThumbNode.endRecording();
                }

                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(thumbRect, thr, thr, Path.Direction.CW);
                canvas.clipPath(clipPath);
                canvas.drawRenderNode(glassThumbNode);
                canvas.restore();
            }

            int whiteAlpha = Math.round(255f * Math.max(0f, 1f - press * 1.35f));
            if (whiteAlpha > 2) {
                thumbWhitePaint.setColor(Color.argb(whiteAlpha, 255, 255, 255));
                canvas.drawRoundRect(thumbRect, thr, thr, thumbWhitePaint);
            }

            if (press > 0.02f) {
                rimPaint.setStyle(Paint.Style.STROKE);
                rimPaint.setStrokeWidth(1.05f * density);
                rimPaint.setShader(new LinearGradient(thumbRect.left, thumbRect.top, thumbRect.left, thumbRect.bottom,
                        Color.argb(Math.round(150 * press), 255, 255, 255),
                        Color.argb(Math.round(70 * press), 255, 255, 255),
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(thumbRect, thr, thr, rimPaint);
                rimPaint.setShader(null);
            }
        }
    }
}
