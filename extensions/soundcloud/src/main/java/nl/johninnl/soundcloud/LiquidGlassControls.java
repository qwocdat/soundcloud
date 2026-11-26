package nl.johninnl.soundcloud;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
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

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Real AGSL + RenderNode iOS 26/27 Liquid Glass controls:
 * - Ios27GlassSwitch: pure white pill at rest (#34C759 green ON / #2C2C2E OFF) that morphs into
 *   an oversized crystal-clear AGSL refracting + rainbow-dispersing glass pill when held/dragged.
 * - Ios27GlassSlider: same real AGSL refracting glass pill thumb over the slider track.
 * - VietQrView: verified offline VietQR for VietinBank (970415) STK 0989249990.
 * - GlobalLiquidGlassStyler: replaces SoundCloud's stock Search bar, Back buttons, and buttons
 *   IN-PLACE with a real RenderNode + AGSL refracting Liquid Glass Drawable (zero overlay boxes).
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
     * Exact iOS 26/27 Liquid Glass Switch (matching 152003.jpg - 152009.jpg & 152011.mp4):
     * - Rest: crisp white pill inside dark gray (#2C2C2E) or vibrant iOS green (#34C759) track.
     * - Held/Dragged: white pill dissolves into an OVERSIZED crystal-clear AGSL refracting &
     *   7-band chromatic-dispersing glass pill that bends the green/gray track underneath!
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
        private final Paint innerDarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
            // Extra vertical & horizontal headroom so the oversized glass pill NEVER clips!
            setMeasuredDimension(Math.round(76f * density), Math.round(48f * density));
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

            float trackW = 58f * density;
            float trackH = 30f * density;
            float cxView = w * 0.5f;
            float cyView = h * 0.5f;
            trackRect.set(cxView - trackW * 0.5f, cyView - trackH * 0.5f,
                    cxView + trackW * 0.5f, cyView + trackH * 0.5f);
            float tr = trackH * 0.5f;

            // Blend #2C2C2E (OFF) -> #34C759 (iOS Green ON)
            int r = Math.round(44 + (52 - 44) * pos);
            int g = Math.round(44 + (199 - 44) * pos);
            int b = Math.round(46 + (89 - 46) * pos);
            trackPaint.setColor(Color.rgb(r, g, b));

            // 1. Draw track (and record into trackNode for real AGSL refraction when pressed!)
            if (Build.VERSION.SDK_INT >= 33 && press > 0.01f && glassShader != null && canvas.isHardwareAccelerated()) {
                if (trackNode == null) trackNode = new RenderNode("ios27_sw_track");
                trackNode.setPosition(0, 0, w, h);
                Canvas tc = trackNode.beginRecording(w, h);
                try {
                    tc.drawColor(Color.rgb(22, 22, 28)); // Card backdrop behind switch
                    tc.drawRoundRect(trackRect, tr, tr, trackPaint);
                } finally {
                    trackNode.endRecording();
                }
            }
            canvas.drawRoundRect(trackRect, tr, tr, trackPaint);

            // 2. Thumb geometry: rest (34x25dp white pill) -> pressed (46x38dp OVERSIZED crystal glass pill!)
            float restW = 34f * density;
            float restH = 25f * density;
            float thumbW = restW + 13f * density * press;
            float thumbH = restH + 13f * density * press;

            float minCx = trackRect.left + 2.5f * density + restW * 0.5f;
            float maxCx = trackRect.right - 2.5f * density - restW * 0.5f;
            float tcx = minCx + (maxCx - minCx) * pos;
            thumbRect.set(tcx - thumbW * 0.5f, cyView - thumbH * 0.5f,
                    tcx + thumbW * 0.5f, cyView + thumbH * 0.5f);
            float thr = thumbH * 0.5f;

            // 3. When pressed: real AGSL refracting & rainbow-dispersing Liquid Glass pill!
            if (Build.VERSION.SDK_INT >= 33 && press > 0.01f && trackNode != null
                    && glassShader != null && canvas.isHardwareAccelerated()) {
                if (glassThumbNode == null) glassThumbNode = new RenderNode("ios27_sw_glass");
                glassThumbNode.setPosition(0, 0, w, h);

                glassShader.setFloatUniform("size", thumbRect.width(), thumbRect.height());
                glassShader.setFloatUniform("offset", -thumbRect.left, -thumbRect.top);
                glassShader.setFloatUniform("cornerRadii", new float[]{thr, thr, thr, thr});
                glassShader.setFloatUniform("refractionHeight", 11f * density * press);
                glassShader.setFloatUniform("refractionAmount", -16f * density * press);
                glassShader.setFloatUniform("depthEffect", 1f);
                glassShader.setFloatUniform("chromaticAberration", 0.75f * press);
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

                // Subtle dark crystal rim depth (matches 152005.jpg & 152009.jpg!)
                innerDarkPaint.setStyle(Paint.Style.STROKE);
                innerDarkPaint.setStrokeWidth(3.5f * density * press);
                innerDarkPaint.setColor(Color.argb(Math.round(75 * press), 0, 0, 0));
                canvas.drawRoundRect(thumbRect, thr, thr, innerDarkPaint);
                canvas.restore();
            }

            // 4. White solid pill fades out as press -> 1 so thumb becomes pure transparent crystal glass!
            int whiteAlpha = Math.round(255f * Math.max(0f, 1f - press * 1.15f));
            if (whiteAlpha > 2) {
                thumbWhitePaint.setColor(Color.argb(whiteAlpha, 255, 255, 255));
                canvas.drawRoundRect(thumbRect, thr, thr, thumbWhitePaint);
            }

            // 5. Specular glass rim highlight when pressed
            if (press > 0.02f) {
                rimPaint.setStyle(Paint.Style.STROKE);
                rimPaint.setStrokeWidth(1.1f * density);
                rimPaint.setShader(new LinearGradient(thumbRect.left, thumbRect.top, thumbRect.left, thumbRect.bottom,
                        Color.argb(Math.round(220 * press), 255, 255, 255),
                        Color.argb(Math.round(130 * press), 255, 255, 255),
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
     * - Rest: crisp white pill thumb on capsule track.
     * - Held/Dragged: thumb expands into a crystal-clear AGSL refracting & rainbow-dispersing
     *   Liquid Glass pill that bends the track underneath!
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
        private final Paint innerDarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
            setMeasuredDimension(w, Math.round(46f * density));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float padH = 24f * density;
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

            float padH = 24f * density;
            float trackH = (7.5f + 2.5f * press) * density;
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
                    tc.drawColor(Color.rgb(22, 22, 28));
                    tc.drawRoundRect(trackRect, tr, tr, trackBgPaint);
                    tc.drawRoundRect(fillRect, tr, tr, trackFillPaint);
                } finally {
                    trackNode.endRecording();
                }
            }

            canvas.drawRoundRect(trackRect, tr, tr, trackBgPaint);
            canvas.drawRoundRect(fillRect, tr, tr, trackFillPaint);

            float thumbW = (30f + 14f * press) * density;
            float thumbH = (20f + 14f * press) * density;
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
                glassShader.setFloatUniform("refractionHeight", 10f * density * press);
                glassShader.setFloatUniform("refractionAmount", -15f * density * press);
                glassShader.setFloatUniform("depthEffect", 1f);
                glassShader.setFloatUniform("chromaticAberration", 0.75f * press);
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

                innerDarkPaint.setStyle(Paint.Style.STROKE);
                innerDarkPaint.setStrokeWidth(3f * density * press);
                innerDarkPaint.setColor(Color.argb(Math.round(70 * press), 0, 0, 0));
                canvas.drawRoundRect(thumbRect, thr, thr, innerDarkPaint);
                canvas.restore();
            }

            int whiteAlpha = Math.round(255f * Math.max(0f, 1f - press * 1.15f));
            if (whiteAlpha > 2) {
                thumbWhitePaint.setColor(Color.argb(whiteAlpha, 255, 255, 255));
                canvas.drawRoundRect(thumbRect, thr, thr, thumbWhitePaint);
            }

            if (press > 0.02f) {
                rimPaint.setStyle(Paint.Style.STROKE);
                rimPaint.setStrokeWidth(1.1f * density);
                rimPaint.setShader(new LinearGradient(thumbRect.left, thumbRect.top, thumbRect.left, thumbRect.bottom,
                        Color.argb(Math.round(220 * press), 255, 255, 255),
                        Color.argb(Math.round(130 * press), 255, 255, 255),
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(thumbRect, thr, thr, rimPaint);
                rimPaint.setShader(null);
            }
        }
    }

    /**
     * Real-time AGSL refracting Liquid Glass Drawable applied IN-PLACE to SoundCloud's
     * Search bar, Back buttons, and buttons (replaces stock opaque background completely).
     */
    private static final class LiveRefractingGlassDrawable extends Drawable {
        private final View target;
        private final float density;
        private final boolean dark;
        private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clipPath = new Path();
        private RenderNode bgNode;
        private RenderNode fxNode;
        private RuntimeShader shader;
        private boolean capturing = false;

        LiveRefractingGlassDrawable(View target, float density, boolean dark) {
            this.target = target;
            this.density = density;
            this.dark = dark;
            if (Build.VERSION.SDK_INT >= 33) {
                shader = new RuntimeShader(PILL_REFRACTION_DISPERSION_SHADER);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            if (capturing) return;
            int w = target.getWidth();
            int h = target.getHeight();
            if (w <= 0 || h <= 0) return;

            rect.set(density, density, w - density, h - density);
            float r = Math.min(rect.width(), rect.height()) * 0.5f;

            canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.clipPath(clipPath);

            if (Build.VERSION.SDK_INT >= 33 && shader != null && canvas.isHardwareAccelerated()) {
                captureBehind(w, h);
                if (bgNode != null && bgNode.hasDisplayList()) {
                    if (fxNode == null) fxNode = new RenderNode("sc_ui_glass_fx");
                    fxNode.setPosition(0, 0, w, h);

                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(1.4f);
                    RenderEffect chain = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                    chain = RenderEffect.createChainEffect(
                            RenderEffect.createBlurEffect(4f * density, 4f * density, Shader.TileMode.CLAMP), chain);

                    shader.setFloatUniform("size", rect.width(), rect.height());
                    shader.setFloatUniform("offset", -rect.left, -rect.top);
                    shader.setFloatUniform("cornerRadii", new float[]{r, r, r, r});
                    shader.setFloatUniform("refractionHeight", Math.min(r, 14f * density));
                    shader.setFloatUniform("refractionAmount", -Math.min(r, 14f * density));
                    shader.setFloatUniform("depthEffect", 0f);
                    shader.setFloatUniform("chromaticAberration", 0.35f);
                    chain = RenderEffect.createChainEffect(
                            RenderEffect.createRuntimeShaderEffect(shader, "content"), chain);
                    fxNode.setRenderEffect(chain);

                    Canvas fc = fxNode.beginRecording(w, h);
                    try {
                        fc.drawRenderNode(bgNode);
                    } finally {
                        fxNode.endRecording();
                    }
                    canvas.drawRenderNode(fxNode);
                }
            }

            surfacePaint.setColor(dark ? Color.argb(95, 28, 28, 36) : Color.argb(120, 245, 247, 252));
            canvas.drawRoundRect(rect, r, r, surfacePaint);
            canvas.restore();

            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(1f * density);
            rimPaint.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                    Color.argb(180, 255, 255, 255),
                    Color.argb(55, 255, 255, 255),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, r, r, rimPaint);
            rimPaint.setShader(null);
        }

        private void captureBehind(int w, int h) {
            View root = target.getRootView();
            if (root == null) return;
            View content = root.findViewById(android.R.id.content);
            if (content == null) content = root;
            if (bgNode == null) bgNode = new RenderNode("sc_ui_glass_bg");
            bgNode.setPosition(0, 0, w, h);

            int[] tLoc = new int[2];
            int[] cLoc = new int[2];
            target.getLocationInWindow(tLoc);
            content.getLocationInWindow(cLoc);

            capturing = true;
            Canvas c = bgNode.beginRecording(w, h);
            try {
                c.translate(cLoc[0] - tLoc[0], cLoc[1] - tLoc[1]);
                drawExcluding(c, content, target);
            } catch (Throwable ignored) {
            } finally {
                bgNode.endRecording();
                capturing = false;
            }
        }

        private boolean isAncestor(View cand, View leaf) {
            ViewParent p = leaf.getParent();
            while (p instanceof View) {
                if (p == cand) return true;
                p = p.getParent();
            }
            return false;
        }

        private void drawExcluding(Canvas c, View v, View ex) {
            if (v == ex || v.getVisibility() != View.VISIBLE) return;
            if (!isAncestor(v, ex)) {
                v.draw(c);
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                Drawable bg = vg.getBackground();
                if (bg != null) bg.draw(c);
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View ch = vg.getChildAt(i);
                    if (ch == ex || ch.getVisibility() != View.VISIBLE) continue;
                    int s = c.save();
                    c.translate(ch.getLeft() + ch.getTranslationX() - vg.getScrollX(),
                            ch.getTop() + ch.getTranslationY() - vg.getScrollY());
                    drawExcluding(c, ch, ex);
                    c.restoreToCount(s);
                }
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Global Liquid Glass UI transformer:
     * - Always upgrades SoundCloud's Search bar IN-PLACE to real AGSL refracting Liquid Glass
     *   (stripping the stock opaque search bar completely, never drawing a second box over it!).
     * - When "Bật LiquidGlass" is ON, also transforms back buttons & action buttons IN-PLACE.
     */
    public static final class GlobalLiquidGlassStyler {
        private static final WeakHashMap<View, Drawable> ORIGINAL_BG = new WeakHashMap<>();
        private static final WeakHashMap<View, Drawable> SEARCH_BG = new WeakHashMap<>();
        private static long lastScanMs = 0L;

        public static void applySearchAndGlobal(View root, View exTab, View exMini, View exAuthor, boolean globalEnabled) {
            if (root == null) return;
            if (!globalEnabled && !ORIGINAL_BG.isEmpty()) {
                restoreGlobal();
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastScanMs < 350L) return;
            lastScanMs = now;
            float density = root.getResources().getDisplayMetrics().density;
            boolean dark = (root.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            walk(root, exTab, exMini, exAuthor, density, dark, globalEnabled, 0);
        }

        public static void restoreGlobal() {
            for (View v : new ArrayList<>(ORIGINAL_BG.keySet())) {
                if (v != null) v.setBackground(ORIGINAL_BG.get(v));
            }
            ORIGINAL_BG.clear();
        }

        private static boolean isStyled(View v) {
            return ORIGINAL_BG.containsKey(v) || SEARCH_BG.containsKey(v);
        }

        private static boolean hasStyledAncestor(View v) {
            ViewParent p = v.getParent();
            int d = 0;
            while (p instanceof View && d < 4) {
                if (isStyled((View) p)) return true;
                p = p.getParent();
                d++;
            }
            return false;
        }

        private static void walk(View v, View ex1, View ex2, View ex3, float density, boolean dark, boolean globalEnabled, int depth) {
            if (v == null || v == ex1 || v == ex2 || v == ex3 || depth > 14 || v.getVisibility() != View.VISIBLE) return;

            if (!isStyled(v) && !hasStyledAncestor(v) && v.getWidth() > 0 && v.getHeight() > 0) {
                // Always transform SoundCloud's stock Search Bar IN-PLACE into real AGSL refracting Liquid Glass
                if (v instanceof EditText) {
                    View target = v;
                    ViewParent parent = v.getParent();
                    if (parent instanceof ViewGroup) {
                        ViewGroup pg = (ViewGroup) parent;
                        if (pg.getHeight() >= Math.round(34f * density) && pg.getHeight() <= Math.round(74f * density)) {
                            target = pg;
                            // Clear stock opaque background on EditText and its sibling icons inside the search bar
                            v.setBackgroundColor(Color.TRANSPARENT);
                            for (int i = 0; i < pg.getChildCount(); i++) {
                                View sib = pg.getChildAt(i);
                                if (sib != v && sib instanceof ImageButton) {
                                    sib.setBackgroundColor(Color.TRANSPARENT);
                                }
                            }
                        }
                    }
                    if (!SEARCH_BG.containsKey(target)) {
                        SEARCH_BG.put(target, target.getBackground());
                        target.setBackground(new LiveRefractingGlassDrawable(target, density, dark));
                    }
                    return;
                }

                if (globalEnabled) {
                    if (v instanceof Button && !(v instanceof CompoundButton)) {
                        ORIGINAL_BG.put(v, v.getBackground());
                        v.setBackground(new LiveRefractingGlassDrawable(v, density, dark));
                        return;
                    } else if (v instanceof ImageButton) {
                        int w = v.getWidth();
                        int h = v.getHeight();
                        if (w >= Math.round(32f * density) && w <= Math.round(60f * density)
                                && h >= Math.round(32f * density) && h <= Math.round(60f * density)) {
                            ORIGINAL_BG.put(v, v.getBackground());
                            v.setBackground(new LiveRefractingGlassDrawable(v, density, dark));
                            return;
                        }
                    }
                }
            }

            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    walk(vg.getChildAt(i), ex1, ex2, ex3, density, dark, globalEnabled, depth + 1);
                }
            }
        }
    }
}
