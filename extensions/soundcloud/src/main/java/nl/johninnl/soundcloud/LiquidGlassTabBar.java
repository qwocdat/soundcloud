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
import android.graphics.Insets;
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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 1:1 port of NPatch FloatingGlassBottomBar (Compose/AGSL) to Android View + RenderNode + AGSL
 * for SoundCloud's BottomTabBarRestyle / BottomTabBar + Floating Liquid Glass Mini-Player
 * with scroll-down collapse and scroll-up expand choreography.
 */
@SuppressLint("NewApi")
public final class LiquidGlassTabBar {

    private static final WeakHashMap<View, Controller> ATTACHED = new WeakHashMap<>();

    // Exact AGSL SDF + Refraction shaders ported from NPatch Lens.kt
    private static final String ROUNDED_RECT_SDF =
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "    if (coord.x >= 0.0) {\n" +
            "        if (coord.y <= 0.0) return radii.y;\n" +
            "        else return radii.z;\n" +
            "    } else {\n" +
            "        if (coord.y <= 0.0) return radii.x;\n" +
            "        else return radii.w;\n" +
            "    }\n" +
            "}\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "    return outside + inside;\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
            "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
            "    } else {\n" +
            "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "    }\n" +
            "}\n";

    private static final String ROUNDED_RECT_REFRACTION_SHADER =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            ROUNDED_RECT_SDF +
            "float circleMap(float x) {\n" +
            "    return 1.0 - sqrt(1.0 - x * x);\n" +
            "}\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord + float2(0.0001)));\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    return content.eval(refractedCoord);\n" +
            "}\n";

    private static final String ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            ROUNDED_RECT_SDF +
            "float circleMap(float x) {\n" +
            "    return 1.0 - sqrt(1.0 - x * x);\n" +
            "}\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 halfSize = size * 0.5;\n" +
            "    float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "    float radius = radiusAt(centeredCoord, cornerRadii);\n" +
            "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "    if (-sd >= refractionHeight) {\n" +
            "        return content.eval(coord);\n" +
            "    }\n" +
            "    sd = min(sd, 0.0);\n" +
            "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord + float2(0.0001)));\n" +
            "    float2 refractedCoord = coord + d * grad;\n" +
            "    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / max(halfSize.x * halfSize.y, 1.0));\n" +
            "    float2 dispersedCoord = d * grad * dispersionIntensity;\n" +
            "    half4 color = half4(0.0);\n" +
            "    half4 red = content.eval(refractedCoord + dispersedCoord);\n" +
            "    color.r += red.r / 3.5; color.a += red.a / 7.0;\n" +
            "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n" +
            "    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;\n" +
            "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n" +
            "    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;\n" +
            "    half4 green = content.eval(refractedCoord);\n" +
            "    color.g += green.g / 3.5; color.a += green.a / 7.0;\n" +
            "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n" +
            "    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;\n" +
            "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n" +
            "    color.b += blue.b / 3.0; color.a += blue.a / 7.0;\n" +
            "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n" +
            "    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;\n" +
            "    return color;\n" +
            "}\n";

    // Exact AGSL radial highlight ported from NPatch InteractiveHighlight.kt
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
            controller.install();
        }
    }

    private LiquidGlassTabBar() {}

    private static final class Controller implements
            ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener,
            View.OnLayoutChangeListener,
            View.OnTouchListener,
            Choreographer.FrameCallback,
            SensorEventListener {

        private final ViewGroup bar;
        private final float density;
        private final float touchSlopSq;

        private final GlassBackgroundDrawable bgDrawable;
        private final GlassOverlayDrawable fgDrawable;
        private final MiniPlayerGlassDrawable miniBgDrawable;

        private RenderNode backdropNode;
        private RenderNode outerGlassNode;
        private RenderNode pillCombinedNode;
        private RenderNode pillGlassNode;

        // Mini-player Liquid Glass nodes
        private View miniPlayerView;
        private RenderNode miniBackdropNode;
        private RenderNode miniGlassNode;
        private RuntimeShader miniLensShader;
        private final RectF miniRect = new RectF();
        private final Path miniClipPath = new Path();
        private long lastMiniSearchMs = 0L;

        private RuntimeShader outerLensShader;
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

        // Visible tab views & measured rects in bar coordinates
        private final List<View> visibleTabs = new ArrayList<>();
        private final List<RectF> tabRects = new ArrayList<>();

        // Damped spring state (ported 1:1 from NPatch DampedDragAnimation.kt)
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

        // Scroll-collapse spring state (0f = expanded like video 18s top, 1f = collapsed like video 18s scroll down)
        private float collapseProgress = 0f;
        private float collapseTarget = 0f;
        private float collapseVel = 0f;
        private boolean scrollTracking = false;
        private float scrollLastRawY = 0f;
        private float scrollAccumY = 0f;

        // Touch tracking on TabBar
        private boolean trackingTouch = false;
        private boolean isDragging = false;
        private float downX = 0f;
        private float downY = 0f;
        private float lastTouchX = 0f;
        private int touchStartIndex = 0;
        private int touchDownTargetIndex = 0;

        // Pill geometry
        private final RectF outerRect = new RectF();
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

            if (Build.VERSION.SDK_INT >= 33) {
                outerLensShader = new RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER);
                pillDispersionShader = new RuntimeShader(ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER);
                highlightShader = new RuntimeShader(INTERACTIVE_HIGHLIGHT_SHADER);
                miniLensShader = new RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER);
            }
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

            // Lift bar cleanly above the system gesture bar so bottom never clips or overlaps gesture pill
            bar.setPadding(0, dp(6f), 0, dp(6f));
            bar.setBackground(bgDrawable);
            bar.getOverlay().add(fgDrawable);
            bar.addOnAttachStateChangeListener(this);
            bar.addOnLayoutChangeListener(this);
            bar.setOnTouchListener(this);

            if (bar.isAttachedToWindow()) {
                onViewAttachedToWindow(bar);
            }
        }

        private int getNavBarInsetBottom() {
            try {
                WindowInsets insets = bar.getRootWindowInsets();
                if (insets != null) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                        return Math.max(0, nav.bottom);
                    } else {
                        return Math.max(0, insets.getSystemWindowInsetBottom());
                    }
                }
            } catch (Throwable ignored) {
            }
            return dp(12f);
        }

        private void ensureFloatingBottomLift() {
            int desiredLift = Math.max(dp(12f), getNavBarInsetBottom() + dp(4f));
            if (bar.getPaddingBottom() < desiredLift) {
                bar.setPadding(0, dp(6f), 0, desiredLift);
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

        private void hookWindowTouchForScroll() {
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
                                if ("dispatchTouchEvent".equals(method.getName()) && args != null && args.length == 1
                                        && args[0] instanceof MotionEvent) {
                                    onGlobalTouchEvent((MotionEvent) args[0]);
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

        private boolean isTouchInsideView(View v, float rawX, float rawY) {
            if (v == null || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0) return false;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
        }

        private void onGlobalTouchEvent(MotionEvent ev) {
            if (!attached || bar.getVisibility() != View.VISIBLE) return;
            float rawX = ev.getRawX();
            float rawY = ev.getRawY();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (isTouchInsideView(bar, rawX, rawY) || isTouchInsideView(miniPlayerView, rawX, rawY)) {
                        scrollTracking = false;
                    } else {
                        scrollTracking = true;
                        scrollLastRawY = rawY;
                        scrollAccumY = 0f;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!scrollTracking) break;
                    float dy = rawY - scrollLastRawY;
                    scrollLastRawY = rawY;
                    // Reset accumulator when scroll direction reverses
                    if ((dy > 0f && scrollAccumY < 0f) || (dy < 0f && scrollAccumY > 0f)) {
                        scrollAccumY = 0f;
                    }
                    scrollAccumY += dy;
                    if (scrollAccumY < -dpf(26f) && collapseTarget < 0.5f) {
                        // Finger swiped UP -> scrolling DOWN -> collapse TabBar & drop Mini-Player
                        collapseTarget = 1f;
                        scheduleFrame();
                    } else if (scrollAccumY > dpf(20f) && collapseTarget > 0.5f) {
                        // Finger swiped DOWN -> scrolling UP -> expand TabBar & lift Mini-Player
                        collapseTarget = 0f;
                        scheduleFrame();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scrollTracking = false;
                    scrollAccumY = 0f;
                    break;
            }
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            if (attached) return;
            attached = true;
            disableAncestorClipping();
            ensureFloatingBottomLift();
            hookWindowTouchForScroll();
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
            ensureFloatingBottomLift();
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
                if (miniPlayerView != null) miniPlayerView.invalidate();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        private void locateAndStyleMiniPlayer() {
            if (miniPlayerView != null && miniPlayerView.isAttachedToWindow()) {
                if (miniPlayerView.getBackground() != miniBgDrawable) {
                    miniPlayerView.setBackground(miniBgDrawable);
                }
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastMiniSearchMs < 500L) return;
            lastMiniSearchMs = now;

            View root = bar.getRootView();
            if (root == null) return;
            View content = root.findViewById(android.R.id.content);
            if (content == null) content = root;

            View found = findMiniPlayerRecursive(content);
            if (found != null && found != bar) {
                miniPlayerView = found;
                found.setWillNotDraw(false);
                if (found instanceof ViewGroup) {
                    ((ViewGroup) found).setClipChildren(false);
                    ((ViewGroup) found).setClipToPadding(false);
                }
                found.setElevation(0f);
                found.setBackground(miniBgDrawable);
                // Disable clipping on mini-player parents too
                ViewParent p = found.getParent();
                int depth = 0;
                while (p instanceof ViewGroup && depth < 6) {
                    ((ViewGroup) p).setClipChildren(false);
                    ((ViewGroup) p).setClipToPadding(false);
                    p = p.getParent();
                    depth++;
                }
            }
        }

        private View findMiniPlayerRecursive(View v) {
            if (v == null || v == bar) return null;
            int id = v.getId();
            if (id != View.NO_ID) {
                try {
                    String name = v.getResources().getResourceEntryName(id);
                    if ("footer_follow_button".equals(name) || "footer_like_button".equals(name)) {
                        // Walk up to the mini-player pill container (ConstraintLayout / ViewGroup with height 40..96dp)
                        ViewParent p = v.getParent();
                        while (p instanceof View) {
                            View pv = (View) p;
                            int h = pv.getHeight();
                            if (h >= dp(40f) && h <= dp(96f) && pv.getWidth() > dp(200f)) {
                                // Check if immediate parent also wraps just the pill
                                if (pv.getBackground() != null) return pv;
                                if (pv.getParent() instanceof View) {
                                    View ppv = (View) pv.getParent();
                                    if (ppv.getHeight() >= dp(40f) && ppv.getHeight() <= dp(96f) && ppv.getBackground() != null) {
                                        return ppv;
                                    }
                                }
                                return pv;
                            }
                            p = pv.getParent();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                int count = vg.getChildCount();
                for (int i = 0; i < count; i++) {
                    View res = findMiniPlayerRecursive(vg.getChildAt(i));
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

        private boolean isTabViewSelected(View child) {
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

        private void ensureMenuMargins(ViewGroup menu) {
            // Outer glass bar sits at [12dp, barW - 12dp]; tabs sit inside [16dp, barW - 16dp] (4dp concentric gap)
            int targetMarginH = dp(16f);
            ViewGroup.LayoutParams rawLp = menu.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;
                if (lp.leftMargin != targetMarginH || lp.rightMargin != targetMarginH) {
                    lp.leftMargin = targetMarginH;
                    lp.rightMargin = targetMarginH;
                    menu.setLayoutParams(lp);
                }
            }
        }

        private void syncTabState(boolean snap) {
            ViewGroup menu = getMenuView();
            if (menu == null) return;
            menu.setClipChildren(false);
            menu.setClipToPadding(false);
            if (menu.getBackground() != null) {
                menu.setBackgroundColor(Color.TRANSPARENT);
            }
            ensureMenuMargins(menu);

            visibleTabs.clear();
            tabRects.clear();

            int rawCount = menu.getChildCount();
            int selected = -1;
            float menuLeft = menu.getLeft() + menu.getTranslationX();
            float menuTop = menu.getTop() + menu.getTranslationY();

            for (int i = 0; i < rawCount; i++) {
                View child = menu.getChildAt(i);
                if (child.getBackground() != null) {
                    child.setBackgroundColor(Color.TRANSPARENT);
                }
                child.setOnTouchListener(this);
                if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0 && child.getHeight() > 0) {
                    int visIdx = visibleTabs.size();
                    visibleTabs.add(child);
                    tabRects.add(new RectF(
                            menuLeft + child.getLeft() + child.getTranslationX(),
                            menuTop + child.getTop() + child.getTranslationY(),
                            menuLeft + child.getRight() + child.getTranslationX(),
                            menuTop + child.getBottom() + child.getTranslationY()
                    ));
                    if (isTabViewSelected(child)) {
                        selected = visIdx;
                    }
                }
            }

            int count = visibleTabs.size();
            if (count <= 0) return;

            if (currentIndex >= count) {
                currentIndex = count - 1;
            }
            if (selected < 0) {
                selected = currentIndex;
            }

            if (!trackingTouch && (selected != currentIndex || snap)) {
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

        private float toBarX(View v, float x) {
            float bx = x;
            View cur = v;
            while (cur != null && cur != bar) {
                bx += cur.getLeft() + cur.getTranslationX();
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
            return bx;
        }

        private float toBarY(View v, float y) {
            float by = y;
            View cur = v;
            while (cur != null && cur != bar) {
                by += cur.getTop() + cur.getTranslationY();
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
            return by;
        }

        private int indexAt(float x) {
            int count = tabRects.size();
            if (count <= 0) return 0;
            int bestIdx = 0;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                RectF r = tabRects.get(i);
                if (x >= r.left && x <= r.right) {
                    return i;
                }
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

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (collapseProgress > 0.7f) return false;
            syncTabState(false);
            int count = visibleTabs.size();
            if (count <= 0) return false;

            float x = toBarX(v, event.getX());
            float y = toBarY(v, event.getY());

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
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
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!trackingTouch) return false;
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
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!trackingTouch) return false;
                    trackingTouch = false;
                    int target = event.getActionMasked() == MotionEvent.ACTION_CANCEL
                            ? touchStartIndex
                            : Math.max(0, Math.min(count - 1, Math.round(springTarget)));
                    springTarget = target;
                    pressTarget = 0f;
                    scaleXTarget = 1f;
                    scaleYTarget = 1f;
                    rawOffsetAccum = 0f;
                    panelOffsetPx = 0f;
                    dragVelocity = 0f;
                    if (target != currentIndex) {
                        currentIndex = target;
                        collapseTarget = 0f;
                        dispatchTabClick(target);
                    } else if (!isDragging && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        collapseTarget = 0f;
                        dispatchTabClick(touchDownTargetIndex);
                    }
                    isDragging = false;
                    scheduleFrame();
                    return true;
                }
            }
            return false;
        }

        private void dispatchTabClick(int visibleIndex) {
            if (visibleIndex >= 0 && visibleIndex < visibleTabs.size()) {
                visibleTabs.get(visibleIndex).performClick();
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
            float barShift = (outerRect.height() > 0f ? outerRect.height() + dpf(18f) : dpf(76f));

            // TabBar smoothly slides down + shrinks slightly + fades out on scroll down (like video 18s)
            bar.setTranslationY(p * barShift);
            float barScale = 1f - 0.10f * p;
            bar.setScaleX(barScale);
            bar.setScaleY(barScale);
            bar.setAlpha(Math.max(0f, 1f - p * 1.15f));

            // Mini-player smoothly drops into the TabBar's floating slot on scroll down
            if (miniPlayerView != null && miniPlayerView.isAttachedToWindow()) {
                float dropDist = outerRect.height() > 0f ? outerRect.height() + dpf(6f) : dpf(66f);
                miniPlayerView.setTranslationY(p * dropDist);
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

            float[] s4 = stepSpring(collapseProgress, collapseVel, collapseTarget, 340f, 0.88f, dt);
            collapseProgress = Math.max(0f, Math.min(1f, s4[0]));
            collapseVel = s4[1];
            if (Math.abs(collapseProgress - collapseTarget) > 0.002f || Math.abs(collapseVel) > 0.005f) moving = true;

            applyCollapseTransforms();
            bar.invalidate();
            if (miniPlayerView != null) miniPlayerView.invalidate();
            if (moving || trackingTouch) {
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
            if (Build.VERSION.SDK_INT < 29) return true;
            if (bar.getWidth() > 0 && bar.getHeight() > 0 && bar.getAlpha() > 0.02f) {
                captureBackdropFor(bar, true);
            }
            if (miniPlayerView != null && miniPlayerView.isAttachedToWindow()
                    && miniPlayerView.getVisibility() == View.VISIBLE
                    && miniPlayerView.getWidth() > 0 && miniPlayerView.getHeight() > 0) {
                captureBackdropFor(miniPlayerView, false);
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
            if (view == bar || view == miniPlayerView || view.getVisibility() != View.VISIBLE) return;
            if (!isAncestorOf(view, bar) && !isAncestorOf(view, miniPlayerView)) {
                view.draw(c);
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                Drawable bg = vg.getBackground();
                if (bg != null) {
                    bg.draw(c);
                }
                int count = vg.getChildCount();
                for (int i = 0; i < count; i++) {
                    View child = vg.getChildAt(i);
                    if (child == bar || child == miniPlayerView || child.getVisibility() != View.VISIBLE) continue;
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
            node.setPosition(0, 0, w, h);

            int[] targetLoc = new int[2];
            int[] contentLoc = new int[2];
            targetView.getLocationInWindow(targetLoc);
            content.getLocationInWindow(contentLoc);
            float dx = contentLoc[0] - targetLoc[0];
            float dy = contentLoc[1] - targetLoc[1];

            capturing = true;
            Canvas c = node.beginRecording(w, h);
            try {
                c.translate(dx, dy);
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
            float safeBottom = Math.max(safeTop + dpf(44f), barH - Math.max(bar.getPaddingBottom(), dp(6f)) + dpf(2f));
            safeBottom = Math.min(safeBottom, barH - dpf(2f));

            // Horizontal bounds strictly match NPatch MainScreen (12dp margin), wrapping menu (16dp margin) by 4dp
            float left = dpf(12f) + panelOffsetPx;
            float right = barW - dpf(12f) + panelOffsetPx;

            if (!tabRects.isEmpty()) {
                float minTop = Float.MAX_VALUE;
                float maxBottom = -Float.MAX_VALUE;
                for (RectF r : tabRects) {
                    if (r.top < minTop) minTop = r.top;
                    if (r.bottom > maxBottom) maxBottom = r.bottom;
                }
                float padV = dpf(4f);
                float top = Math.max(safeTop, minTop - padV);
                float bottom = Math.min(safeBottom, maxBottom + padV);
                float targetH = Math.min(dpf(64f), safeBottom - safeTop);
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
                } else if (bottom - top > dpf(64f)) {
                    float cy = (top + bottom) * 0.5f;
                    top = Math.max(safeTop, cy - dpf(32f));
                    bottom = Math.min(safeBottom, top + dpf(64f));
                }
                outerRect.set(left, top, right, bottom);
            } else {
                float pillH = Math.min(dpf(64f), Math.max(dpf(44f), safeBottom - safeTop));
                float top = safeTop + ((safeBottom - safeTop) - pillH) * 0.5f;
                outerRect.set(left, top, right, top + pillH);
            }
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
                if (capturing || miniPlayerView == null) return;
                int w = miniPlayerView.getWidth();
                int h = miniPlayerView.getHeight();
                if (w <= 0 || h <= 0) return;

                boolean dark = isDarkTheme();
                miniRect.set(dpf(2f), dpf(2f), w - dpf(2f), h - dpf(2f));
                float radius = miniRect.height() * 0.5f;

                // 1. Drop shadow
                shadowPaint.setColor(Color.TRANSPARENT);
                shadowPaint.setShadowLayer(dpf(10f), 0f, dpf(3f),
                        Color.argb(dark ? 56 : 28, 0, 0, 0));
                canvas.drawRoundRect(miniRect, radius, radius, shadowPaint);

                // 2. Real-time Liquid Glass refraction + vibrancy + blur
                canvas.save();
                miniClipPath.reset();
                miniClipPath.addRoundRect(miniRect, radius, radius, Path.Direction.CW);
                canvas.clipPath(miniClipPath);

                if (Build.VERSION.SDK_INT >= 33 && miniBackdropNode != null
                        && miniBackdropNode.hasDisplayList() && miniLensShader != null
                        && canvas.isHardwareAccelerated()) {
                    if (miniGlassNode == null) {
                        miniGlassNode = new RenderNode("sc_mini_glass");
                    }
                    miniGlassNode.setPosition(0, 0, w, h);

                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(1.5f);
                    RenderEffect vibrancyEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                    float blurPx = dpf(4f);
                    RenderEffect blurEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);

                    miniLensShader.setFloatUniform("size", miniRect.width(), miniRect.height());
                    miniLensShader.setFloatUniform("offset", -miniRect.left, -miniRect.top);
                    miniLensShader.setFloatUniform("cornerRadii", new float[]{radius, radius, radius, radius});
                    miniLensShader.setFloatUniform("refractionHeight", dpf(18f));
                    miniLensShader.setFloatUniform("refractionAmount", -dpf(18f));
                    miniLensShader.setFloatUniform("depthEffect", 0f);
                    RenderEffect lensEffect = RenderEffect.createRuntimeShaderEffect(miniLensShader, "content");

                    RenderEffect chain = RenderEffect.createChainEffect(lensEffect,
                            RenderEffect.createChainEffect(blurEffect, vibrancyEffect));
                    miniGlassNode.setRenderEffect(chain);

                    Canvas gc = miniGlassNode.beginRecording(w, h);
                    try {
                        gc.drawRenderNode(miniBackdropNode);
                    } finally {
                        miniGlassNode.endRecording();
                    }
                    canvas.drawRenderNode(miniGlassNode);
                }

                // 3. Translucent frosted glass tint (matches TabBar & video 18s)
                surfacePaint.setColor(dark ? Color.argb(102, 24, 24, 28) : Color.argb(115, 245, 247, 252));
                canvas.drawRoundRect(miniRect, radius, radius, surfacePaint);
                canvas.restore();

                // 4. Gravity-rotated specular BloomStroke rim
                drawBloomRim(canvas, miniRect, radius, 0.75f, -45f);
            }

            @Override
            public void setAlpha(int alpha) {}

            @Override
            public void setColorFilter(ColorFilter colorFilter) {}

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        }

        private final class GlassBackgroundDrawable extends Drawable {
            private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                if (capturing) return;
                updateGeometry();
                if (outerRect.width() <= 0f || outerRect.height() <= 0f) return;

                boolean dark = isDarkTheme();
                float radius = outerRect.height() * 0.5f;

                // NPatch outer bar press scale: lerp(1f, 1f + 16.dp / width, pressProgress)
                float outerScale = 1f + (dpf(16f) / Math.max(1f, outerRect.width())) * pressProgress;
                canvas.save();
                if (pressProgress > 0.001f) {
                    canvas.scale(outerScale, outerScale, outerRect.centerX(), outerRect.centerY());
                }

                // 1. Drop shadow
                shadowPaint.setColor(Color.TRANSPARENT);
                shadowPaint.setShadowLayer(dpf(10f), 0f, dpf(3f),
                        Color.argb(dark ? 51 : 26, 0, 0, 0));
                canvas.drawRoundRect(outerRect, radius, radius, shadowPaint);

                // 2. Backdrop + Vibrancy (sat 1.5) + Blur (4.dp) + Outer Lens Refraction (24.dp)
                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(outerRect, radius, radius, Path.Direction.CW);
                canvas.clipPath(clipPath);

                if (Build.VERSION.SDK_INT >= 33 && backdropNode != null
                        && backdropNode.hasDisplayList() && canvas.isHardwareAccelerated()) {
                    if (outerGlassNode == null) {
                        outerGlassNode = new RenderNode("sc_outer_glass");
                    }
                    int w = bar.getWidth();
                    int h = bar.getHeight();
                    outerGlassNode.setPosition(0, 0, w, h);

                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(1.5f);
                    RenderEffect vibrancyEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                    float blurPx = dpf(4f);
                    RenderEffect blurEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);

                    outerLensShader.setFloatUniform("size", outerRect.width(), outerRect.height());
                    outerLensShader.setFloatUniform("offset", -outerRect.left, -outerRect.top);
                    outerLensShader.setFloatUniform("cornerRadii", new float[]{radius, radius, radius, radius});
                    outerLensShader.setFloatUniform("refractionHeight", dpf(24f));
                    outerLensShader.setFloatUniform("refractionAmount", -dpf(24f));
                    outerLensShader.setFloatUniform("depthEffect", 0f);
                    RenderEffect lensEffect = RenderEffect.createRuntimeShaderEffect(outerLensShader, "content");

                    RenderEffect chain = RenderEffect.createChainEffect(lensEffect,
                            RenderEffect.createChainEffect(blurEffect, vibrancyEffect));
                    outerGlassNode.setRenderEffect(chain);

                    Canvas gc = outerGlassNode.beginRecording(w, h);
                    try {
                        gc.drawRenderNode(backdropNode);
                    } finally {
                        outerGlassNode.endRecording();
                    }
                    canvas.drawRenderNode(outerGlassNode);
                }

                // 3. Glass surface tint
                surfacePaint.setColor(dark ? Color.argb(102, 24, 24, 28) : Color.argb(115, 245, 247, 252));
                canvas.drawRoundRect(outerRect, radius, radius, surfacePaint);
                canvas.restore();

                // 4. Gravity-rotated BloomStroke rim highlight
                drawBloomRim(canvas, outerRect, radius, 0.75f, -45f);
                canvas.restore();
            }

            @Override
            public void setAlpha(int alpha) {}

            @Override
            public void setColorFilter(ColorFilter colorFilter) {}

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        }

        private final class GlassOverlayDrawable extends Drawable {
            private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint pillSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint innerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint surfaceTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final float[] tmpCX = new float[1];
            private final float[] tmpCY = new float[1];
            private final float[] tmpW = new float[1];

            @Override
            public void draw(Canvas canvas) {
                if (capturing) return;
                updateGeometry();
                if (tabRects.isEmpty() || outerRect.width() <= 0f) return;

                boolean dark = isDarkTheme();
                float outerRadius = outerRect.height() * 0.5f;
                computeInterpolatedPillBounds(springValue, tmpCX, tmpCY, tmpW);
                float centerX = tmpCX[0];
                float centerY = tmpCY[0];
                float tabW = tmpW[0];

                // 1. InteractiveHighlight (AGSL BlendMode.PLUS)
                if (Build.VERSION.SDK_INT >= 33 && pressProgress > 0.005f && highlightShader != null) {
                    canvas.save();
                    clipPath.reset();
                    clipPath.addRoundRect(outerRect, outerRadius, outerRadius, Path.Direction.CW);
                    canvas.clipPath(clipPath);

                    highlightPaint.setBlendMode(BlendMode.PLUS);
                    highlightPaint.setShader(null);
                    highlightPaint.setColor(Color.argb((int) (255 * 0.06f * pressProgress), 255, 255, 255));
                    canvas.drawRect(outerRect, highlightPaint);

                    highlightShader.setFloatUniform("size", outerRect.width(), outerRect.height());
                    highlightShader.setColorUniform("color", Color.argb((int) (255 * 0.12f * pressProgress), 255, 255, 255));
                    highlightShader.setFloatUniform("radius", outerRect.height() * 1.2f);
                    highlightShader.setFloatUniform("position", centerX - outerRect.left, centerY - outerRect.top);
                    highlightPaint.setShader(highlightShader);
                    canvas.drawRect(outerRect, highlightPaint);
                    highlightPaint.setShader(null);
                    highlightPaint.setBlendMode(null);
                    canvas.restore();
                }

                // 2. Sliding Glass Pill Indicator (strictly concentric inside outerRect at rest)
                float innerLeft = outerRect.left + dpf(4f);
                float innerRight = outerRect.right - dpf(4f);
                float maxBaseW = (innerRight - innerLeft) / Math.max(1, tabRects.size());
                float baseW = Math.min(tabW, maxBaseW);
                float pillH = Math.min(dpf(56f), Math.max(dpf(36f), outerRect.height() - dpf(8f)));

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

                // 7-band chromatic dispersion lens over BLURRED outer glass + magnified SoundCloud tabs
                if (Build.VERSION.SDK_INT >= 33 && pressProgress > 0.01f
                        && outerGlassNode != null && outerGlassNode.hasDisplayList()
                        && pillDispersionShader != null && canvas.isHardwareAccelerated()) {
                    if (pillCombinedNode == null) pillCombinedNode = new RenderNode("sc_pill_combined");
                    if (pillGlassNode == null) pillGlassNode = new RenderNode("sc_pill_glass");

                    int w = bar.getWidth();
                    int h = bar.getHeight();
                    pillCombinedNode.setPosition(0, 0, w, h);
                    pillGlassNode.setPosition(0, 0, w, h);

                    Canvas cc = pillCombinedNode.beginRecording(w, h);
                    try {
                        cc.drawRenderNode(outerGlassNode);
                        surfaceTintPaint.setColor(dark ? Color.argb(102, 24, 24, 28) : Color.argb(115, 245, 247, 252));
                        cc.drawRect(0, 0, w, h, surfaceTintPaint);

                        ViewGroup menu = getMenuView();
                        if (menu != null) {
                            float tabScale = 1f + 0.2f * pressProgress;
                            cc.save();
                            cc.scale(tabScale, tabScale, centerX, centerY);
                            cc.translate(menu.getLeft() + menu.getTranslationX(), menu.getTop() + menu.getTranslationY());
                            menu.draw(cc);
                            cc.restore();
                        }
                    } finally {
                        pillCombinedNode.endRecording();
                    }

                    pillDispersionShader.setFloatUniform("size", pillRect.width(), pillRect.height());
                    pillDispersionShader.setFloatUniform("offset", -pillRect.left, -pillRect.top);
                    pillDispersionShader.setFloatUniform("cornerRadii", new float[]{pillRadius, pillRadius, pillRadius, pillRadius});
                    pillDispersionShader.setFloatUniform("refractionHeight", dpf(10f) * pressProgress);
                    pillDispersionShader.setFloatUniform("refractionAmount", -dpf(14f) * pressProgress);
                    pillDispersionShader.setFloatUniform("depthEffect", 1f);
                    pillDispersionShader.setFloatUniform("chromaticAberration", 0.5f);
                    pillGlassNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(pillDispersionShader, "content"));

                    Canvas pg = pillGlassNode.beginRecording(w, h);
                    try {
                        pg.drawRenderNode(pillCombinedNode);
                    } finally {
                        pillGlassNode.endRecording();
                    }
                    canvas.drawRenderNode(pillGlassNode);
                }

                // Pill resting & pressed surface tint
                int restAlpha = Math.round(25.5f * (1f - pressProgress));
                pillSurfacePaint.setColor(dark
                        ? Color.argb(restAlpha, 255, 255, 255)
                        : Color.argb(restAlpha, 0, 0, 0));
                canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillSurfacePaint);

                if (pressProgress > 0.01f) {
                    pillSurfacePaint.setColor(Color.argb(Math.round(8f * pressProgress), 0, 0, 0));
                    canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillSurfacePaint);

                    // InnerShadow
                    innerShadowPaint.setStyle(Paint.Style.STROKE);
                    innerShadowPaint.setStrokeWidth(dpf(8f) * pressProgress);
                    innerShadowPaint.setColor(Color.argb(Math.round(38f * pressProgress), 0, 0, 0));
                    canvas.drawRoundRect(pillRect, pillRadius, pillRadius, innerShadowPaint);
                }
                canvas.restore();

                // Pill specular rim highlight
                if (pressProgress > 0.01f) {
                    drawBloomRim(canvas, pillRect, pillRadius, pressProgress, 90f);
                }
            }

            @Override
            public void setAlpha(int alpha) {}

            @Override
            public void setColorFilter(ColorFilter colorFilter) {}

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
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
