package nl.johninnl.soundcloud;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.BlendMode;
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
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * 1:1 port of NPatch FloatingGlassBottomBar + iOS 27 Gooey Metaball Search Fusion (152000.mp4):
 * - Unified AGSL Smooth-Min SDF shader fuses the TabBar pill and the draggable Search circle into
 *   one liquid mercury glass body with 7-band chromatic dispersion whenever they approach/collide!
 * - Firm weighted elastic drag on the right Search/Menu circle (up to ±78dp X, ±34dp Y).
 * - Replaces SoundCloud's stock Search bar in-place with a real AGSL refracting Liquid Glass bar.
 * - Full-screen "Tác giả" tab page mounted inside bar's parent right below bar.
 */
@SuppressLint("NewApi")
public final class LiquidGlassTabBar {

    private static final WeakHashMap<View, Controller> ATTACHED = new WeakHashMap<>();
    private static WeakReference<Controller> ACTIVE_CONTROLLER = new WeakReference<>(null);

    // Unified AGSL Smooth-Min Metaball Liquid Glass Shader:
    // - Smooth-union SDF creates a real liquid mercury bridge/neck between TabBar and Search circle.
    // - Single-pass inner glow & specular rim boost centered at `touchPoint` illuminating BOTH TabBar & Search.
    private static final String METABALL_LIQUID_GLASS_SHADER =
            "uniform shader content;\n" +
            "uniform float2 barCenter;\n" +
            "uniform float2 barHalfSize;\n" +
            "uniform float barRadius;\n" +
            "uniform float2 searchCenter;\n" +
            "uniform float searchRadius;\n" +
            "uniform float smoothK;\n" +
            "uniform float2 miniCenter;\n" +
            "uniform float2 miniHalfSize;\n" +
            "uniform float miniRadius;\n" +
            "uniform float miniSmoothK;\n" +
            "uniform float miniAlpha;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float chromaticAberration;\n" +
            "uniform float barAlpha;\n" +
            "layout(color) uniform half4 tintColor;\n" +
            "uniform float2 lightDir;\n" +
            "uniform float2 touchPoint;\n" +
            "uniform float touchGlow;\n" +
            "uniform float glowRadius;\n" +
            "float sdRoundedRect(float2 p, float2 b, float r) {\n" +
            "    float2 q = abs(p) - (b - float2(r));\n" +
            "    return length(max(q, 0.0)) - r + min(max(q.x, q.y), 0.0);\n" +
            "}\n" +
            "float smin(float a, float b, float k) {\n" +
            "    if (k <= 0.001) return min(a, b);\n" +
            "    float h = max(k - abs(a - b), 0.0) / k;\n" +
            "    return min(a, b) - h * h * k * 0.25;\n" +
            "}\n" +
            "float fieldSd(float2 p) {\n" +
            "    float d = length(p - searchCenter) - searchRadius;\n" +
            "    if (barAlpha > 0.02) {\n" +
            "        float dBar = sdRoundedRect(p - barCenter, barHalfSize, barRadius);\n" +
            "        d = smin(d, dBar, smoothK);\n" +
            "    }\n" +
            "    if (miniAlpha > 0.02) {\n" +
            "        float dMini = sdRoundedRect(p - miniCenter, miniHalfSize, miniRadius);\n" +
            "        d = smin(d, dMini, miniSmoothK);\n" +
            "    }\n" +
            "    return d;\n" +
            "}\n" +
            "float circleMap(float x) { return 1.0 - sqrt(max(0.0, 1.0 - x * x)); }\n" +
            "half4 main(float2 coord) {\n" +
            "    float sd = fieldSd(coord);\n" +
            "    float edgeMask = smoothstep(1.2, -1.2, sd);\n" +
            "    if (edgeMask <= 0.001) return half4(0.0);\n" +
            "    float eps = 1.5;\n" +
            "    float2 grad = normalize(float2(\n" +
            "        fieldSd(coord + float2(eps, 0.0)) - fieldSd(coord - float2(eps, 0.0)),\n" +
            "        fieldSd(coord + float2(0.0, eps)) - fieldSd(coord - float2(0.0, eps))\n" +
            "    ) + float2(0.0001));\n" +
            "    half4 glass;\n" +
            "    if (-sd < refractionHeight && refractionHeight > 0.5) {\n" +
            "        float edgeT = clamp(1.0 - (-sd / refractionHeight), 0.0, 1.0);\n" +
            "        float d = circleMap(edgeT) * refractionAmount;\n" +
            "        float2 rc = coord + d * grad;\n" +
            "        if (chromaticAberration > 0.01) {\n" +
            "            float2 dc = d * grad * chromaticAberration * 0.50;\n" +
            "            half4 col = half4(0.0);\n" +
            "            half4 red = content.eval(rc + dc); col.r += red.r / 3.5; col.a += red.a / 7.0;\n" +
            "            half4 org = content.eval(rc + dc * 0.6667); col.r += org.r / 3.5; col.g += org.g / 7.0; col.a += org.a / 7.0;\n" +
            "            half4 yel = content.eval(rc + dc * 0.3333); col.r += yel.r / 3.5; col.g += yel.g / 3.5; col.a += yel.a / 7.0;\n" +
            "            half4 grn = content.eval(rc); col.g += grn.g / 3.5; col.a += grn.a / 7.0;\n" +
            "            half4 cyn = content.eval(rc - dc * 0.3333); col.g += cyn.g / 3.5; col.b += cyn.b / 3.0; col.a += cyn.a / 7.0;\n" +
            "            half4 blu = content.eval(rc - dc * 0.6667); col.b += blu.b / 3.0; col.a += blu.a / 7.0;\n" +
            "            half4 pur = content.eval(rc - dc); col.r += pur.r / 7.0; col.b += pur.b / 3.0; col.a += pur.a / 7.0;\n" +
            "            glass = col;\n" +
            "        } else {\n" +
            "            glass = content.eval(rc);\n" +
            "        }\n" +
            "    } else {\n" +
            "        glass = content.eval(coord);\n" +
            "    }\n" +
            "    // Blend surface glass tint\n" +
            "    half3 rgb = mix(glass.rgb, tintColor.rgb, tintColor.a);\n" +
            "    // Touch-centered inner glow + specular bloom spreading across BOTH Search circle & TabBar\n" +
            "    float distTouch = distance(coord, touchPoint);\n" +
            "    float radialGlow = clamp(exp(-distTouch / max(glowRadius, 1.0)) * touchGlow, 0.0, 1.0);\n" +
            "    rgb += mix(tintColor.rgb, half3(0.92, 0.94, 0.98), half(0.55)) * half(clamp(radialGlow * 0.30, 0.0, 0.30));\n" +
            "    // Specular BloomStroke rim along the fused metaball contour + RGB prism tint at edge\n" +
            "    float rimBand = smoothstep(2.3, 0.15, abs(sd));\n" +
            "    float spec = pow(max(dot(grad, lightDir), 0.0), 1.6) * 0.72 + pow(max(dot(grad, -lightDir), 0.0), 2.0) * 0.28 + 0.18;\n" +
            "    spec += radialGlow * 0.95;\n" +
            "    half3 rimColor = half3(1.0) + half3(0.18 * grad.x, 0.10 * -grad.y, -0.18 * grad.x) * half(chromaticAberration);\n" +
            "    rgb += rimColor * half(rimBand * spec);\n" +
            "    return half4(clamp(rgb, half3(0.0), half3(1.0)) * half(edgeMask), half(edgeMask));\n" +
            "}\n";

    private static final String INTERACTIVE_HIGHLIGHT_SHADER =
            "uniform float2 size;\n" +
            "layout(color) uniform half4 color;\n" +
            "uniform float radius;\n" +
            "uniform float2 position;\n" +
            "half4 main(float2 coord) {\n" +
            "    float dist = distance(coord, position);\n" +
            "    float intensity = smoothstep(radius, radius * 0.5, dist);\n" +
            "    return color * intensity;\n" +
            "}\n";

    public static void attach(View view) {
        if (!(view instanceof ViewGroup)) return;
        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(view)) return;
            Controller controller = new Controller((ViewGroup) view);
            ATTACHED.put(view, controller);
            ACTIVE_CONTROLLER = new WeakReference<>(controller);
            controller.install();
        }
    }

    public static View wrapSettingsView(View original) {
        if (original == null) return null;
        try {
            Context ctx = original.getContext();
            float density = ctx.getResources().getDisplayMetrics().density;

            final FrameLayout wrapper = new FrameLayout(ctx);
            wrapper.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            wrapper.addView(original, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            TextView btn = new TextView(ctx);
            btn.setText("✦  Cài đặt TabBar Liquid Glass");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14f);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setGravity(Gravity.CENTER);
            int padH = Math.round(20f * density);
            int padV = Math.round(13f * density);
            btn.setPadding(padH, padV, padH, padV);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(232, 26, 26, 32));
            bg.setCornerRadius(28f * density);
            bg.setStroke(Math.round(1.2f * density), Color.argb(145, 255, 255, 255));
            btn.setBackground(bg);
            btn.setElevation(8f * density);

            final View[] overlayPageRef = new View[1];
            btn.setOnClickListener(v -> {
                if (overlayPageRef[0] != null && overlayPageRef[0].getParent() == wrapper) return;
                View page = LiquidGlassAuthorPage.createPage(ctx, true, () -> {
                    if (overlayPageRef[0] != null) {
                        wrapper.removeView(overlayPageRef[0]);
                        overlayPageRef[0] = null;
                    }
                }, () -> {
                    Controller c = ACTIVE_CONTROLLER.get();
                    if (c != null) c.onSettingsUpdated();
                });
                overlayPageRef[0] = page;
                wrapper.addView(page, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = Math.round(28f * density);
            wrapper.addView(btn, lp);
            return wrapper;
        } catch (Throwable ignored) {
            return original;
        }
    }

    private LiquidGlassTabBar() {}

    private static final class AuthorIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path bodyPath = new Path();
        private final float density;

        AuthorIconDrawable(float density) {
            this.density = density;
            paint.setColor(Color.argb(225, 235, 235, 240));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.9f * density);
        }

        @Override
        public void draw(Canvas canvas) {
            int w = getBounds().width();
            int h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            float cx = getBounds().centerX();
            float cy = getBounds().centerY();
            float s = Math.min(w, h);

            // Head balanced with neighboring 24dp tab icons
            float headR = s * 0.175f;
            float headCy = cy - s * 0.23f;
            canvas.drawCircle(cx, headCy, headR, paint);

            // Longer torso/shoulders body extending cleanly to bottom baseline
            float bodyTop = cy + s * 0.02f;
            float bodyBottom = cy + s * 0.43f;
            float halfW = s * 0.33f;
            bodyPath.reset();
            bodyPath.moveTo(cx - halfW, bodyBottom);
            bodyPath.cubicTo(
                    cx - halfW, bodyTop + s * 0.04f,
                    cx - halfW * 0.55f, bodyTop,
                    cx, bodyTop
            );
            bodyPath.cubicTo(
                    cx + halfW * 0.55f, bodyTop,
                    cx + halfW, bodyTop + s * 0.04f,
                    cx + halfW, bodyBottom
            );
            canvas.drawPath(bodyPath, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return Math.round(24f * density); }
        @Override public int getIntrinsicHeight() { return Math.round(24f * density); }
    }

    private static final class Controller implements
            ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener,
            View.OnLayoutChangeListener,
            Choreographer.FrameCallback,
            SensorEventListener {

        private final ViewGroup bar;
        private final float density;
        private final float touchSlopSq;

        private final GlassBackgroundDrawable bgDrawable;
        private final GlassOverlayDrawable fgDrawable;
        private final MiniPlayerGlassDrawable miniBgDrawable;

        private RenderNode backdropNode;
        private RenderNode metaballGlassNode;
        private RenderNode pillCombinedNode;
        private RenderNode pillGlassNode;

        private View authorPageView;
        private boolean authorPageVisible = false;

        private View miniPlayerSheet;
        private View miniPlayerPill;
        private RenderNode miniBackdropNode;
        private RenderNode miniGlassNode;
        private RuntimeShader miniLensShader;
        private final RectF miniRect = new RectF();
        private final Path miniClipPath = new Path();
        private long lastMiniSearchMs = 0L;

        private RuntimeShader metaballShader;
        private RuntimeShader pillDispersionShader;
        private RuntimeShader highlightShader;

        private SensorManager sensorManager;
        private Sensor gravitySensor;
        private float gravityX = 0f;
        private float gravityY = -9.81f;

        private boolean attached = false;
        private boolean capturing = false;
        private boolean frameScheduled = false;
        private long lastFrameNs = 0L;
        private boolean windowHooked = false;

        private boolean globalGlassEnabled = false;
        private boolean scrollCollapseEnabled = true;
        private float transparencyPct = 60f;
        private float refractionDp = 24f;
        private float blurDp = 4f;
        private float dispersionAmount = 0.5f;

        private View authorTabView = null;
        private int authorTabVisibleIndex = -1;
        private int searchTabItemId = View.NO_ID;
        private boolean searchTabSelected = false;
        private final List<View> visibleTabs = new ArrayList<>();
        private final List<RectF> tabRects = new ArrayList<>();

        private int currentIndex = 0;
        private float springValue = 0f;
        private float springTarget = 0f;
        private float springVel = 0f;

        private float pressProgress = 0f;
        private float pressTarget = 0f;
        private float pressVel = 0f;

        private float scaleX = 1f;
        private float scaleXTarget = 1f;
        private float scaleXVel = 0f;

        private float scaleY = 1f;
        private float scaleYTarget = 1f;
        private float scaleYVel = 0f;

        private float dragVelocity = 0f;
        private float panelOffsetPx = 0f;
        private float rawOffsetAccum = 0f;

        // Firm, weighted iOS 27 metaball drag on Search circle (allows dragging into TabBar to melt!)
        private boolean trackingSearchBtn = false;
        // Tunable named constants for firm, weighted iOS 27 Search drag (LỖI 2)
        private static final float DRAG_START_SLOP_DP = 12f;
        private static final float DRAG_RESISTANCE = 0.42f;
        private static final float MAX_PULL_MIN_DP = 84f;
        private static final float MAX_PULL_MAX_DP = 120f;
        private static final float MERGE_THRESHOLD_DP = 9f;

        private static final int SEARCH_IDLE = 0;
        private static final int SEARCH_PRESSED = 1;
        private static final int SEARCH_DRAGGING = 2;
        private static final int SEARCH_MERGED = 3;
        private static final int SEARCH_RELEASING = 4;
        private int searchState = SEARCH_IDLE;
        private float searchDownX = 0f;
        private float searchDownY = 0f;
        private float searchTouchX = 0f;
        private float searchTouchY = 0f;

        // Independent spring-animated merge progress for BOTH TabBar and Mini-Player (LỖI 4)
        private float barMergeProgress = 0f;
        private float barMergeTarget = 0f;
        private float barMergeVel = 0f;
        private float miniMergeProgress = 0f;
        private float miniMergeTarget = 0f;
        private float miniMergeVel = 0f;
        private float miniMergeGlow = 0f;
        private float searchPress = 0f;
        private float searchPressTarget = 0f;
        private float searchPressVel = 0f;
        private float searchOffsetX = 0f;
        private float searchOffsetY = 0f;
        private float searchOffsetTargetX = 0f;
        private float searchOffsetTargetY = 0f;
        private float searchOffsetVelX = 0f;
        private float searchOffsetVelY = 0f;

        private float collapseProgress = 0f;
        private float collapseTarget = 0f;
        private float collapseVel = 0f;

        private boolean scrollTracking = false;
        private float scrollAnchorY = 0f;

        private boolean directBarCapture = false;
        private boolean trackingTouch = false;
        private boolean isDragging = false;
        private float downX = 0f;
        private float downY = 0f;
        private float lastTouchX = 0f;
        private int touchStartIndex = 0;
        private int touchDownTargetIndex = 0;

        private final RectF outerRect = new RectF();
        private final RectF searchRect = new RectF();
        private final RectF pillRect = new RectF();
        private final Path clipPath = new Path();

        Controller(ViewGroup bar) {
            this.bar = bar;
            this.density = bar.getResources().getDisplayMetrics().density;
            float slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();
            this.touchSlopSq = slop * slop;
            this.bgDrawable = new GlassBackgroundDrawable();
            this.fgDrawable = new GlassOverlayDrawable();
            this.miniBgDrawable = new MiniPlayerGlassDrawable();
            loadSettings();

            if (Build.VERSION.SDK_INT >= 33) {
                metaballShader = new RuntimeShader(METABALL_LIQUID_GLASS_SHADER);
                pillDispersionShader = new RuntimeShader(LiquidGlassControls.PILL_REFRACTION_DISPERSION_SHADER);
                highlightShader = new RuntimeShader(INTERACTIVE_HIGHLIGHT_SHADER);
                miniLensShader = new RuntimeShader(LiquidGlassControls.PILL_REFRACTION_DISPERSION_SHADER);
            }
        }

        private void loadSettings() {
            Context ctx = bar.getContext();
            globalGlassEnabled = LiquidGlassAuthorPage.isGlobalGlassEnabled(ctx);
            scrollCollapseEnabled = LiquidGlassAuthorPage.isScrollCollapseEnabled(ctx);
            transparencyPct = LiquidGlassAuthorPage.getTransparencyPct(ctx);
            refractionDp = LiquidGlassAuthorPage.getRefractionDp(ctx);
            blurDp = LiquidGlassAuthorPage.getBlurDp(ctx);
            dispersionAmount = LiquidGlassAuthorPage.getDispersion(ctx);
        }

        void onSettingsUpdated() {
            loadSettings();
            if (!scrollCollapseEnabled) {
                collapseTarget = 0f;
            }
            scheduleFrame();
        }

        void install() {
            bar.setWillNotDraw(false);
            bar.setClipChildren(false);
            bar.setClipToPadding(false);
            bar.setElevation(0f);

            try {
                bar.getClass().getMethod("setItemActiveIndicatorEnabled", boolean.class).invoke(bar, false);
            } catch (Throwable ignored) {
            }

            bar.setBackground(bgDrawable);
            bar.getOverlay().add(fgDrawable);
            bar.addOnAttachStateChangeListener(this);
            bar.addOnLayoutChangeListener(this);

            if (bar.isAttachedToWindow()) {
                onViewAttachedToWindow(bar);
            }
        }

        private void disableAncestorClipping() {
            ViewParent p = bar.getParent();
            int depth = 0;
            while (p instanceof ViewGroup && depth < 10) {
                ViewGroup vg = (ViewGroup) p;
                vg.setClipChildren(false);
                vg.setClipToPadding(false);
                if (vg.getId() == android.R.id.content) break;
                p = vg.getParent();
                depth++;
            }
        }

        private Activity findActivity(Context ctx) {
            Context cur = ctx;
            int guard = 0;
            while (cur instanceof ContextWrapper && guard < 12) {
                if (cur instanceof Activity) return (Activity) cur;
                cur = ((ContextWrapper) cur).getBaseContext();
                guard++;
            }
            return null;
        }

        private void hookWindowTouch() {
            if (windowHooked) return;
            Activity act = findActivity(bar.getContext());
            if (act == null) return;
            Window win = act.getWindow();
            if (win == null) return;
            final Window.Callback orig = win.getCallback();
            if (orig == null) return;
            try {
                Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(
                        Window.Callback.class.getClassLoader(),
                        new Class<?>[]{Window.Callback.class},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object p, Method method, Object[] args) throws Throwable {
                                String name = method.getName();
                                if ("dispatchTouchEvent".equals(name) && args != null && args.length == 1
                                        && args[0] instanceof MotionEvent) {
                                    if (interceptWindowTouch((MotionEvent) args[0])) {
                                        return Boolean.TRUE;
                                    }
                                } else if ("dispatchKeyEvent".equals(name) && args != null && args.length == 1
                                        && args[0] instanceof KeyEvent) {
                                    KeyEvent ke = (KeyEvent) args[0];
                                    if (authorPageVisible && ke.getKeyCode() == KeyEvent.KEYCODE_BACK
                                            && ke.getAction() == KeyEvent.ACTION_UP) {
                                        hideAuthorPage();
                                        return Boolean.TRUE;
                                    }
                                }
                                return method.invoke(orig, args);
                            }
                        }
                );
                win.setCallback(proxy);
                windowHooked = true;
            } catch (Throwable ignored) {
            }
        }

        private void showAuthorPage() {
            ViewParent bp = bar.getParent();
            ViewGroup host = (bp instanceof ViewGroup) ? (ViewGroup) bp : null;
            if (host == null) {
                View root = bar.getRootView();
                if (root != null) host = root.findViewById(android.R.id.content);
            }
            if (host == null) return;

            if (authorPageView == null || authorPageView.getParent() != host) {
                authorPageView = LiquidGlassAuthorPage.createPage(
                        bar.getContext(),
                        false,
                        this::hideAuthorPage,
                        this::onSettingsUpdated
                );
                authorPageView.setZ(0f);
                int barIdx = host.indexOfChild(bar);
                ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                if (barIdx >= 0) {
                    host.addView(authorPageView, barIdx, lp);
                } else {
                    host.addView(authorPageView, lp);
                }
            }
            authorPageVisible = true;
            authorPageView.setVisibility(View.VISIBLE);
            authorPageView.setAlpha(0f);
            authorPageView.animate().alpha(1f).setDuration(160L).start();
            bar.setZ(dpf(28f));
            bar.bringToFront();
            collapseTarget = 0f;
            scheduleFrame();
        }

        private void hideAuthorPage() {
            if (!authorPageVisible) return;
            authorPageVisible = false;
            if (authorPageView != null) {
                authorPageView.setVisibility(View.GONE);
            }
            syncTabState(false);
        }

        private boolean isMiniPlayerCollapsedAndVisible() {
            if (miniPlayerPill == null || !miniPlayerPill.isShown() || miniPlayerPill.getWidth() <= 0) {
                return false;
            }
            if (miniPlayerSheet != null && miniPlayerSheet.isShown()) {
                View root = bar.getRootView();
                int rootH = root != null ? root.getHeight() : 0;
                if (rootH > 0 && miniPlayerSheet.getTop() < rootH / 2) return false;
            }
            return true;
        }

        private boolean isTouchInsideView(View v, float rawX, float rawY) {
            if (v == null || !v.isAttachedToWindow() || !v.isShown() || v.getWidth() <= 0) return false;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
        }

        private boolean interceptWindowTouch(MotionEvent ev) {
            if (!attached || !bar.isShown() || bar.getWidth() <= 0) return false;
            updateGeometry();

            float rawX = ev.getRawX();
            float rawY = ev.getRawY();
            int action = ev.getActionMasked();

            int[] barLoc = new int[2];
            bar.getLocationOnScreen(barLoc);
            float localX = rawX - barLoc[0];
            float localY = rawY - barLoc[1];

            if (directBarCapture) {
                handleDirectBarTouch(action, localX, localY);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    directBarCapture = false;
                }
                return true;
            }

            if (action == MotionEvent.ACTION_DOWN && isTouchInsideView(bar, rawX, rawY)) {
                boolean onRightCircle = isInsideSearchCircle(localX, localY);
                boolean onLeftPill = localX >= outerRect.left - dpf(4f) && localX <= outerRect.right + dpf(4f)
                        && localY >= outerRect.top - dpf(6f) && localY <= outerRect.bottom + dpf(6f);

                if (onRightCircle) {
                    directBarCapture = true;
                    handleDirectBarTouch(action, localX, localY);
                    return true;
                }

                if (onLeftPill) {
                    if (collapseProgress > 0.5f) return false;
                    directBarCapture = true;
                    handleDirectBarTouch(action, localX, localY);
                    return true;
                }
            }

            if (scrollCollapseEnabled && !authorPageVisible) {
                boolean miniVisible = isMiniPlayerCollapsedAndVisible();
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        if (isTouchInsideView(bar, rawX, rawY) || (miniVisible && isTouchInsideView(miniPlayerPill, rawX, rawY))) {
                            scrollTracking = false;
                        } else {
                            scrollTracking = true;
                            scrollAnchorY = rawY;
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (scrollTracking) {
                            float totalDy = rawY - scrollAnchorY;
                            if (totalDy < -dpf(28f)) {
                                scrollAnchorY = rawY;
                                if (collapseTarget < 0.5f) {
                                    collapseTarget = 1f;
                                    scheduleFrame();
                                }
                            } else if (totalDy > dpf(24f)) {
                                scrollAnchorY = rawY;
                                if (collapseTarget > 0.5f) {
                                    collapseTarget = 0f;
                                    scheduleFrame();
                                }
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        scrollTracking = false;
                        break;
                }
            }

            return false;
        }

        private void handleDirectBarTouch(int action, float x, float y) {
            // === Strict State Machine for Search Circle: IDLE -> PRESSED -> DRAGGING -> MERGED -> RELEASING ===
            if (action == MotionEvent.ACTION_DOWN && isInsideSearchCircle(x, y)) {
                trackingSearchBtn = true;
                searchState = SEARCH_PRESSED; // Glow & slight scale ONLY — never merges on press!
                searchDownX = x;
                searchDownY = y;
                searchTouchX = x;
                searchTouchY = y;
                searchPressTarget = 1f;
                searchOffsetTargetX = 0f;
                searchOffsetTargetY = 0f;
                scheduleFrame();
                return;
            }
            if (trackingSearchBtn) {
                if (action == MotionEvent.ACTION_MOVE) {
                    searchTouchX = x;
                    searchTouchY = y;
                    float dx = x - searchDownX;
                    float dy = y - searchDownY;
                    float slopPx = dpf(DRAG_START_SLOP_DP);
                    float distSq = dx * dx + dy * dy;
                    if (searchState == SEARCH_PRESSED && distSq > slopPx * slopPx) {
                        searchState = SEARCH_DRAGGING;
                    }
                    if (searchState == SEARCH_DRAGGING || searchState == SEARCH_MERGED) {
                        // Weighted 2D vector rubber-band: heavy resistance (DRAG_RESISTANCE = 0.42),
                        // progressive exponential resistance maxPull * (1 - exp(-raw / maxPull)),
                        // MAX_PULL = 84..120dp so Search can firmly reach other tabs & Mini-Player!
                        float targetReach = Math.max(outerRect.width() * 0.35f, dpf(MAX_PULL_MIN_DP));
                        float maxPull = Math.max(dpf(MAX_PULL_MIN_DP), Math.min(dpf(MAX_PULL_MAX_DP), targetReach + dpf(12f)));
                        float len = (float) Math.hypot(dx, dy);
                        if (len > 0.001f) {
                            float effectiveLen = Math.max(0f, len - slopPx * 0.5f);
                            float rawWeighted = effectiveLen * DRAG_RESISTANCE;
                            float pulled = maxPull * (1f - (float) Math.exp(-rawWeighted / maxPull));
                            float ox = (dx / len) * pulled;
                            float oy = (dy / len) * pulled;
                            // Keep circle inside safe right & bottom screen edges (never clipped)
                            float expandedRad = (searchRect.height() * 0.5f) * 1.12f;
                            float safeMaxRight = Math.max(0f, (bar.getWidth() - dpf(4f)) - (searchRect.centerX() + expandedRad));
                            float safeMaxDown = Math.max(0f, (bar.getHeight() - dpf(3f)) - (searchRect.centerY() + expandedRad));
                            searchOffsetTargetX = Math.min(safeMaxRight, ox);
                            searchOffsetTargetY = Math.min(safeMaxDown, oy);
                        }
                    }
                    scheduleFrame();
                    return;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    boolean wasTap = (searchState == SEARCH_PRESSED);
                    trackingSearchBtn = false;
                    searchState = SEARCH_RELEASING;
                    searchPressTarget = 0f;
                    searchOffsetTargetX = 0f;
                    searchOffsetTargetY = 0f;
                    if (action == MotionEvent.ACTION_UP && wasTap) {
                        if (collapseProgress > 0.4f || collapseTarget > 0.5f) {
                            collapseTarget = 0f;
                        } else {
                            hideAuthorPage();
                            triggerSearchTab();
                        }
                    }
                    scheduleFrame();
                }
                return;
            }

            syncTabState(false);
            int count = visibleTabs.size();
            if (count <= 0) return;

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    trackingTouch = true;
                    isDragging = false;
                    downX = x;
                    downY = y;
                    lastTouchX = x;
                    touchStartIndex = currentIndex;
                    touchDownTargetIndex = indexAt(x);
                    springTarget = touchDownTargetIndex;
                    pressTarget = 1f;
                    scaleXTarget = 78f / 56f;
                    scaleYTarget = 78f / 56f;
                    scheduleFrame();
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (!trackingTouch) return;
                    float dx = x - downX;
                    float dy = y - downY;
                    if (!isDragging && (dx * dx + dy * dy) > touchSlopSq) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        float stepDx = x - lastTouchX;
                        float tabW = avgTabWidth();
                        boolean isLtr = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
                        float deltaIdx = (stepDx / tabW) * (isLtr ? 1f : -1f);
                        springTarget = Math.max(0f, Math.min(count - 1f, springTarget + deltaIdx));
                        rawOffsetAccum += stepDx;
                        float fraction = Math.max(-1f, Math.min(1f, rawOffsetAccum / Math.max(1f, outerRect.width())));
                        float easeOut = 1f - (1f - Math.abs(fraction)) * (1f - Math.abs(fraction));
                        panelOffsetPx = dp(4f) * Math.signum(fraction) * easeOut;
                        dragVelocity = deltaIdx * 12f;
                        scheduleFrame();
                    }
                    lastTouchX = x;
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!trackingTouch) return;
                    trackingTouch = false;
                    int target = action == MotionEvent.ACTION_CANCEL
                            ? touchStartIndex
                            : Math.max(0, Math.min(count - 1, Math.round(springTarget)));
                    pressTarget = 0f;
                    scaleXTarget = 1f;
                    scaleYTarget = 1f;
                    rawOffsetAccum = 0f;
                    panelOffsetPx = 0f;
                    dragVelocity = 0f;

                    if (action == MotionEvent.ACTION_UP) {
                        int clickIdx = isDragging ? target : touchDownTargetIndex;
                        currentIndex = clickIdx;
                        springTarget = clickIdx;
                        collapseTarget = 0f;
                        if (clickIdx == authorTabVisibleIndex) {
                            searchTabSelected = false;
                            showAuthorPage();
                        } else {
                            hideAuthorPage();
                            dispatchTabClick(clickIdx);
                        }
                    } else {
                        springTarget = touchStartIndex;
                    }
                    isDragging = false;
                    scheduleFrame();
                    break;
            }
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            if (attached) return;
            attached = true;
            disableAncestorClipping();
            hookWindowTouch();
            bar.setBackground(bgDrawable);
            bar.getViewTreeObserver().addOnPreDrawListener(this);
            initSensors();
            syncTabState(true);
            locateAndStyleMiniPlayer();
            scheduleFrame();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            attached = false;
            bar.getViewTreeObserver().removeOnPreDrawListener(this);
            if (frameScheduled) {
                Choreographer.getInstance().removeFrameCallback(this);
                frameScheduled = false;
            }
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            disableAncestorClipping();
            if (bar.getBackground() != bgDrawable) {
                bar.setBackground(bgDrawable);
            }
            syncTabState(false);
        }

        private void initSensors() {
            try {
                sensorManager = (SensorManager) bar.getContext().getSystemService(Context.SENSOR_SERVICE);
                if (sensorManager != null) {
                    gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
                    if (gravitySensor != null) {
                        sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_UI);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
                gravityX = event.values[0];
                gravityY = event.values[1];
                bar.invalidate();
                if (miniPlayerPill != null) miniPlayerPill.invalidate();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        private void locateAndStyleMiniPlayer() {
            if (miniPlayerPill != null && miniPlayerPill.isAttachedToWindow()) {
                if (miniPlayerPill.getBackground() != miniBgDrawable) {
                    miniPlayerPill.setBackground(miniBgDrawable);
                }
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastMiniSearchMs < 400L) return;
            lastMiniSearchMs = now;

            View root = bar.getRootView();
            if (root == null) return;
            View content = root.findViewById(android.R.id.content);
            if (content == null) content = root;

            View anchor = findMiniPlayerAnchor(content);
            if (anchor == null) return;

            View pill = null;
            ViewParent p = anchor.getParent();
            while (p instanceof View) {
                View pv = (View) p;
                int h = pv.getHeight();
                if (h >= dp(40f) && h <= dp(96f) && pv.getWidth() > dp(200f)) {
                    pill = pv;
                    if (pv.getBackground() != null) break;
                }
                if (h > dp(120f)) break;
                p = pv.getParent();
            }
            if (pill == null || pill == bar) return;

            View sheet = pill;
            ViewParent barParent = bar.getParent();
            ViewParent sp = pill.getParent();
            int depth = 0;
            while (sp instanceof View && depth < 12) {
                View sv = (View) sp;
                if (sv.getParent() == barParent) {
                    sheet = sv;
                    break;
                }
                if (sv.getClass().getName().contains("CoordinatorLayout")) break;
                sheet = sv;
                sp = sv.getParent();
                depth++;
            }

            miniPlayerPill = pill;
            miniPlayerSheet = sheet;

            pill.setWillNotDraw(false);
            if (pill instanceof ViewGroup) {
                ((ViewGroup) pill).setClipChildren(false);
                ((ViewGroup) pill).setClipToPadding(false);
            }
            pill.setElevation(0f);
            pill.setBackground(miniBgDrawable);

            ViewParent ap = pill.getParent();
            int d = 0;
            while (ap instanceof ViewGroup && d < 8) {
                ((ViewGroup) ap).setClipChildren(false);
                ((ViewGroup) ap).setClipToPadding(false);
                ap = ap.getParent();
                d++;
            }
        }

        private View findMiniPlayerAnchor(View v) {
            if (v == null || v == bar || v == authorPageView) return null;
            String cls = v.getClass().getName();
            if (cls.endsWith("PlayPauseButton") && v.isShown()) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                View root = bar.getRootView();
                int rootH = root != null ? root.getHeight() : 0;
                if (rootH > 0 && loc[1] > rootH * 0.65f) return v;
            }
            int id = v.getId();
            if (id != View.NO_ID) {
                try {
                    String name = v.getResources().getResourceEntryName(id);
                    if ("footer_follow_button".equals(name) || "footer_like_button".equals(name)) return v;
                } catch (Throwable ignored) {
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View res = findMiniPlayerAnchor(vg.getChildAt(i));
                    if (res != null) return res;
                }
            }
            return null;
        }

        private ViewGroup getMenuView() {
            if (bar.getChildCount() > 0 && bar.getChildAt(0) instanceof ViewGroup) {
                return (ViewGroup) bar.getChildAt(0);
            }
            return null;
        }

        private int getTabItemId(View child) {
            if (child == null) return View.NO_ID;
            try {
                Method getItemData = child.getClass().getMethod("getItemData");
                Object itemData = getItemData.invoke(child);
                if (itemData != null) {
                    Method getItemId = itemData.getClass().getMethod("getItemId");
                    Object idObj = getItemId.invoke(itemData);
                    if (idObj instanceof Integer && (Integer) idObj != 0) return (Integer) idObj;
                }
            } catch (Throwable ignored) {
            }
            return child.getId();
        }

        private boolean isTabViewSelected(View child) {
            if (child == null) return false;
            if (child.isSelected() || child.isActivated()) return true;
            try {
                Method getItemData = child.getClass().getMethod("getItemData");
                Object itemData = getItemData.invoke(child);
                if (itemData != null) {
                    Method isChecked = itemData.getClass().getMethod("isChecked");
                    Object checked = isChecked.invoke(itemData);
                    if (checked instanceof Boolean && (Boolean) checked) return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        private boolean isSearchTabView(View child, int index, int totalCount) {
            if (child == null) return false;
            CharSequence cd = child.getContentDescription();
            if (cd != null) {
                String s = cd.toString().toLowerCase(Locale.ROOT);
                if (s.contains("search") || s.contains("tìm kiếm") || s.contains("tim kiem")) return true;
            }
            int itemId = getTabItemId(child);
            if (itemId != View.NO_ID) {
                try {
                    String entry = child.getResources().getResourceEntryName(itemId).toLowerCase(Locale.ROOT);
                    if (entry.contains("search")) return true;
                } catch (Throwable ignored) {
                }
            }
            if (containsSearchText(child)) return true;
            return totalCount >= 5 && index == 3;
        }

        private boolean containsSearchText(View v) {
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null) {
                    String s = t.toString().toLowerCase(Locale.ROOT);
                    if (s.contains("search") || s.contains("tìm kiếm") || s.contains("tim kiem")) return true;
                }
            } else if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    if (containsSearchText(vg.getChildAt(i))) return true;
                }
            }
            return false;
        }

        private void styleAsAuthorTab(View tabView) {
            if (tabView == null) return;
            if (tabView.getVisibility() != View.VISIBLE) tabView.setVisibility(View.VISIBLE);
            applyAuthorTextAndIcon(tabView);
        }

        private void applyAuthorTextAndIcon(View v) {
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                if (!"Tác giả".equals(tv.getText())) tv.setText("Tác giả");
            } else if (v instanceof ImageView) {
                ImageView iv = (ImageView) v;
                if (!(iv.getDrawable() instanceof AuthorIconDrawable)) {
                    iv.setImageDrawable(new AuthorIconDrawable(density));
                }
            } else if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    applyAuthorTextAndIcon(vg.getChildAt(i));
                }
            }
        }

        private void normalizeTabLabelSize(View v) {
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                CharSequence txt = tv.getText();
                if (txt != null && ("Tạo".equals(txt.toString()) || "Upload".equalsIgnoreCase(txt.toString()))) {
                    tv.setVisibility(View.GONE);
                    return;
                }
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
                tv.setSingleLine(true);
            } else if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    normalizeTabLabelSize(vg.getChildAt(i));
                }
            }
        }

        private void ensureMenuBounds(ViewGroup menu) {
            int barW = bar.getWidth();
            if (barW <= 0) return;

            if (menu.getPaddingLeft() != 0 || menu.getPaddingRight() != 0) {
                menu.setPadding(0, menu.getPaddingTop(), 0, menu.getPaddingBottom());
            }

            int leftMargin = dp(16f);
            int rightReserve = dp(12f + 56f + 10f + 4f); // 82dp
            int targetW = Math.max(dp(120f), barW - leftMargin - rightReserve);

            ViewGroup.LayoutParams rawLp = menu.getLayoutParams();
            if (rawLp instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rawLp;
                int desiredGravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                if (lp.width != targetW || lp.leftMargin != leftMargin || lp.rightMargin != 0 || lp.gravity != desiredGravity) {
                    lp.width = targetW;
                    lp.leftMargin = leftMargin;
                    lp.rightMargin = 0;
                    lp.gravity = desiredGravity;
                    menu.setLayoutParams(lp);
                }
            } else if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;
                if (lp.width != targetW || lp.leftMargin != leftMargin || lp.rightMargin != rightReserve) {
                    lp.width = targetW;
                    lp.leftMargin = leftMargin;
                    lp.rightMargin = rightReserve;
                    menu.setLayoutParams(lp);
                }
            }
        }

        private void syncTabState(boolean snap) {
            ViewGroup menu = getMenuView();
            if (menu == null) return;
            menu.setClipChildren(false);
            menu.setClipToPadding(false);
            if (menu.getBackground() != null) menu.setBackgroundColor(Color.TRANSPARENT);
            ensureMenuBounds(menu);

            int rawCount = menu.getChildCount();
            if (rawCount <= 0) return;

            if (authorTabView == null || authorTabView.getParent() != menu) {
                authorTabView = null;
                for (int i = 0; i < rawCount; i++) {
                    View c = menu.getChildAt(i);
                    if (isSearchTabView(c, i, rawCount)) {
                        authorTabView = c;
                        searchTabItemId = getTabItemId(c);
                        break;
                    }
                }
            }
            if (authorTabView != null) {
                styleAsAuthorTab(authorTabView);
                searchTabSelected = !authorPageVisible && isTabViewSelected(authorTabView);
            }

            visibleTabs.clear();
            tabRects.clear();
            authorTabVisibleIndex = -1;

            int selected = -1;
            float menuLeft = menu.getLeft();
            float menuTop = menu.getTop();

            for (int i = 0; i < rawCount; i++) {
                View child = menu.getChildAt(i);
                if (child.getBackground() != null) child.setBackgroundColor(Color.TRANSPARENT);
                normalizeTabLabelSize(child);
                if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0 && child.getHeight() > 0) {
                    int visIdx = visibleTabs.size();
                    visibleTabs.add(child);
                    tabRects.add(new RectF(
                            menuLeft + child.getLeft(),
                            menuTop + child.getTop(),
                            menuLeft + child.getRight(),
                            menuTop + child.getBottom()
                    ));
                    if (child == authorTabView) {
                        authorTabVisibleIndex = visIdx;
                        if (authorPageVisible) selected = visIdx;
                    } else if (!authorPageVisible && isTabViewSelected(child)) {
                        selected = visIdx;
                    }
                }
            }

            int count = visibleTabs.size();
            if (count <= 0) return;

            if (currentIndex >= count) currentIndex = count - 1;
            if (selected >= 0 && !trackingTouch && (selected != currentIndex || snap)) {
                currentIndex = Math.max(0, Math.min(count - 1, selected));
                springTarget = currentIndex;
                if (snap) {
                    springValue = springTarget;
                    springVel = 0f;
                } else {
                    triggerTapPulse();
                }
                scheduleFrame();
            }
        }

        private void triggerTapPulse() {
            pressTarget = 1f;
            scaleXTarget = 78f / 56f;
            scaleYTarget = 78f / 56f;
            bar.postDelayed(() -> {
                if (!trackingTouch) {
                    pressTarget = 0f;
                    scaleXTarget = 1f;
                    scaleYTarget = 1f;
                    scheduleFrame();
                }
            }, 140L);
        }

        private int indexAt(float x) {
            int count = tabRects.size();
            if (count <= 0) return 0;
            int bestIdx = 0;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                RectF r = tabRects.get(i);
                if (x >= r.left && x <= r.right) return i;
                float dist = Math.abs(x - r.centerX());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                }
            }
            return bestIdx;
        }

        private float avgTabWidth() {
            if (tabRects.isEmpty()) return Math.max(1f, outerRect.width() / 5f);
            float sum = 0f;
            for (RectF r : tabRects) sum += r.width();
            return Math.max(1f, sum / tabRects.size());
        }

        private boolean isInsideSearchCircle(float x, float y) {
            float pad = dpf(8f);
            return x >= searchRect.left - pad && x <= searchRect.right + pad
                    && y >= searchRect.top - pad && y <= searchRect.bottom + pad;
        }

        private void triggerSearchTab() {
            if (searchTabItemId != View.NO_ID && searchTabItemId != 0) {
                try {
                    Method setSelected = bar.getClass().getMethod("setSelectedItemId", int.class);
                    setSelected.invoke(bar, searchTabItemId);
                    searchTabSelected = true;
                    // Immediately transform the newly shown Search bar into Liquid Glass
                    bar.postDelayed(() -> {
                        View root = bar.getRootView();
                        View content = root != null ? root.findViewById(android.R.id.content) : null;
                    }, 120L);
                    return;
                } catch (Throwable ignored) {
                }
            }
            if (authorTabView != null) {
                try {
                    authorTabView.performClick();
                } catch (Throwable ignored) {
                }
            }
        }

        private void dispatchTabClick(int visibleIndex) {
            if (visibleIndex < 0 || visibleIndex >= visibleTabs.size()) return;
            View tabView = visibleTabs.get(visibleIndex);
            boolean beforeSelected = isTabViewSelected(tabView);
            try {
                tabView.performClick();
            } catch (Throwable ignored) {
            }
            if (!beforeSelected && !isTabViewSelected(tabView)) {
                int itemId = getTabItemId(tabView);
                if (itemId != View.NO_ID && itemId != 0) {
                    try {
                        Method setSelected = bar.getClass().getMethod("setSelectedItemId", int.class);
                        setSelected.invoke(bar, itemId);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void scheduleFrame() {
            if (!frameScheduled && attached) {
                frameScheduled = true;
                lastFrameNs = SystemClock.elapsedRealtimeNanos();
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        private void applyCollapseTransforms() {
            float p = Math.max(0f, Math.min(1f, collapseProgress));
            ViewGroup menu = getMenuView();
            boolean miniActive = isMiniPlayerCollapsedAndVisible();

            bar.setZ(dpf(28f));
            if (menu != null) {
                float shiftX = (searchRect.centerX() - outerRect.centerX()) * p;
                menu.setPivotX(menu.getWidth() * 0.5f);
                menu.setPivotY(menu.getHeight() * 0.5f);
                menu.setTranslationX(shiftX);
                menu.setTranslationY(0f);
                float ms = 1f - 0.65f * p;
                menu.setScaleX(ms);
                menu.setScaleY(ms);
                menu.setAlpha(Math.max(0f, 1f - p * 1.4f));
            }
            if (miniActive && miniPlayerSheet != null) {
                // Keep Mini-Player foreground content ABOVE bar's metaball glass (28dp) when expanded (p < 0.5)
                // so text, artwork, play button & progress bar are NEVER covered when fusing!
                miniPlayerSheet.setZ(p < 0.5f ? dpf(32f) : dpf(18f));
                float dropDist = outerRect.height() > 0f ? outerRect.height() + dpf(8f) : dpf(66f);
                float pullUpReaction = (searchOffsetY < 0f) ? (searchOffsetY * 0.22f) : 0f;
                miniPlayerSheet.setTranslationY(p * dropDist + pullUpReaction);
                float miniW = Math.max(dpf(200f), miniPlayerPill.getWidth());
                float targetScaleX = (miniW - dpf(66f) * p) / miniW;
                miniPlayerPill.setPivotX(0f);
                miniPlayerPill.setPivotY(miniPlayerPill.getHeight() * 0.5f);
                miniPlayerPill.setScaleX(targetScaleX);
                miniPlayerPill.setScaleY(1f);
            } else if (miniPlayerSheet != null) {
                miniPlayerSheet.setTranslationY(0f);
                if (miniPlayerPill != null) {
                    miniPlayerPill.setScaleX(1f);
                    miniPlayerPill.setScaleY(1f);
                }
            }
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            if (!attached) return;

            float dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
            lastFrameNs = frameTimeNanos;
            if (dt <= 0f || dt > 0.05f) dt = 0.016f;

            boolean moving = false;
            float[] s0 = stepSpring(springValue, springVel, springTarget, 650f, 1.0f, dt);
            springValue = s0[0];
            springVel = s0[1];
            if (Math.abs(springValue - springTarget) > 0.001f || Math.abs(springVel) > 0.005f) moving = true;

            float[] s1 = stepSpring(pressProgress, pressVel, pressTarget, 850f, 1.0f, dt);
            pressProgress = Math.max(0f, Math.min(1f, s1[0]));
            pressVel = s1[1];
            if (Math.abs(pressProgress - pressTarget) > 0.002f || Math.abs(pressVel) > 0.005f) moving = true;

            float[] s2 = stepSpring(scaleX, scaleXVel, scaleXTarget, 250f, 0.6f, dt);
            scaleX = s2[0];
            scaleXVel = s2[1];
            if (Math.abs(scaleX - scaleXTarget) > 0.002f || Math.abs(scaleXVel) > 0.005f) moving = true;

            float[] s3 = stepSpring(scaleY, scaleYVel, scaleYTarget, 250f, 0.7f, dt);
            scaleY = s3[0];
            scaleYVel = s3[1];
            if (Math.abs(scaleY - scaleYTarget) > 0.002f || Math.abs(scaleYVel) > 0.005f) moving = true;

            float[] s4 = stepSpring(collapseProgress, collapseVel, collapseTarget, 360f, 0.88f, dt);
            collapseProgress = Math.max(0f, Math.min(1f, s4[0]));
            collapseVel = s4[1];
            if (Math.abs(collapseProgress - collapseTarget) > 0.002f || Math.abs(collapseVel) > 0.005f) moving = true;

            // Firm, weighted spring for the metaball Search circle (higher stiffness = solid, crisp feel)
            float[] s5 = stepSpring(searchPress, searchPressVel, searchPressTarget, 520f, 0.78f, dt);
            searchPress = Math.max(0f, Math.min(1f, s5[0]));
            searchPressVel = s5[1];
            if (Math.abs(searchPress - searchPressTarget) > 0.002f || Math.abs(searchPressVel) > 0.005f) moving = true;

            float[] s6 = stepSpring(searchOffsetX, searchOffsetVelX, searchOffsetTargetX, 420f, 0.72f, dt);
            searchOffsetX = s6[0];
            searchOffsetVelX = s6[1];
            if (Math.abs(searchOffsetX - searchOffsetTargetX) > 0.1f || Math.abs(searchOffsetVelX) > 0.5f) moving = true;

            float[] s7 = stepSpring(searchOffsetY, searchOffsetVelY, searchOffsetTargetY, 420f, 0.72f, dt);
            searchOffsetY = s7[0];
            searchOffsetVelY = s7[1];
            if (Math.abs(searchOffsetY - searchOffsetTargetY) > 0.1f || Math.abs(searchOffsetVelY) > 0.5f) moving = true;
            float[] s8 = stepSpring(barMergeProgress, barMergeVel, barMergeTarget, 380f, 0.78f, dt);
            barMergeProgress = Math.max(0f, Math.min(1f, s8[0]));
            barMergeVel = s8[1];
            if (Math.abs(barMergeProgress - barMergeTarget) > 0.002f || Math.abs(barMergeVel) > 0.005f) moving = true;

            float[] s9 = stepSpring(miniMergeProgress, miniMergeVel, miniMergeTarget, 380f, 0.78f, dt);
            miniMergeProgress = Math.max(0f, Math.min(1f, s9[0]));
            miniMergeVel = s9[1];
            if (Math.abs(miniMergeProgress - miniMergeTarget) > 0.002f || Math.abs(miniMergeVel) > 0.005f) moving = true;

            if (searchState == SEARCH_RELEASING && searchPress < 0.01f
                    && barMergeProgress < 0.01f && miniMergeProgress < 0.01f
                    && Math.abs(searchOffsetX) < 0.3f && Math.abs(searchOffsetY) < 0.3f) {
                searchState = SEARCH_IDLE;
            }

            applyCollapseTransforms();
            bar.invalidate();
            if (miniPlayerPill != null) miniPlayerPill.invalidate();
            if (moving || trackingTouch || trackingSearchBtn) {
                scheduleFrame();
            }
        }

        private float[] stepSpring(float x, float v, float target, float stiffness, float dampingRatio, float dt) {
            float c = 2f * dampingRatio * (float) Math.sqrt(stiffness);
            float a = -stiffness * (x - target) - c * v;
            float nv = v + a * dt;
            float nx = x + nv * dt;
            return new float[]{nx, nv};
        }

        @Override
        public boolean onPreDraw() {
            if (capturing || !attached) return true;
            syncTabState(false);
            locateAndStyleMiniPlayer();

            // Always apply real AGSL Liquid Glass to Search bar; also apply to global buttons when toggled ON
            View root = bar.getRootView();
            View content = root != null ? root.findViewById(android.R.id.content) : null;

            if (Build.VERSION.SDK_INT < 29) return true;
            if (bar.getWidth() > 0 && bar.getHeight() > 0) {
                captureBackdropFor(bar, true);
            }
            if (isMiniPlayerCollapsedAndVisible()) {
                captureBackdropFor(miniPlayerPill, false);
            }
            return true;
        }

        private boolean isAncestorOf(View candidate, View target) {
            if (target == null) return false;
            ViewParent p = target.getParent();
            while (p instanceof View) {
                if (p == candidate) return true;
                p = p.getParent();
            }
            return false;
        }

        private void drawViewExcludingTargets(Canvas c, View view) {
            if (view == bar || view == miniPlayerSheet || view == miniPlayerPill
                    || view.getVisibility() != View.VISIBLE) return;
            if (!isAncestorOf(view, bar) && !isAncestorOf(view, miniPlayerSheet) && !isAncestorOf(view, miniPlayerPill)) {
                view.draw(c);
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                Drawable bg = vg.getBackground();
                if (bg != null) bg.draw(c);
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child == bar || child == miniPlayerSheet || child == miniPlayerPill
                            || child.getVisibility() != View.VISIBLE) continue;
                    int save = c.save();
                    c.translate(
                            child.getLeft() + child.getTranslationX() - vg.getScrollX(),
                            child.getTop() + child.getTranslationY() - vg.getScrollY()
                    );
                    drawViewExcludingTargets(c, child);
                    c.restoreToCount(save);
                }
            }
        }

        private void captureBackdropFor(View targetView, boolean isTabBar) {
            View root = targetView.getRootView();
            if (root == null) return;
            View content = root.findViewById(android.R.id.content);
            if (content == null) content = root;

            int w = targetView.getWidth();
            int h = targetView.getHeight();
            if (w <= 0 || h <= 0) return;

            RenderNode node;
            if (isTabBar) {
                if (backdropNode == null) backdropNode = new RenderNode("sc_liquid_backdrop");
                node = backdropNode;
            } else {
                if (miniBackdropNode == null) miniBackdropNode = new RenderNode("sc_mini_backdrop");
                node = miniBackdropNode;
            }
            int topPad = isTabBar ? Math.round(dpf(110f)) : 0;
            node.setPosition(0, -topPad, w, h);

            int[] targetLoc = new int[2];
            int[] contentLoc = new int[2];
            targetView.getLocationInWindow(targetLoc);
            content.getLocationInWindow(contentLoc);
            float dx = contentLoc[0] - targetLoc[0];
            float dy = contentLoc[1] - targetLoc[1];

            capturing = true;
            Canvas c = node.beginRecording(w, h + topPad);
            try {
                c.translate(dx, dy + topPad);
                drawViewExcludingTargets(c, content);
            } catch (Throwable ignored) {
            } finally {
                node.endRecording();
                capturing = false;
            }
        }

        private void updateGeometry() {
            float barW = bar.getWidth();
            float barH = bar.getHeight();
            if (barW <= 0f || barH <= 0f) return;

            float safeTop = dpf(2f);
            float safeBottom = barH - dpf(2f);

            float top;
            float bottom;
            if (!tabRects.isEmpty()) {
                float minTop = Float.MAX_VALUE;
                float maxBottom = -Float.MAX_VALUE;
                for (RectF r : tabRects) {
                    if (r.top < minTop) minTop = r.top;
                    if (r.bottom > maxBottom) maxBottom = r.bottom;
                }
                float padV = dpf(4f);
                top = Math.max(safeTop, minTop - padV);
                bottom = Math.min(safeBottom, maxBottom + padV);
                float targetH = Math.min(dpf(60f), safeBottom - safeTop);
                if (bottom - top < targetH) {
                    float cy = (minTop + maxBottom) * 0.5f;
                    top = cy - targetH * 0.5f;
                    bottom = cy + targetH * 0.5f;
                    if (top < safeTop) {
                        bottom = Math.min(safeBottom, bottom + (safeTop - top));
                        top = safeTop;
                    }
                    if (bottom > safeBottom) {
                        top = Math.max(safeTop, top - (bottom - safeBottom));
                        bottom = safeBottom;
                    }
                } else if (bottom - top > dpf(60f)) {
                    float cy = (top + bottom) * 0.5f;
                    top = Math.max(safeTop, cy - dpf(30f));
                    bottom = Math.min(safeBottom, top + dpf(60f));
                }
            } else {
                float pillH = Math.min(dpf(60f), Math.max(dpf(44f), safeBottom - safeTop));
                top = safeTop + ((safeBottom - safeTop) - pillH) * 0.5f;
                bottom = top + pillH;
            }

            float circleSize = Math.min(dpf(56f), bottom - top);
            float cy = (top + bottom) * 0.5f;
            float rightEdge = barW - dpf(12f);

            searchRect.set(
                    rightEdge - circleSize,
                    cy - circleSize * 0.5f,
                    rightEdge,
                    cy + circleSize * 0.5f
            );

            float left = dpf(12f) + panelOffsetPx;
            float right = searchRect.left - dpf(10f) + panelOffsetPx;
            outerRect.set(left, top, Math.max(left + dpf(80f), right), bottom);
        }

        private void computeInterpolatedPillBounds(float indexFloat, float[] outCenterX, float[] outCenterY, float[] outWidth) {
            int count = tabRects.size();
            if (count <= 0) {
                outCenterX[0] = outerRect.centerX();
                outCenterY[0] = outerRect.centerY();
                outWidth[0] = outerRect.width();
                return;
            }
            float clamped = Math.max(0f, Math.min(count - 1f, indexFloat));
            int i0 = (int) Math.floor(clamped);
            int i1 = Math.min(count - 1, i0 + 1);
            float t = clamped - i0;
            RectF r0 = tabRects.get(i0);
            RectF r1 = tabRects.get(i1);
            outCenterX[0] = r0.centerX() + (r1.centerX() - r0.centerX()) * t + panelOffsetPx;
            outCenterY[0] = outerRect.centerY();
            outWidth[0] = r0.width() + (r1.width() - r0.width()) * t;
        }

        private int computeSurfaceTintAlpha(boolean dark) {
            float op = Math.max(0f, Math.min(1f, 1f - transparencyPct / 100f));
            return Math.round(20f + op * (dark ? 185f : 195f));
        }

        private boolean isDarkTheme() {
            int mode = bar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        }

        private int dp(float v) {
            return Math.round(v * density);
        }

        private float dpf(float v) {
            return v * density;
        }

        private final class MiniPlayerGlassDrawable extends Drawable {
            private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                if (capturing || miniPlayerPill == null) return;
                int w = miniPlayerPill.getWidth();
                int h = miniPlayerPill.getHeight();
                if (w <= 0 || h <= 0) return;

                boolean dark = isDarkTheme();
                miniRect.set(dpf(2f), dpf(2f), w - dpf(2f), h - dpf(2f));
                float radius = miniRect.height() * 0.5f;

                // While fused with Search button, bar's unified metaballGlassNode (Z=28dp) already renders
                // the seamless liquid glass material BEHIND this Mini-Player (Z=32dp); skip opaque bg here
                // so native text, artwork, play button & progress bar render 100% intact on top!
                if (miniMergeProgress > 0.04f && collapseProgress < 0.35f) {
                    if (Build.VERSION.SDK_INT >= 33 && miniMergeGlow > 0.01f && highlightShader != null) {
                        canvas.save();
                        miniClipPath.reset();
                        miniClipPath.addRoundRect(miniRect, radius, radius, Path.Direction.CW);
                        canvas.clipPath(miniClipPath);
                        float clampedGlow = Math.max(0f, Math.min(0.32f, miniMergeGlow * 0.32f));
                        Paint mpGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
                        float glowX = Math.max(miniRect.left, Math.min(miniRect.right,
                                miniRect.right - dpf(28f) + searchOffsetX));
                        float glowY = miniRect.bottom;
                        int glowColor = dark
                                ? Color.argb(Math.round(255f * clampedGlow), 195, 208, 232)
                                : Color.argb(Math.round(255f * clampedGlow * 0.7f), 255, 255, 255);
                        highlightShader.setFloatUniform("size", miniRect.width(), miniRect.height());
                        highlightShader.setColorUniform("color", glowColor);
                        highlightShader.setFloatUniform("radius", miniRect.height() * 1.35f);
                        highlightShader.setFloatUniform("position", glowX, glowY);
                        mpGlow.setShader(highlightShader);
                        canvas.drawRect(miniRect, mpGlow);
                        canvas.restore();
                    }
                    return;
                }

                shadowPaint.setColor(Color.TRANSPARENT);
                shadowPaint.setShadowLayer(dpf(10f), 0f, dpf(3f),
                        Color.argb(dark ? 56 : 28, 0, 0, 0));
                canvas.drawRoundRect(miniRect, radius, radius, shadowPaint);

                canvas.save();
                miniClipPath.reset();
                miniClipPath.addRoundRect(miniRect, radius, radius, Path.Direction.CW);
                canvas.clipPath(miniClipPath);

                if (Build.VERSION.SDK_INT >= 33 && miniBackdropNode != null
                        && miniBackdropNode.hasDisplayList() && miniLensShader != null
                        && canvas.isHardwareAccelerated()) {
                    if (miniGlassNode == null) miniGlassNode = new RenderNode("sc_mini_glass");
                    miniGlassNode.setPosition(0, 0, w, h);

                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(1.5f);
                    RenderEffect chain = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                    if (blurDp > 0.2f) {
                        float blurPx = dpf(blurDp);
                        chain = RenderEffect.createChainEffect(
                                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP), chain);
                    }
                    if (refractionDp > 0.5f) {
                        float refPx = dpf(refractionDp * 0.75f);
                        miniLensShader.setFloatUniform("size", miniRect.width(), miniRect.height());
                        miniLensShader.setFloatUniform("offset", -miniRect.left, -miniRect.top);
                        miniLensShader.setFloatUniform("cornerRadii", new float[]{radius, radius, radius, radius});
                        miniLensShader.setFloatUniform("refractionHeight", refPx);
                        miniLensShader.setFloatUniform("refractionAmount", -refPx);
                        miniLensShader.setFloatUniform("depthEffect", 0f);
                        miniLensShader.setFloatUniform("chromaticAberration", dispersionAmount * 0.45f);
                        chain = RenderEffect.createChainEffect(
                                RenderEffect.createRuntimeShaderEffect(miniLensShader, "content"), chain);
                    }
                    miniGlassNode.setRenderEffect(chain);

                    Canvas gc = miniGlassNode.beginRecording(w, h);
                    try {
                        gc.drawRenderNode(miniBackdropNode);
                    } finally {
                        miniGlassNode.endRecording();
                    }
                    canvas.drawRenderNode(miniGlassNode);
                }

                int surfAlpha = computeSurfaceTintAlpha(dark);
                surfacePaint.setColor(dark ? Color.argb(surfAlpha, 24, 24, 28) : Color.argb(surfAlpha, 245, 247, 252));
                canvas.drawRoundRect(miniRect, radius, radius, surfacePaint);

                // Soft, clamped (0..0.32) background-tinted glow ONLY when Search actually approaches/merges Mini-Player
                // Uses SRC_OVER (NEVER BlendMode.PLUS white wash!) so Mini-Player never blows out to solid white!
                if (Build.VERSION.SDK_INT >= 33 && miniMergeGlow > 0.01f && highlightShader != null) {
                    float clampedGlow = Math.max(0f, Math.min(0.32f, miniMergeGlow * 0.32f));
                    Paint mpGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
                    float glowX = Math.max(miniRect.left, Math.min(miniRect.right,
                            miniRect.right - dpf(28f) + searchOffsetX));
                    float glowY = miniRect.bottom;
                    // Subtle glass-tinted color matching theme (not pure harsh white)
                    int glowColor = dark
                            ? Color.argb(Math.round(255f * clampedGlow), 195, 208, 232)
                            : Color.argb(Math.round(255f * clampedGlow * 0.7f), 255, 255, 255);
                    highlightShader.setFloatUniform("size", miniRect.width(), miniRect.height());
                    highlightShader.setColorUniform("color", glowColor);
                    highlightShader.setFloatUniform("radius", miniRect.height() * 1.35f);
                    highlightShader.setFloatUniform("position", glowX, glowY);
                    mpGlow.setShader(highlightShader);
                    canvas.drawRect(miniRect, mpGlow);
                }
                canvas.restore();

                drawBloomRim(canvas, miniRect, radius, 0.75f, -45f);
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        }

        /**
         * Renders BOTH the TabBar pill AND the Search circle in a SINGLE unified AGSL Smooth-Min
         * Metaball Shader so dragging the Search circle into the TabBar melts them together with
         * rainbow chromatic dispersion along the fused liquid bridge (152000.mp4)!
         */
        private final class GlassBackgroundDrawable extends Drawable {
            private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                if (capturing) return;
                updateGeometry();
                if (outerRect.width() <= 0f || outerRect.height() <= 0f) return;

                boolean dark = isDarkTheme();
                int surfAlpha = computeSurfaceTintAlpha(dark);
                float p = Math.max(0f, Math.min(1f, collapseProgress));

                // Compute animated outer bar bounds (shrinks subtly on hold, collapses into searchRect on scroll)
                float left = outerRect.left + (searchRect.left - outerRect.left) * p;
                float right = outerRect.right + (searchRect.right - outerRect.right) * p;
                float top = outerRect.top + (searchRect.top - outerRect.top) * p;
                float bottom = outerRect.bottom + (searchRect.bottom - outerRect.bottom) * p;
                float barCx = (left + right) * 0.5f;
                float barCy = (top + bottom) * 0.5f;
                float barHalfW = Math.max(1f, (right - left) * 0.5f * (1f - 0.015f * pressProgress));
                float barHalfH = Math.max(1f, (bottom - top) * 0.5f * (1f - 0.10f * pressProgress));
                float barRad = Math.min(barHalfW, barHalfH);
                float barAlpha = Math.max(0f, 1f - p * 1.15f);

                int w = bar.getWidth();
                int h = bar.getHeight();

                int topPad = Math.round(dpf(110f));

                // Compute animated Search circle center & radius:
                // Allow pulling freely UP past y = 0 toward Mini-Player while keeping safe right/bottom margins!
                float sRad = (searchRect.height() * 0.5f) * (1f + 0.12f * searchPress);
                float safePadX = dpf(3f);
                float safePadY = dpf(2f);
                float sCx = Math.max(sRad + safePadX, Math.min(w - sRad - safePadX, searchRect.centerX() + searchOffsetX));
                float sCy = Math.max(-topPad + sRad + safePadY, Math.min(h - sRad - safePadY, searchRect.centerY() + searchOffsetY));

                // Simultaneous multi-target SDF evaluation (LỖI 4 FIX):
                // TabBar AND Mini-Player each have independent spring-animated mergeProgress (0..1).
                // Touching both at once fuses ALL THREE into one seamless liquid body!
                boolean allowMerge = (searchState == SEARCH_DRAGGING || searchState == SEARCH_MERGED);
                float dMaxMerge = dpf(MERGE_THRESHOLD_DP);

                // Target 1: TabBar rounded-rect SDF distance from Search circle edge
                float qBarX = Math.max(0f, Math.abs(sCx - barCx) - (barHalfW - barRad));
                float qBarY = Math.max(0f, Math.abs(sCy - barCy) - (barHalfH - barRad));
                float distToBar = ((float) Math.hypot(qBarX, qBarY) - barRad) - sRad;

                // Target 2: Mini-Player rounded-rect SDF distance from Search circle edge
                float miniCx = 0f, miniCy = -9999f, miniHalfW = 1f, miniHalfH = 1f, miniRad = 1f;
                float distToMini = 9999f;
                boolean miniVis = isMiniPlayerCollapsedAndVisible();
                if (miniVis && miniPlayerPill != null && p < 0.35f) {
                    int[] bLoc = new int[2], mLoc = new int[2];
                    bar.getLocationOnScreen(bLoc);
                    miniPlayerPill.getLocationOnScreen(mLoc);
                    float mLeft = (mLoc[0] - bLoc[0]) + dpf(2f);
                    float mTop = (mLoc[1] - bLoc[1]) + dpf(2f);
                    float mW = Math.max(1f, miniPlayerPill.getWidth() - dpf(4f));
                    float mH = Math.max(1f, miniPlayerPill.getHeight() - dpf(4f));
                    miniCx = mLeft + mW * 0.5f;
                    miniCy = mTop + mH * 0.5f;
                    miniHalfW = mW * 0.5f;
                    miniHalfH = mH * 0.5f;
                    miniRad = miniHalfH;
                    float qMiniX = Math.max(0f, Math.abs(sCx - miniCx) - (miniHalfW - miniRad));
                    float qMiniY = Math.max(0f, Math.abs(sCy - miniCy) - (miniHalfH - miniRad));
                    distToMini = ((float) Math.hypot(qMiniX, qMiniY) - miniRad) - sRad;
                }

                // Evaluate BOTH targets independently (no argmin / else-if!)
                if (allowMerge && distToBar <= dMaxMerge) {
                    float tB = Math.max(0f, Math.min(1f, distToBar / dMaxMerge));
                    barMergeTarget = 1f - (tB * tB * (3f - 2f * tB));
                } else {
                    barMergeTarget = 0f;
                }
                if (allowMerge && distToMini <= dMaxMerge) {
                    float tM = Math.max(0f, Math.min(1f, distToMini / dMaxMerge));
                    miniMergeTarget = 1f - (tM * tM * (3f - 2f * tM));
                } else {
                    miniMergeTarget = 0f;
                }

                float smoothK = dpf(38f) * barMergeProgress + dpf(18f) * p;
                float miniSmoothK = dpf(38f) * miniMergeProgress;
                float miniAlphaVal = (miniMergeProgress > 0.04f) ? 1f : 0f;
                miniMergeGlow = miniMergeProgress * searchPress;

                if (searchState == SEARCH_DRAGGING && (barMergeTarget > 0.05f || miniMergeTarget > 0.05f)) {
                    searchState = SEARCH_MERGED;
                }
                float proxSmooth = Math.max(barMergeProgress, miniMergeProgress);

                // Active touchPoint & touchGlow for unified inner glow + specular bloom on BOTH TabBar & Search!
                float activeGlow = Math.max(searchPress, pressProgress);
                float glowPtX = (searchPress >= pressProgress) ? searchTouchX : (outerRect.left + outerRect.width() * 0.5f);
                float glowPtY = (searchPress >= pressProgress) ? searchTouchY : outerRect.centerY();
                if (pressProgress > searchPress && !tabRects.isEmpty()) {
                    float[] tmpX = new float[1], tmpY = new float[1], tmpW = new float[1];
                    computeInterpolatedPillBounds(springValue, tmpX, tmpY, tmpW);
                    glowPtX = tmpX[0];
                    glowPtY = tmpY[0];
                }
                float glowRadPx = dpf(72f) + dpf(36f) * proxSmooth;

                if (Build.VERSION.SDK_INT >= 33 && backdropNode != null
                        && backdropNode.hasDisplayList() && metaballShader != null
                        && canvas.isHardwareAccelerated()) {
                    if (metaballGlassNode == null) metaballGlassNode = new RenderNode("sc_metaball_glass");
                    metaballGlassNode.setPosition(0, -topPad, w, h);

                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(1.5f);
                    RenderEffect chain = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                    if (blurDp > 0.2f) {
                        float blurPx = dpf(blurDp);
                        chain = RenderEffect.createChainEffect(
                                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP), chain);
                    }

                    // Gravity-rotated specular light direction
                    float magSq = gravityX * gravityX + gravityY * gravityY;
                    float lx0 = 0f, ly0 = -1f;
                    if (magSq > 0.01f) {
                        float inv = 1f / (float) Math.sqrt(magSq);
                        lx0 = gravityX * inv;
                        ly0 = gravityY * inv;
                    }
                    double rad = -45.0 * Math.PI / 180.0;
                    float lx = (float) (Math.cos(rad) * lx0 - Math.sin(rad) * ly0);
                    float ly = (float) (Math.sin(rad) * lx0 + Math.cos(rad) * ly0);

                    float refPx = dpf(Math.max(4f, refractionDp));
                    float chroma = dispersionAmount * (0.45f + 0.75f * Math.max(activeGlow, proxSmooth));

                    metaballShader.setFloatUniform("barCenter", barCx, barCy + topPad);
                    metaballShader.setFloatUniform("barHalfSize", barHalfW, barHalfH);
                    metaballShader.setFloatUniform("barRadius", barRad);
                    metaballShader.setFloatUniform("searchCenter", sCx, sCy + topPad);
                    metaballShader.setFloatUniform("searchRadius", sRad);
                    metaballShader.setFloatUniform("smoothK", smoothK);
                    metaballShader.setFloatUniform("miniCenter", miniCx, miniCy + topPad);
                    metaballShader.setFloatUniform("miniHalfSize", miniHalfW, miniHalfH);
                    metaballShader.setFloatUniform("miniRadius", miniRad);
                    metaballShader.setFloatUniform("miniSmoothK", miniSmoothK);
                    metaballShader.setFloatUniform("miniAlpha", miniAlphaVal);
                    metaballShader.setFloatUniform("refractionHeight", refPx);
                    metaballShader.setFloatUniform("refractionAmount", -refPx);
                    metaballShader.setFloatUniform("chromaticAberration", chroma);
                    metaballShader.setFloatUniform("barAlpha", barAlpha);
                    metaballShader.setColorUniform("tintColor", dark
                            ? Color.argb(surfAlpha, 24, 24, 28)
                            : Color.argb(surfAlpha, 245, 247, 252));
                    metaballShader.setFloatUniform("lightDir", lx, ly);
                    metaballShader.setFloatUniform("touchPoint", glowPtX, glowPtY + topPad);
                    metaballShader.setFloatUniform("touchGlow", activeGlow);
                    metaballShader.setFloatUniform("glowRadius", glowRadPx);

                    chain = RenderEffect.createChainEffect(
                            RenderEffect.createRuntimeShaderEffect(metaballShader, "content"), chain);
                    metaballGlassNode.setRenderEffect(chain);

                    Canvas gc = metaballGlassNode.beginRecording(w, h + topPad);
                    try {
                        gc.translate(0f, topPad);
                        gc.drawRenderNode(backdropNode);
                    } finally {
                        metaballGlassNode.endRecording();
                    }
                    canvas.drawRenderNode(metaballGlassNode);
                } else {
                    fallbackPaint.setColor(dark ? Color.argb(surfAlpha, 24, 24, 28) : Color.argb(surfAlpha, 245, 247, 252));
                    if (barAlpha > 0.02f) {
                        canvas.drawRoundRect(new RectF(barCx - barHalfW, barCy - barHalfH, barCx + barHalfW, barCy + barHalfH),
                                barRad, barRad, fallbackPaint);
                    }
                    canvas.drawCircle(sCx, sCy, sRad, fallbackPaint);
                }
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        }

        private final class GlassOverlayDrawable extends Drawable {
            private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint pillSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint surfaceTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint accentMatrixPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF animSearchRect = new RectF();
            private final float[] tmpCX = new float[1];
            private final float[] tmpCY = new float[1];
            private final float[] tmpW = new float[1];

            GlassOverlayDrawable() {
                ColorMatrix cm = new ColorMatrix(new float[]{
                        1.05f, 0f,    0f,    0f, 12f,
                        0f,    0.55f, 0f,    0f, 0f,
                        0f,    0f,    0.26f, 0f, 0f,
                        0f,    0f,    0f,    1f, 0f
                });
                accentMatrixPaint.setColorFilter(new ColorMatrixColorFilter(cm));
            }

            @Override
            public void draw(Canvas canvas) {
                if (capturing) return;
                updateGeometry();
                boolean dark = isDarkTheme();
                int surfAlpha = computeSurfaceTintAlpha(dark);
                float p = Math.max(0f, Math.min(1f, collapseProgress));

                // 1. Sliding Liquid Glass Pill on the left TabBar
                if (p < 0.85f && !searchTabSelected && !tabRects.isEmpty() && outerRect.width() > 0f) {
                    float pillAlphaMult = Math.max(0f, 1f - p * 1.4f);
                    float outerRadius = outerRect.height() * 0.5f;
                    computeInterpolatedPillBounds(springValue, tmpCX, tmpCY, tmpW);
                    float centerX = tmpCX[0];
                    float centerY = tmpCY[0];
                    float tabW = tmpW[0];

                    if (Build.VERSION.SDK_INT >= 33 && pressProgress > 0.005f && highlightShader != null) {
                        canvas.save();
                        clipPath.reset();
                        clipPath.addRoundRect(outerRect, outerRadius, outerRadius, Path.Direction.CW);
                        canvas.clipPath(clipPath);

                        highlightPaint.setBlendMode(BlendMode.PLUS);
                        highlightPaint.setShader(null);
                        highlightPaint.setColor(Color.argb((int) (255 * 0.06f * pressProgress * pillAlphaMult), 255, 255, 255));
                        canvas.drawRect(outerRect, highlightPaint);

                        highlightShader.setFloatUniform("size", outerRect.width(), outerRect.height());
                        highlightShader.setColorUniform("color", Color.argb((int) (255 * 0.14f * pressProgress * pillAlphaMult), 255, 255, 255));
                        highlightShader.setFloatUniform("radius", outerRect.height() * 1.2f);
                        highlightShader.setFloatUniform("position", centerX, centerY);
                        highlightPaint.setShader(highlightShader);
                        canvas.drawRect(outerRect, highlightPaint);
                        highlightPaint.setShader(null);
                        highlightPaint.setBlendMode(null);
                        canvas.restore();
                    }

                    float innerLeft = outerRect.left + dpf(4f);
                    float innerRight = outerRect.right - dpf(4f);
                    float maxBaseW = (innerRight - innerLeft) / Math.max(1, tabRects.size());
                    float baseW = Math.min(tabW, maxBaseW);
                    float pillH = Math.min(dpf(52f), Math.max(dpf(36f), outerRect.height() - dpf(8f)));

                    float velNorm = dragVelocity / 10f;
                    float dynScaleX = scaleX / (1f - Math.max(-0.2f, Math.min(0.2f, velNorm * 0.75f)));
                    float dynScaleY = scaleY * (1f - Math.max(-0.2f, Math.min(0.2f, velNorm * 0.25f)));

                    float pw = baseW * dynScaleX;
                    float ph = pillH * dynScaleY;

                    if (pressProgress <= 0.01f) {
                        float halfPw = pw * 0.5f;
                        centerX = Math.max(innerLeft + halfPw, Math.min(innerRight - halfPw, centerX));
                    }

                    pillRect.set(centerX - pw * 0.5f, centerY - ph * 0.5f, centerX + pw * 0.5f, centerY + ph * 0.5f);
                    float pillRadius = ph * 0.5f;

                    canvas.save();
                    clipPath.reset();
                    clipPath.addRoundRect(pillRect, pillRadius, pillRadius, Path.Direction.CW);
                    canvas.clipPath(clipPath);

                    ViewGroup menu = getMenuView();
                    if (Build.VERSION.SDK_INT >= 33 && metaballGlassNode != null && metaballGlassNode.hasDisplayList()
                            && canvas.isHardwareAccelerated()) {
                        if (pillCombinedNode == null) pillCombinedNode = new RenderNode("sc_pill_combined");
                        if (pillGlassNode == null) pillGlassNode = new RenderNode("sc_pill_glass");

                        int w = bar.getWidth();
                        int h = bar.getHeight();
                        pillCombinedNode.setPosition(0, 0, w, h);
                        pillGlassNode.setPosition(0, 0, w, h);

                        Canvas cc = pillCombinedNode.beginRecording(w, h);
                        try {
                            cc.drawRenderNode(metaballGlassNode);
                            if (menu != null) {
                                float tabScale = 1f + 0.16f * pressProgress;
                                cc.save();
                                cc.scale(tabScale, tabScale, centerX, centerY);
                                cc.translate(menu.getLeft() + menu.getTranslationX(), menu.getTop() + menu.getTranslationY());
                                int layer = cc.saveLayer(0f, 0f, menu.getWidth(), menu.getHeight(), accentMatrixPaint);
                                menu.draw(cc);
                                cc.restoreToCount(layer);
                                cc.restore();
                            }
                        } finally {
                            pillCombinedNode.endRecording();
                        }

                        if (pressProgress > 0.01f && pillDispersionShader != null && refractionDp > 0.5f) {
                            float pRefH = dpf(refractionDp * 0.45f) * pressProgress;
                            float pRefA = dpf(refractionDp * 0.62f) * pressProgress;
                            pillDispersionShader.setFloatUniform("size", pillRect.width(), pillRect.height());
                            pillDispersionShader.setFloatUniform("offset", -pillRect.left, -pillRect.top);
                            pillDispersionShader.setFloatUniform("cornerRadii", new float[]{pillRadius, pillRadius, pillRadius, pillRadius});
                            pillDispersionShader.setFloatUniform("refractionHeight", Math.max(1f, pRefH));
                            pillDispersionShader.setFloatUniform("refractionAmount", -Math.max(1f, pRefA));
                            pillDispersionShader.setFloatUniform("depthEffect", 1f);
                            pillDispersionShader.setFloatUniform("chromaticAberration", dispersionAmount);
                            pillGlassNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(pillDispersionShader, "content"));
                        } else {
                            pillGlassNode.setRenderEffect(null);
                        }

                        Canvas pg = pillGlassNode.beginRecording(w, h);
                        try {
                            pg.drawRenderNode(pillCombinedNode);
                        } finally {
                            pillGlassNode.endRecording();
                        }
                        canvas.drawRenderNode(pillGlassNode);
                    }

                    int restAlpha = Math.round(28f * (1f - pressProgress) * pillAlphaMult);
                    pillSurfacePaint.setColor(dark
                            ? Color.argb(restAlpha, 255, 255, 255)
                            : Color.argb(restAlpha, 0, 0, 0));
                    canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillSurfacePaint);

                    if (pressProgress > 0.01f) {
                        pillSurfacePaint.setColor(Color.argb(Math.round(8f * pressProgress * pillAlphaMult), 0, 0, 0));
                        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillSurfacePaint);

                        innerShadowPaint.setStyle(Paint.Style.STROKE);
                        innerShadowPaint.setStrokeWidth(dpf(8f) * pressProgress);
                        innerShadowPaint.setColor(Color.argb(Math.round(42f * pressProgress * pillAlphaMult), 0, 0, 0));
                        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, innerShadowPaint);
                    }
                    canvas.restore();

                    if (pressProgress > 0.01f) {
                        drawBloomRim(canvas, pillRect, pillRadius, pressProgress * pillAlphaMult, 90f);
                    }
                }

                // 2. Right Separated Search <-> Menu Circle Icon (strictly synced with clamped metaball center)
                if (searchRect.width() > 0f) {
                    int bw = bar.getWidth();
                    int bh = bar.getHeight();
                    float cr = (searchRect.height() * 0.5f) * (1f + 0.12f * searchPress);
                    float cx = Math.max(cr + dpf(3f), Math.min(bw - cr - dpf(3f), searchRect.centerX() + searchOffsetX));
                    float cy = Math.max(-dpf(110f) + cr + dpf(2f), Math.min(bh - cr - dpf(2f), searchRect.centerY() + searchOffsetY));

                    int activeOrange = Color.rgb(255, 122, 56);
                    int baseColor = (searchTabSelected || searchPress > 0.35f) && p < 0.5f
                            ? activeOrange
                            : (dark ? Color.WHITE : Color.rgb(28, 28, 30));

                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeCap(Paint.Cap.ROUND);
                    iconPaint.setStrokeWidth(dpf(2.1f));

                    float searchAlpha = Math.max(0f, 1f - p * 1.6f);
                    if (searchAlpha > 0.02f) {
                        canvas.save();
                        float s = (1f - 0.25f * p) * (1f + 0.14f * searchPress);
                        canvas.scale(s, s, cx, cy);
                        canvas.rotate(45f * p, cx, cy);
                        iconPaint.setColor(baseColor);
                        iconPaint.setAlpha(Math.round(255 * searchAlpha));
                        float r = dpf(7.5f);
                        float lensCx = cx - dpf(1.8f);
                        float lensCy = cy - dpf(1.8f);
                        canvas.drawCircle(lensCx, lensCy, r, iconPaint);
                        float hStart = r * 0.707f;
                        canvas.drawLine(
                                lensCx + hStart, lensCy + hStart,
                                lensCx + hStart + dpf(5.2f), lensCy + hStart + dpf(5.2f),
                                iconPaint
                        );
                        canvas.restore();
                    }

                    float menuAlpha = Math.max(0f, (p - 0.35f) / 0.65f);
                    if (menuAlpha > 0.02f) {
                        canvas.save();
                        float s = (0.75f + 0.25f * menuAlpha) * (1f + 0.14f * searchPress);
                        canvas.scale(s, s, cx, cy);
                        iconPaint.setColor(searchPress > 0.35f ? activeOrange : (dark ? Color.WHITE : Color.rgb(28, 28, 30)));
                        iconPaint.setAlpha(Math.round(255 * menuAlpha));
                        float halfLine = dpf(8.5f);
                        float gap = dpf(5.5f);
                        canvas.drawLine(cx - halfLine, cy - gap, cx + halfLine, cy - gap, iconPaint);
                        canvas.drawLine(cx - halfLine, cy, cx + halfLine, cy, iconPaint);
                        canvas.drawLine(cx - halfLine, cy + gap, cx + halfLine, cy + gap, iconPaint);
                        canvas.restore();
                    }
                }
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        }

        private void drawBloomRim(Canvas canvas, RectF rect, float radius, float alphaScale, float extraDegrees) {
            if (alphaScale <= 0.01f) return;
            float gx = gravityX;
            float gy = gravityY;
            float magSq = gx * gx + gy * gy;
            float lx0 = 0f;
            float ly0 = -1f;
            if (magSq > 0.01f) {
                float inv = 1f / (float) Math.sqrt(magSq);
                lx0 = gx * inv;
                ly0 = gy * inv;
            }
            double rad = extraDegrees * Math.PI / 180.0;
            float c = (float) Math.cos(rad);
            float s = (float) Math.sin(rad);
            float lx = c * lx0 - s * ly0;
            float ly = s * lx0 + c * ly0;

            float cx = rect.centerX();
            float cy = rect.centerY();
            float halfW = rect.width() * 0.5f;
            float halfH = rect.height() * 0.5f;

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dpf(1f));
            int aHigh = Math.min(255, Math.round(210 * alphaScale));
            int aLow = Math.min(255, Math.round(30 * alphaScale));
            int aMid = Math.min(255, Math.round(90 * alphaScale));
            p.setShader(new LinearGradient(
                    cx + lx * halfW, cy + ly * halfH,
                    cx - lx * halfW, cy - ly * halfH,
                    new int[]{
                            Color.argb(aHigh, 255, 255, 255),
                            Color.argb(aLow, 255, 255, 255),
                            Color.argb(aMid, 255, 255, 255)
                    },
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRoundRect(rect, radius, radius, p);
        }
    }
}
