package nl.johninnl.soundcloud;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
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
 * 1:1 port of NPatch FloatingGlassBottomBar (AGSL + RenderNode) for SoundCloud:
 * - Direct Window-level touch dispatch so the Mini-Player BottomSheet NEVER blocks TabBar touches.
 * - Separated right circular Liquid Glass Search/Menu button + Author ("Tác giả") tab in the 5th slot.
 * - Floating Liquid Glass Mini-Player with two scroll-down modes:
 *     1) Replace mode (default): TabBar collapses into the right Search circle (morphs to Menu),
 *        Mini-Player drops into the TabBar's left slot.
 *     2) Stacked mode ("Xếp chồng khi vuốt xuống"): Mini-Player stacks on top of TabBar (still showing
 *        the bottom peek of TabBar). Swiping UP on the stacked Mini-Player plays a 2-phase card-deck
 *        animation where the Mini-Player lifts up and tucks underneath the TabBar!
 * - In-app TabBar Settings injected into SoundCloud Settings + Author dialog.
 */
@SuppressLint("NewApi")
public final class LiquidGlassTabBar {

    private static final String PREFS_NAME = "sc_liquid_glass_prefs";
    private static final String KEY_STACKED_MODE = "stacked_scroll_mode";
    private static final String KEY_SCROLL_COLLAPSE = "scroll_collapse_enabled";
    private static final String KEY_BLUR_DP = "glass_blur_dp";

    private static final WeakHashMap<View, Controller> ATTACHED = new WeakHashMap<>();
    private static WeakReference<Controller> ACTIVE_CONTROLLER = new WeakReference<>(null);

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
            ACTIVE_CONTROLLER = new WeakReference<>(controller);
            controller.install();
        }
    }

    /**
     * Called from SettingsFragment.onCreateView(...) to inject a floating "Cài đặt TabBar Liquid Glass"
     * button directly inside SoundCloud's Settings screen.
     */
    public static View wrapSettingsView(View original) {
        if (original == null) return null;
        try {
            Context ctx = original.getContext();
            float density = ctx.getResources().getDisplayMetrics().density;

            FrameLayout wrapper = new FrameLayout(ctx);
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
            bg.setStroke(Math.round(1.2f * density), Color.argb(140, 255, 255, 255));
            btn.setBackground(bg);
            btn.setElevation(8f * density);
            btn.setOnClickListener(v -> showSettingsDialog(ctx));

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

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isStackedMode(Context ctx) {
        return prefs(ctx).getBoolean(KEY_STACKED_MODE, false);
    }

    private static boolean isScrollCollapseEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SCROLL_COLLAPSE, true);
    }

    private static float getGlassBlurDp(Context ctx) {
        return prefs(ctx).getFloat(KEY_BLUR_DP, 4f);
    }

    private static void notifySettingsChanged() {
        Controller c = ACTIVE_CONTROLLER.get();
        if (c != null) c.onSettingsUpdated();
    }

    private static void showAuthorDialog(Context ctx) {
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(22f * density);
            root.setPadding(pad, Math.round(20f * density), pad, Math.round(16f * density));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.rgb(20, 20, 24));
            cardBg.setCornerRadius(24f * density);
            cardBg.setStroke(Math.round(1f * density), Color.argb(90, 255, 255, 255));
            root.setBackground(cardBg);

            TextView badge = new TextView(ctx);
            badge.setText("DIGITALXIAO MOD");
            badge.setTextColor(Color.rgb(255, 110, 40));
            badge.setTextSize(11f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            root.addView(badge);

            TextView title = new TextView(ctx);
            title.setText("SoundCloud Liquid Glass");
            title.setTextColor(Color.WHITE);
            title.setTextSize(20f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, Math.round(4f * density), 0, Math.round(8f * density));
            root.addView(title);

            TextView desc = new TextView(ctx);
            desc.setText("Ứng dụng được tùy biến giao diện Liquid Glass & phát triển bởi DigitalXiao.");
            desc.setTextColor(Color.argb(215, 235, 235, 242));
            desc.setTextSize(14f);
            desc.setPadding(0, 0, 0, Math.round(16f * density));
            root.addView(desc);

            final AlertDialog dialog = new AlertDialog.Builder(ctx).setView(root).create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            addActionPill(ctx, root, "TikTok: @digitalxiaoiu5", Color.rgb(34, 34, 42), v -> {
                openUrl(ctx, "https://www.tiktok.com/@digitalxiaoiu5");
                dialog.dismiss();
            });
            addActionPill(ctx, root, "Box Telegram: t.me/DigitalXiao", Color.rgb(26, 78, 122), v -> {
                openUrl(ctx, "https://t.me/DigitalXiao");
                dialog.dismiss();
            });
            addActionPill(ctx, root, "✦ Cài đặt TabBar Liquid Glass", Color.rgb(255, 85, 0), v -> {
                dialog.dismiss();
                showSettingsDialog(ctx);
            });
            addActionPill(ctx, root, "Đóng", Color.argb(40, 255, 255, 255), v -> dialog.dismiss());

            dialog.show();
        } catch (Throwable ignored) {
        }
    }

    private static void showSettingsDialog(Context ctx) {
        try {
            float density = ctx.getResources().getDisplayMetrics().density;
            SharedPreferences sp = prefs(ctx);

            ScrollView scroll = new ScrollView(ctx);
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(22f * density);
            root.setPadding(pad, Math.round(20f * density), pad, Math.round(16f * density));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.rgb(20, 20, 24));
            cardBg.setCornerRadius(24f * density);
            cardBg.setStroke(Math.round(1f * density), Color.argb(90, 255, 255, 255));
            root.setBackground(cardBg);

            TextView title = new TextView(ctx);
            title.setText("Cài đặt TabBar Liquid Glass");
            title.setTextColor(Color.WHITE);
            title.setTextSize(19f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, 0, 0, Math.round(14f * density));
            root.addView(title);

            // Switch 1: Stacked mode on scroll down
            addSettingSwitch(
                    ctx, root,
                    "Xếp chồng khi vuốt xuống",
                    "Ô nhạc xếp chồng lên TabBar (vẫn thấy mép dưới TabBar). Vuốt ô nhạc lên để ô nhạc bay lên rồi chui xuống dưới TabBar.",
                    sp.getBoolean(KEY_STACKED_MODE, false),
                    checked -> {
                        sp.edit().putBoolean(KEY_STACKED_MODE, checked).apply();
                        notifySettingsChanged();
                    }
            );

            // Switch 2: Auto collapse on scroll
            addSettingSwitch(
                    ctx, root,
                    "Tự thu gọn khi cuộn",
                    "Tự kích hoạt chế độ thu gọn / xếp chồng khi cuộn xuống và mở lại khi cuộn lên.",
                    sp.getBoolean(KEY_SCROLL_COLLAPSE, true),
                    checked -> {
                        sp.edit().putBoolean(KEY_SCROLL_COLLAPSE, checked).apply();
                        notifySettingsChanged();
                    }
            );

            // Blur presets
            TextView blurLabel = new TextView(ctx);
            blurLabel.setText("Độ mờ kính (Blur)");
            blurLabel.setTextColor(Color.WHITE);
            blurLabel.setTextSize(14f);
            blurLabel.setTypeface(Typeface.DEFAULT_BOLD);
            blurLabel.setPadding(0, Math.round(8f * density), 0, Math.round(8f * density));
            root.addView(blurLabel);

            LinearLayout blurRow = new LinearLayout(ctx);
            blurRow.setOrientation(LinearLayout.HORIZONTAL);
            float curBlur = sp.getFloat(KEY_BLUR_DP, 4f);
            float[] blurVals = new float[]{4f, 8f, 14f};
            String[] blurNames = new String[]{"Nhẹ (4dp)", "Vừa (8dp)", "Đậm (14dp)"};
            TextView[] blurBtns = new TextView[3];
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                TextView b = new TextView(ctx);
                b.setText(blurNames[i]);
                b.setTextSize(12.5f);
                b.setGravity(Gravity.CENTER);
                b.setPadding(Math.round(8f * density), Math.round(10f * density), Math.round(8f * density), Math.round(10f * density));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) blp.leftMargin = Math.round(8f * density);
                b.setLayoutParams(blp);
                styleBlurChip(b, Math.abs(curBlur - blurVals[i]) < 1f, density);
                b.setOnClickListener(v -> {
                    sp.edit().putFloat(KEY_BLUR_DP, blurVals[idx]).apply();
                    for (int j = 0; j < 3; j++) styleBlurChip(blurBtns[j], j == idx, density);
                    notifySettingsChanged();
                });
                blurBtns[i] = b;
                blurRow.addView(b);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = Math.round(16f * density);
            root.addView(blurRow, rowLp);

            scroll.addView(root);
            final AlertDialog dialog = new AlertDialog.Builder(ctx).setView(scroll).create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            addActionPill(ctx, root, "Xong", Color.rgb(255, 85, 0), v -> dialog.dismiss());
            dialog.show();
        } catch (Throwable ignored) {
        }
    }

    private static void styleBlurChip(TextView tv, boolean active, float density) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14f * density);
        bg.setColor(active ? Color.rgb(255, 85, 0) : Color.argb(40, 255, 255, 255));
        tv.setBackground(bg);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private interface BoolConsumer {
        void accept(boolean value);
    }

    private static void addSettingSwitch(Context ctx, LinearLayout parent, String title, String subtitle,
                                         boolean initial, BoolConsumer onChange) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(8f * density), 0, Math.round(12f * density));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = Math.round(12f * density);
        textCol.setLayoutParams(tlp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText(subtitle);
        tvSub.setTextColor(Color.argb(175, 220, 220, 230));
        tvSub.setTextSize(12.5f);
        tvSub.setPadding(0, Math.round(3f * density), 0, 0);
        textCol.addView(tvSub);

        Switch sw = new Switch(ctx);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> onChange.accept(isChecked));

        row.addView(textCol);
        row.addView(sw);
        parent.addView(row);
    }

    private static void addActionPill(Context ctx, LinearLayout parent, String text, int color, View.OnClickListener onClick) {
        float density = ctx.getResources().getDisplayMetrics().density;
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(Math.round(14f * density), Math.round(12f * density), Math.round(14f * density), Math.round(12f * density));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(16f * density);
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(8f * density);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(onClick);
        parent.addView(btn);
    }

    private static void openUrl(Context ctx, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private LiquidGlassTabBar() {}

    /** Clean vector icon drawable for the "Tác giả" (Author) tab slot. */
    private static final class AuthorIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        AuthorIconDrawable(float density) {
            this.density = density;
            paint.setColor(Color.argb(220, 235, 235, 240));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.8f * density);
        }

        @Override
        public void draw(Canvas canvas) {
            int w = getBounds().width();
            int h = getBounds().height();
            if (w <= 0 || h <= 0) return;
            float cx = getBounds().centerX();
            float cy = getBounds().centerY();
            float r = Math.min(w, h) * 0.42f;
            // Head circle
            canvas.drawCircle(cx, cy - r * 0.35f, r * 0.36f, paint);
            // Shoulders arc
            RectF arc = new RectF(cx - r * 0.72f, cy + r * 0.12f, cx + r * 0.72f, cy + r * 1.15f);
            canvas.drawArc(arc, 200f, 140f, false, paint);
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
        private RenderNode outerGlassNode;
        private RenderNode searchGlassNode;
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
        private int miniOrigRightMargin = Integer.MIN_VALUE;

        private RuntimeShader outerLensShader;
        private RuntimeShader searchLensShader;
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

        // Settings cache
        private boolean stackedMode = false;
        private boolean scrollCollapseEnabled = true;
        private float blurDp = 4f;

        // Author tab (repurposed from Search slot) + Right Search circle
        private View authorTabView = null;
        private int authorTabVisibleIndex = -1;
        private int searchTabItemId = View.NO_ID;
        private boolean searchTabSelected = false;
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

        // Right Search/Menu circle press scale
        private float searchBtnScale = 1f;
        private float searchBtnScaleTarget = 1f;
        private float searchBtnScaleVel = 0f;
        private boolean trackingSearchBtn = false;

        // Scroll-collapse spring state (0f = expanded, 1f = collapsed / stacked)
        private float collapseProgress = 0f;
        private float collapseTarget = 0f;
        private float collapseVel = 0f;

        // Stacked mode card-deck shuffle state:
        // When stacked (collapseProgress ~ 1): Mini-Player sits ON TOP of TabBar (showing bottom peek of TabBar).
        // Swiping UP on the stacked Mini-Player animates shuffleProgress 0 -> 1:
        //   0.0 -> 0.5: Mini-Player lifts UP above TabBar
        //   0.5       : Z-order swaps (TabBar comes to FRONT, Mini-Player goes BEHIND)
        //   0.5 -> 1.0: Mini-Player dives DOWN & tucks behind TabBar!
        private boolean stackedMiniBehind = false;
        private boolean shuffleAnimating = false;
        private float shuffleProgress = 0f; // 0 = front, 1 = tucked behind
        private float shuffleDir = 0f;      // +1 (going behind) or -1 (coming front)

        // Window-level touch state
        private boolean scrollTracking = false;
        private float scrollLastRawY = 0f;
        private float scrollAccumY = 0f;

        private boolean miniSwipeTracking = false;
        private float miniSwipeStartY = 0f;
        private float miniSwipeStartX = 0f;

        // Direct TabBar touch state (intercepted at Window level so Mini-Player BottomSheet NEVER steals it)
        private boolean directBarCapture = false;
        private boolean trackingTouch = false;
        private boolean isDragging = false;
        private float downX = 0f;
        private float downY = 0f;
        private float lastTouchX = 0f;
        private int touchStartIndex = 0;
        private int touchDownTargetIndex = 0;

        // Geometry
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
                outerLensShader = new RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER);
                searchLensShader = new RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER);
                pillDispersionShader = new RuntimeShader(ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER);
                highlightShader = new RuntimeShader(INTERACTIVE_HIGHLIGHT_SHADER);
                miniLensShader = new RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER);
            }
        }

        private void loadSettings() {
            Context ctx = bar.getContext();
            stackedMode = isStackedMode(ctx);
            scrollCollapseEnabled = isScrollCollapseEnabled(ctx);
            blurDp = getGlassBlurDp(ctx);
        }

        void onSettingsUpdated() {
            loadSettings();
            collapseTarget = 0f;
            stackedMiniBehind = false;
            shuffleAnimating = false;
            shuffleProgress = 0f;
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
                                if ("dispatchTouchEvent".equals(method.getName()) && args != null && args.length == 1
                                        && args[0] instanceof MotionEvent) {
                                    MotionEvent ev = (MotionEvent) args[0];
                                    if (interceptWindowTouch(ev)) {
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

        private boolean isTouchInsideView(View v, float rawX, float rawY) {
            if (v == null || !v.isAttachedToWindow() || v.getVisibility() != View.VISIBLE || v.getWidth() <= 0) {
                return false;
            }
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
        }

        /**
         * Intercepts touches at the Window level BEFORE SoundCloud's Mini-Player BottomSheet
         * can steal touches intended for the bottom TabBar!
         */
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

            // 1. If we already captured this gesture for the TabBar / Search button, continue handling it
            if (directBarCapture) {
                handleDirectBarTouch(action, localX, localY);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    directBarCapture = false;
                }
                return true;
            }

            // 2. Stacked Mode: detect upward swipe on Mini-Player while it is stacked on top of TabBar
            if (stackedMode && collapseProgress > 0.65f && !stackedMiniBehind && !shuffleAnimating
                    && miniPlayerView != null && miniPlayerView.isShown()) {
                if (action == MotionEvent.ACTION_DOWN && isTouchInsideView(miniPlayerView, rawX, rawY)) {
                    miniSwipeTracking = true;
                    miniSwipeStartX = rawX;
                    miniSwipeStartY = rawY;
                } else if (miniSwipeTracking && action == MotionEvent.ACTION_MOVE) {
                    float dy = rawY - miniSwipeStartY;
                    float dx = rawX - miniSwipeStartX;
                    if (dy < -dpf(20f) && Math.abs(dy) > Math.abs(dx) * 1.15f) {
                        // User swiped UP on stacked Mini-Player -> lift UP & tuck UNDER TabBar!
                        miniSwipeTracking = false;
                        startCardShuffle(true);
                        return true;
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    miniSwipeTracking = false;
                }
            }

            // 3. Decide if ACTION_DOWN belongs to our TabBar / right Search-Menu circle
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
                    if (!stackedMode && collapseProgress > 0.5f) {
                        // Replace mode & collapsed: let touch pass through to the dropped Mini-Player!
                        return false;
                    }
                    if (stackedMode && collapseProgress > 0.5f && !stackedMiniBehind
                            && isTouchInsideView(miniPlayerView, rawX, rawY)) {
                        // Stacked mode with Mini-Player on top: let Mini-Player handle touches on itself,
                        // while bottom peek below Mini-Player still reaches TabBar!
                        return false;
                    }
                    // TabBar is active/visible -> capture directly so Mini-Player BottomSheet NEVER steals it!
                    directBarCapture = true;
                    handleDirectBarTouch(action, localX, localY);
                    return true;
                }
            }

            // 4. Observe vertical page scroll outside TabBar & Mini-Player to trigger collapse / expand
            if (scrollCollapseEnabled) {
                switch (action) {
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
                        if (scrollTracking) {
                            float dy = rawY - scrollLastRawY;
                            scrollLastRawY = rawY;
                            if ((dy > 0f && scrollAccumY < 0f) || (dy < 0f && scrollAccumY > 0f)) {
                                scrollAccumY = 0f;
                            }
                            scrollAccumY += dy;
                            if (scrollAccumY < -dpf(26f) && collapseTarget < 0.5f) {
                                collapseTarget = 1f;
                                stackedMiniBehind = false;
                                shuffleAnimating = false;
                                shuffleProgress = 0f;
                                scheduleFrame();
                            } else if (scrollAccumY > dpf(20f) && (collapseTarget > 0.5f || stackedMiniBehind)) {
                                collapseTarget = 0f;
                                stackedMiniBehind = false;
                                shuffleAnimating = false;
                                shuffleProgress = 0f;
                                scheduleFrame();
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        scrollTracking = false;
                        scrollAccumY = 0f;
                        break;
                }
            }

            return false;
        }

        private void startCardShuffle(boolean toBehind) {
            shuffleAnimating = true;
            shuffleDir = toBehind ? 1f : -1f;
            if (toBehind) {
                shuffleProgress = 0f;
                stackedMiniBehind = false;
            } else {
                shuffleProgress = 1f;
                stackedMiniBehind = true;
            }
            scheduleFrame();
        }

        private void handleDirectBarTouch(int action, float x, float y) {
            // Right separated Search / Menu circle
            if (action == MotionEvent.ACTION_DOWN && isInsideSearchCircle(x, y)) {
                trackingSearchBtn = true;
                searchBtnScaleTarget = 1.15f;
                scheduleFrame();
                return;
            }
            if (trackingSearchBtn) {
                if (action == MotionEvent.ACTION_UP) {
                    trackingSearchBtn = false;
                    searchBtnScaleTarget = 1f;
                    if (isInsideSearchCircle(x, y)) {
                        if (collapseProgress > 0.4f || collapseTarget > 0.5f) {
                            collapseTarget = 0f;
                            stackedMiniBehind = false;
                            shuffleAnimating = false;
                            shuffleProgress = 0f;
                        } else {
                            triggerSearchTab();
                        }
                    }
                    scheduleFrame();
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    trackingSearchBtn = false;
                    searchBtnScaleTarget = 1f;
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
                        if (clickIdx == authorTabVisibleIndex) {
                            // Author ("Tác giả") tab -> open DigitalXiao info dialog & bounce pill back
                            springTarget = currentIndex;
                            showAuthorDialog(bar.getContext());
                        } else {
                            currentIndex = clickIdx;
                            springTarget = clickIdx;
                            collapseTarget = 0f;
                            stackedMiniBehind = false;
                            shuffleAnimating = false;
                            shuffleProgress = 0f;
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
                if (found.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    miniOrigRightMargin = ((ViewGroup.MarginLayoutParams) found.getLayoutParams()).rightMargin;
                }
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
                        ViewParent p = v.getParent();
                        while (p instanceof View) {
                            View pv = (View) p;
                            int h = pv.getHeight();
                            if (h >= dp(40f) && h <= dp(96f) && pv.getWidth() > dp(200f)) {
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

        private int getTabItemId(View child) {
            if (child == null) return View.NO_ID;
            try {
                Method getItemData = child.getClass().getMethod("getItemData");
                Object itemData = getItemData.invoke(child);
                if (itemData != null) {
                    Method getItemId = itemData.getClass().getMethod("getItemId");
                    Object idObj = getItemId.invoke(itemData);
                    if (idObj instanceof Integer && (Integer) idObj != 0) {
                        return (Integer) idObj;
                    }
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

        /** Repurposes the original Search tab view inside BottomNavigationMenuView into the "Tác giả" tab. */
        private void styleAsAuthorTab(View tabView) {
            if (tabView == null) return;
            if (tabView.getVisibility() != View.VISIBLE) {
                tabView.setVisibility(View.VISIBLE);
            }
            applyAuthorTextAndIcon(tabView);
        }

        private void applyAuthorTextAndIcon(View v) {
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                if (!"Tác giả".equals(tv.getText())) {
                    tv.setText("Tác giả");
                }
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

        private void ensureMenuMargins(ViewGroup menu) {
            int targetLeft = dp(16f);
            int targetRight = dp(12f + 56f + 10f + 4f); // 82dp reserved for right Search circle + gap + 4dp inset
            ViewGroup.LayoutParams rawLp = menu.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;
                if (lp.leftMargin != targetLeft || lp.rightMargin != targetRight) {
                    lp.leftMargin = targetLeft;
                    lp.rightMargin = targetRight;
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
                searchTabSelected = isTabViewSelected(authorTabView);
            }

            visibleTabs.clear();
            tabRects.clear();
            authorTabVisibleIndex = -1;

            int selected = -1;
            float menuLeft = menu.getLeft();
            float menuTop = menu.getTop();

            for (int i = 0; i < rawCount; i++) {
                View child = menu.getChildAt(i);
                if (child.getBackground() != null) {
                    child.setBackgroundColor(Color.TRANSPARENT);
                }
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
                    } else if (isTabViewSelected(child)) {
                        selected = visIdx;
                    }
                }
            }

            int count = visibleTabs.size();
            if (count <= 0) return;

            if (currentIndex >= count) {
                currentIndex = count - 1;
            }
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

        private boolean isInsideSearchCircle(float x, float y) {
            float pad = dpf(6f);
            return x >= searchRect.left - pad && x <= searchRect.right + pad
                    && y >= searchRect.top - pad && y <= searchRect.bottom + pad;
        }

        private void triggerSearchTab() {
            if (searchTabItemId != View.NO_ID && searchTabItemId != 0) {
                try {
                    Method setSelected = bar.getClass().getMethod("setSelectedItemId", int.class);
                    setSelected.invoke(bar, searchTabItemId);
                    searchTabSelected = true;
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

            if (!stackedMode) {
                // === MODE 1: REPLACE (TabBar collapses into right Search circle, Mini-Player drops into left slot) ===
                bar.setZ(dpf(24f));
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
                if (miniPlayerView != null && miniPlayerView.isAttachedToWindow()) {
                    miniPlayerView.setZ(dpf(18f));
                    miniPlayerView.setScaleX(1f);
                    miniPlayerView.setScaleY(1f);
                    miniPlayerView.setAlpha(1f);
                    float dropDist = outerRect.height() > 0f ? outerRect.height() + dpf(8f) : dpf(66f);
                    miniPlayerView.setTranslationY(p * dropDist);
                    updateMiniRightMargin(Math.round(dpf(56f + 10f) * p));
                }
            } else {
                // === MODE 2: STACKED CARD DECK ("Xếp chồng khi vuốt xuống") ===
                // Mini-Player stacks on top of TabBar leaving bottom ~14dp of TabBar peeking out.
                // Swiping UP on the Mini-Player lifts it UP (0 -> 0.5), swaps Z at apex, then tucks it UNDER TabBar (0.5 -> 1)!
                float sh = Math.max(0f, Math.min(1f, shuffleProgress));
                boolean miniOnTop = (sh < 0.5f);

                if (menu != null) {
                    menu.setTranslationX(0f);
                    menu.setTranslationY(0f);
                    float barScale = miniOnTop ? (1f - 0.05f * p) : (0.95f + 0.05f * ((sh - 0.5f) * 2f));
                    menu.setPivotX(menu.getWidth() * 0.5f);
                    menu.setPivotY(menu.getHeight() * 0.5f);
                    menu.setScaleX(barScale);
                    menu.setScaleY(barScale);
                    menu.setAlpha(1f);
                }

                if (miniPlayerView != null && miniPlayerView.isAttachedToWindow()) {
                    updateMiniRightMargin(0);
                    float stackedDrop = outerRect.height() > 0f ? (outerRect.height() - dpf(14f)) : dpf(46f);
                    float baseTy = p * stackedDrop;

                    // 2-phase card-shuffle arc:
                    // Phase 1 (0..0.5): lift UP by -46dp
                    // Phase 2 (0.5..1.0): dive DOWN behind TabBar to slightly tucked behind (+6dp above bar bottom)
                    float arcLift = (float) Math.sin(sh * Math.PI) * -dpf(48f);
                    float tuckedOffset = (sh > 0.5f) ? ((sh - 0.5f) * 2f) * (-dpf(8f)) : 0f;
                    float miniScale = (sh <= 0.5f)
                            ? (1f - 0.04f * (sh * 2f))
                            : (0.96f - 0.04f * ((sh - 0.5f) * 2f));

                    miniPlayerView.setTranslationY(baseTy + arcLift + tuckedOffset);
                    miniPlayerView.setScaleX(miniScale);
                    miniPlayerView.setScaleY(miniScale);
                    miniPlayerView.setAlpha(sh > 0.5f ? (1f - 0.18f * ((sh - 0.5f) * 2f)) : 1f);

                    if (p > 0.05f && miniOnTop) {
                        miniPlayerView.setZ(dpf(32f));
                        bar.setZ(dpf(18f));
                    } else {
                        bar.setZ(dpf(32f));
                        miniPlayerView.setZ(dpf(14f));
                    }
                } else {
                    bar.setZ(dpf(24f));
                }
            }
        }

        private void updateMiniRightMargin(int extraRightPx) {
            if (miniPlayerView == null) return;
            if (miniPlayerView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) miniPlayerView.getLayoutParams();
                int baseRight = (miniOrigRightMargin != Integer.MIN_VALUE) ? miniOrigRightMargin : lp.rightMargin;
                int targetRight = baseRight + extraRightPx;
                if (Math.abs(lp.rightMargin - targetRight) >= 2) {
                    lp.rightMargin = targetRight;
                    miniPlayerView.setLayoutParams(lp);
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

            float[] s5 = stepSpring(searchBtnScale, searchBtnScaleVel, searchBtnScaleTarget, 420f, 0.7f, dt);
            searchBtnScale = s5[0];
            searchBtnScaleVel = s5[1];
            if (Math.abs(searchBtnScale - searchBtnScaleTarget) > 0.002f || Math.abs(searchBtnScaleVel) > 0.005f) moving = true;

            if (shuffleAnimating) {
                shuffleProgress += shuffleDir * (dt / 0.44f); // 440ms smooth 2-phase card shuffle
                if (shuffleDir > 0f && shuffleProgress >= 1f) {
                    shuffleProgress = 1f;
                    shuffleAnimating = false;
                    stackedMiniBehind = true;
                } else if (shuffleDir < 0f && shuffleProgress <= 0f) {
                    shuffleProgress = 0f;
                    shuffleAnimating = false;
                    stackedMiniBehind = false;
                } else {
                    moving = true;
                }
            }

            applyCollapseTransforms();
            bar.invalidate();
            if (miniPlayerView != null) miniPlayerView.invalidate();
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
            if (Build.VERSION.SDK_INT < 29) return true;
            if (bar.getWidth() > 0 && bar.getHeight() > 0) {
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
                    if (miniGlassNode == null) {
                        miniGlassNode = new RenderNode("sc_mini_glass");
                    }
                    miniGlassNode.setPosition(0, 0, w, h);

                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(1.5f);
                    RenderEffect vibrancyEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                    float blurPx = dpf(blurDp);
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

                surfacePaint.setColor(dark ? Color.argb(102, 24, 24, 28) : Color.argb(115, 245, 247, 252));
                canvas.drawRoundRect(miniRect, radius, radius, surfacePaint);
                canvas.restore();

                drawBloomRim(canvas, miniRect, radius, 0.75f, -45f);
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter colorFilter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        }

        private final class GlassBackgroundDrawable extends Drawable {
            private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF animOuterRect = new RectF();

            @Override
            public void draw(Canvas canvas) {
                if (capturing) return;
                updateGeometry();
                if (outerRect.width() <= 0f || outerRect.height() <= 0f) return;

                boolean dark = isDarkTheme();
                float p = stackedMode ? 0f : Math.max(0f, Math.min(1f, collapseProgress));

                // 1. Left TabBar Liquid Glass Pill
                if (p < 0.98f) {
                    float left = outerRect.left + (searchRect.left - outerRect.left) * p;
                    float right = outerRect.right + (searchRect.right - outerRect.right) * p;
                    float top = outerRect.top + (searchRect.top - outerRect.top) * p;
                    float bottom = outerRect.bottom + (searchRect.bottom - outerRect.bottom) * p;
                    animOuterRect.set(left, top, right, bottom);
                    float radius = animOuterRect.height() * 0.5f;
                    float alphaMult = Math.max(0f, 1f - p * 1.15f);

                    float outerScale = 1f + (dpf(16f) / Math.max(1f, animOuterRect.width())) * pressProgress;
                    canvas.save();
                    if (pressProgress > 0.001f) {
                        canvas.scale(outerScale, outerScale, animOuterRect.centerX(), animOuterRect.centerY());
                    }

                    shadowPaint.setColor(Color.TRANSPARENT);
                    shadowPaint.setShadowLayer(dpf(10f), 0f, dpf(3f),
                            Color.argb(Math.round((dark ? 51 : 26) * alphaMult), 0, 0, 0));
                    canvas.drawRoundRect(animOuterRect, radius, radius, shadowPaint);

                    canvas.save();
                    clipPath.reset();
                    clipPath.addRoundRect(animOuterRect, radius, radius, Path.Direction.CW);
                    canvas.clipPath(clipPath);

                    if (Build.VERSION.SDK_INT >= 33 && backdropNode != null
                            && backdropNode.hasDisplayList() && canvas.isHardwareAccelerated()) {
                        if (outerGlassNode == null) {
                            outerGlassNode = new RenderNode("sc_outer_glass");
                        }
                        int w = bar.getWidth();
                        int h = bar.getHeight();
                        outerGlassNode.setPosition(0, 0, w, h);
                        outerGlassNode.setAlpha(alphaMult);

                        ColorMatrix cm = new ColorMatrix();
                        cm.setSaturation(1.5f);
                        RenderEffect vibrancyEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                        float blurPx = dpf(blurDp);
                        RenderEffect blurEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);

                        outerLensShader.setFloatUniform("size", animOuterRect.width(), animOuterRect.height());
                        outerLensShader.setFloatUniform("offset", -animOuterRect.left, -animOuterRect.top);
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

                    surfacePaint.setColor(dark
                            ? Color.argb(Math.round(102 * alphaMult), 24, 24, 28)
                            : Color.argb(Math.round(115 * alphaMult), 245, 247, 252));
                    canvas.drawRoundRect(animOuterRect, radius, radius, surfacePaint);
                    canvas.restore();

                    drawBloomRim(canvas, animOuterRect, radius, 0.75f * alphaMult, -45f);
                    canvas.restore();
                }

                // 2. Right Separated Liquid Glass Circle Button (Search <-> Menu)
                if (searchRect.width() > 0f) {
                    float cr = searchRect.height() * 0.5f;
                    canvas.save();
                    if (Math.abs(searchBtnScale - 1f) > 0.002f) {
                        canvas.scale(searchBtnScale, searchBtnScale, searchRect.centerX(), searchRect.centerY());
                    }

                    shadowPaint.setColor(Color.TRANSPARENT);
                    shadowPaint.setShadowLayer(dpf(10f), 0f, dpf(3f),
                            Color.argb(dark ? 56 : 28, 0, 0, 0));
                    canvas.drawRoundRect(searchRect, cr, cr, shadowPaint);

                    canvas.save();
                    clipPath.reset();
                    clipPath.addRoundRect(searchRect, cr, cr, Path.Direction.CW);
                    canvas.clipPath(clipPath);

                    if (Build.VERSION.SDK_INT >= 33 && backdropNode != null
                            && backdropNode.hasDisplayList() && searchLensShader != null
                            && canvas.isHardwareAccelerated()) {
                        if (searchGlassNode == null) {
                            searchGlassNode = new RenderNode("sc_search_glass");
                        }
                        int w = bar.getWidth();
                        int h = bar.getHeight();
                        searchGlassNode.setPosition(0, 0, w, h);

                        ColorMatrix cm = new ColorMatrix();
                        cm.setSaturation(1.5f);
                        RenderEffect vibrancyEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));
                        float blurPx = dpf(blurDp);
                        RenderEffect blurEffect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP);

                        searchLensShader.setFloatUniform("size", searchRect.width(), searchRect.height());
                        searchLensShader.setFloatUniform("offset", -searchRect.left, -searchRect.top);
                        searchLensShader.setFloatUniform("cornerRadii", new float[]{cr, cr, cr, cr});
                        searchLensShader.setFloatUniform("refractionHeight", dpf(18f));
                        searchLensShader.setFloatUniform("refractionAmount", -dpf(18f));
                        searchLensShader.setFloatUniform("depthEffect", 0f);
                        RenderEffect lensEffect = RenderEffect.createRuntimeShaderEffect(searchLensShader, "content");

                        RenderEffect chain = RenderEffect.createChainEffect(lensEffect,
                                RenderEffect.createChainEffect(blurEffect, vibrancyEffect));
                        searchGlassNode.setRenderEffect(chain);

                        Canvas sc = searchGlassNode.beginRecording(w, h);
                        try {
                            sc.drawRenderNode(backdropNode);
                        } finally {
                            searchGlassNode.endRecording();
                        }
                        canvas.drawRenderNode(searchGlassNode);
                    }

                    surfacePaint.setColor(dark ? Color.argb(108, 24, 24, 28) : Color.argb(120, 245, 247, 252));
                    canvas.drawRoundRect(searchRect, cr, cr, surfacePaint);
                    canvas.restore();

                    drawBloomRim(canvas, searchRect, cr, 0.8f, -45f);
                    canvas.restore();
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
            private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final float[] tmpCX = new float[1];
            private final float[] tmpCY = new float[1];
            private final float[] tmpW = new float[1];

            @Override
            public void draw(Canvas canvas) {
                if (capturing) return;
                updateGeometry();
                boolean dark = isDarkTheme();
                float p = stackedMode ? 0f : Math.max(0f, Math.min(1f, collapseProgress));

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
                        highlightShader.setColorUniform("color", Color.argb((int) (255 * 0.12f * pressProgress * pillAlphaMult), 255, 255, 255));
                        highlightShader.setFloatUniform("radius", outerRect.height() * 1.2f);
                        highlightShader.setFloatUniform("position", centerX - outerRect.left, centerY - outerRect.top);
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

                    int restAlpha = Math.round(25.5f * (1f - pressProgress) * pillAlphaMult);
                    pillSurfacePaint.setColor(dark
                            ? Color.argb(restAlpha, 255, 255, 255)
                            : Color.argb(restAlpha, 0, 0, 0));
                    canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillSurfacePaint);

                    if (pressProgress > 0.01f) {
                        pillSurfacePaint.setColor(Color.argb(Math.round(8f * pressProgress * pillAlphaMult), 0, 0, 0));
                        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillSurfacePaint);

                        innerShadowPaint.setStyle(Paint.Style.STROKE);
                        innerShadowPaint.setStrokeWidth(dpf(8f) * pressProgress);
                        innerShadowPaint.setColor(Color.argb(Math.round(38f * pressProgress * pillAlphaMult), 0, 0, 0));
                        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, innerShadowPaint);
                    }
                    canvas.restore();

                    if (pressProgress > 0.01f) {
                        drawBloomRim(canvas, pillRect, pillRadius, pressProgress * pillAlphaMult, 90f);
                    }
                }

                // 2. Morphing Search (🔍) <-> Menu (☰) icon inside the right separated Liquid Glass circle
                if (searchRect.width() > 0f) {
                    float morphP = stackedMode ? 0f : Math.max(0f, Math.min(1f, collapseProgress));
                    float cx = searchRect.centerX();
                    float cy = searchRect.centerY();
                    int baseColor = searchTabSelected && morphP < 0.5f
                            ? Color.rgb(255, 85, 0)
                            : (dark ? Color.WHITE : Color.rgb(28, 28, 30));

                    canvas.save();
                    if (Math.abs(searchBtnScale - 1f) > 0.002f) {
                        canvas.scale(searchBtnScale, searchBtnScale, cx, cy);
                    }

                    iconPaint.setStyle(Paint.Style.STROKE);
                    iconPaint.setStrokeCap(Paint.Cap.ROUND);
                    iconPaint.setStrokeWidth(dpf(2.1f));

                    float searchAlpha = Math.max(0f, 1f - morphP * 1.6f);
                    if (searchAlpha > 0.02f) {
                        canvas.save();
                        float s = 1f - 0.25f * morphP;
                        canvas.scale(s, s, cx, cy);
                        canvas.rotate(45f * morphP, cx, cy);
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

                    float menuAlpha = Math.max(0f, (morphP - 0.35f) / 0.65f);
                    if (menuAlpha > 0.02f) {
                        canvas.save();
                        float s = 0.75f + 0.25f * menuAlpha;
                        canvas.scale(s, s, cx, cy);
                        iconPaint.setColor(dark ? Color.WHITE : Color.rgb(28, 28, 30));
                        iconPaint.setAlpha(Math.round(255 * menuAlpha));
                        float halfLine = dpf(8.5f);
                        float gap = dpf(5.5f);
                        canvas.drawLine(cx - halfLine, cy - gap, cx + halfLine, cy - gap, iconPaint);
                        canvas.drawLine(cx - halfLine, cy, cx + halfLine, cy, iconPaint);
                        canvas.drawLine(cx - halfLine, cy + gap, cx + halfLine, cy + gap, iconPaint);
                        canvas.restore();
                    }

                    canvas.restore();
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
