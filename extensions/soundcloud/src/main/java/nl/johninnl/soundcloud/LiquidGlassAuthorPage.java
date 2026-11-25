package nl.johninnl.soundcloud;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen SoundCloud-style "Tác giả" (Author & Liquid Glass Customization) page.
 * Mounts inside android.R.id.content directly beneath the floating Liquid Glass TabBar,
 * so switching to "Tác giả" feels like a native SoundCloud tab rather than a popup.
 */
@SuppressLint("NewApi")
public final class LiquidGlassAuthorPage {

    public static final String PREFS_NAME = "sc_liquid_glass_prefs";
    public static final String KEY_GLOBAL_GLASS = "global_liquid_glass";
    public static final String KEY_SCROLL_COLLAPSE = "scroll_collapse_enabled";
    public static final String KEY_TRANSPARENCY = "glass_transparency_pct";   // 0..100 (default 60)
    public static final String KEY_REFRACTION_DP = "glass_refraction_dp";     // 0..36 (default 24)
    public static final String KEY_BLUR_DP = "glass_blur_dp";                 // 0..20 (default 4)
    public static final String KEY_DISPERSION = "glass_dispersion";           // 0.0..1.2 (default 0.5)

    private static final String BANK_STK = "0989249990";

    public interface SettingsCallback {
        void onSettingsChanged();
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isGlobalGlassEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_GLOBAL_GLASS, false);
    }

    public static boolean isScrollCollapseEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SCROLL_COLLAPSE, true);
    }

    public static float getTransparencyPct(Context ctx) {
        return prefs(ctx).getFloat(KEY_TRANSPARENCY, 60f);
    }

    public static float getRefractionDp(Context ctx) {
        return prefs(ctx).getFloat(KEY_REFRACTION_DP, 24f);
    }

    public static float getBlurDp(Context ctx) {
        return prefs(ctx).getFloat(KEY_BLUR_DP, 4f);
    }

    public static float getDispersion(Context ctx) {
        return prefs(ctx).getFloat(KEY_DISPERSION, 0.5f);
    }

    public static View createPage(Context ctx, boolean showBackButton, Runnable onClose, SettingsCallback callback) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences sp = prefs(ctx);

        FrameLayout pageRoot = new FrameLayout(ctx);
        pageRoot.setBackgroundColor(Color.BLACK); // True AMOLED black matching SoundCloud
        pageRoot.setClickable(true);
        pageRoot.setFocusable(true);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(16f * d);
        int topInset = getStatusBarHeight(ctx) + Math.round(14f * d);
        content.setPadding(padH, topInset, padH, Math.round(145f * d)); // Clear floating Mini-Player + TabBar

        // === Top SoundCloud-style Header ===
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Math.round(18f * d);
        header.setLayoutParams(hlp);

        if (showBackButton && onClose != null) {
            TextView backBtn = new TextView(ctx);
            backBtn.setText("‹");
            backBtn.setTextColor(Color.WHITE);
            backBtn.setTextSize(26f);
            backBtn.setGravity(Gravity.CENTER);
            int bs = Math.round(40f * d);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(bs, bs);
            blp.rightMargin = Math.round(12f * d);
            backBtn.setLayoutParams(blp);
            GradientDrawable bbg = new GradientDrawable();
            bbg.setColor(Color.argb(70, 255, 255, 255));
            bbg.setCornerRadius(bs * 0.5f);
            bbg.setStroke(Math.round(1f * d), Color.argb(120, 255, 255, 255));
            backBtn.setBackground(bbg);
            backBtn.setOnClickListener(v -> onClose.run());
            header.addView(backBtn);
        }

        TextView pageTitle = new TextView(ctx);
        pageTitle.setText("Tác giả");
        pageTitle.setTextColor(Color.WHITE);
        pageTitle.setTextSize(26f);
        pageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pageTitle.setLayoutParams(ttlp);
        header.addView(pageTitle);

        TextView badge = new TextView(ctx);
        badge.setText("DIGITALXIAO");
        badge.setTextColor(Color.rgb(255, 110, 40));
        badge.setTextSize(11f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(Math.round(10f * d), Math.round(5f * d), Math.round(10f * d), Math.round(5f * d));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.argb(45, 255, 85, 0));
        badgeBg.setCornerRadius(12f * d);
        badgeBg.setStroke(Math.round(1f * d), Color.argb(110, 255, 95, 30));
        badge.setBackground(badgeBg);
        header.addView(badge);

        content.addView(header);

        // === Card 1: Developer Info ===
        LinearLayout devCard = makeGlassCard(ctx, d);
        TextView modTitle = new TextView(ctx);
        modTitle.setText("SoundCloud MOD by DigitalXiao");
        modTitle.setTextColor(Color.WHITE);
        modTitle.setTextSize(18f);
        modTitle.setTypeface(Typeface.DEFAULT_BOLD);
        devCard.addView(modTitle);

        TextView modDesc = new TextView(ctx);
        modDesc.setText("Phiên bản SoundCloud tùy biến giao diện iOS 27 Liquid Glass, khúc xạ thời gian thực & tán sắc 7 dải màu bởi DigitalXiao.");
        modDesc.setTextColor(Color.argb(200, 225, 225, 235));
        modDesc.setTextSize(13.5f);
        modDesc.setPadding(0, Math.round(6f * d), 0, Math.round(14f * d));
        devCard.addView(modDesc);

        addPillButton(ctx, devCard, d, "TikTok: @digitalxiaoiu5", Color.argb(120, 42, 42, 52),
                v -> openUrl(ctx, "https://www.tiktok.com/@digitalxiaoiu5"));
        addPillButton(ctx, devCard, d, "Box Telegram: t.me/DigitalXiao", Color.argb(190, 26, 95, 150),
                v -> openUrl(ctx, "https://t.me/DigitalXiao"));

        content.addView(devCard);

        // === Card 2: Ủng hộ nhà phát triển (VietinBank QR 0989249990) ===
        LinearLayout donateCard = makeGlassCard(ctx, d);
        TextView donateTitle = new TextView(ctx);
        donateTitle.setText("Ủng hộ nhà phát triển");
        donateTitle.setTextColor(Color.WHITE);
        donateTitle.setTextSize(16.5f);
        donateTitle.setTypeface(Typeface.DEFAULT_BOLD);
        donateCard.addView(donateTitle);

        TextView donateSub = new TextView(ctx);
        donateSub.setText("Bấm để mở mã QR VietinBank (STK: 0989249990)");
        donateSub.setTextColor(Color.argb(180, 215, 215, 225));
        donateSub.setTextSize(13f);
        donateSub.setPadding(0, Math.round(4f * d), 0, Math.round(12f * d));
        donateCard.addView(donateSub);

        LinearLayout qrBox = new LinearLayout(ctx);
        qrBox.setOrientation(LinearLayout.VERTICAL);
        qrBox.setGravity(Gravity.CENTER_HORIZONTAL);
        qrBox.setVisibility(View.GONE);
        qrBox.setPadding(0, Math.round(6f * d), 0, Math.round(4f * d));

        LiquidGlassControls.VietQrView qrView = new LiquidGlassControls.VietQrView(ctx);
        LinearLayout.LayoutParams qrlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qrlp.bottomMargin = Math.round(12f * d);
        qrBox.addView(qrView, qrlp);

        TextView bankInfo = new TextView(ctx);
        bankInfo.setText("Ngân hàng: VietinBank\nSTK: " + BANK_STK);
        bankInfo.setTextColor(Color.WHITE);
        bankInfo.setTextSize(14f);
        bankInfo.setTypeface(Typeface.DEFAULT_BOLD);
        bankInfo.setGravity(Gravity.CENTER);
        bankInfo.setPadding(0, 0, 0, Math.round(10f * d));
        qrBox.addView(bankInfo);

        addPillButton(ctx, qrBox, d, "Sao chép STK: " + BANK_STK, Color.argb(210, 0, 118, 190), v -> {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("VietinBank STK", BANK_STK));
                    Toast.makeText(ctx, "Đã sao chép STK VietinBank: " + BANK_STK, Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
            }
        });

        final TextView[] toggleQrBtnRef = new TextView[1];
        toggleQrBtnRef[0] = addPillButton(ctx, donateCard, d, "♥  Ủng hộ nhà phát triển (Hiện mã QR)", Color.rgb(255, 85, 0), v -> {
            boolean show = qrBox.getVisibility() != View.VISIBLE;
            qrBox.setVisibility(show ? View.VISIBLE : View.GONE);
            toggleQrBtnRef[0].setText(show ? "Ẩn mã QR VietinBank" : "♥  Ủng hộ nhà phát triển (Hiện mã QR)");
        });
        donateCard.addView(qrBox);
        content.addView(donateCard);

        // === Card 3: Bật LiquidGlass toàn giao diện SoundCloud ===
        LinearLayout globalCard = makeGlassCard(ctx, d);
        addSwitchRow(
                ctx, globalCard, d,
                "Bật LiquidGlass",
                "Biến toàn bộ nút bấm, thanh tìm kiếm, nút quay lại và công tắc trong SoundCloud thành hiệu ứng kính Liquid Glass kiểu iOS 27.",
                sp.getBoolean(KEY_GLOBAL_GLASS, false),
                checked -> {
                    sp.edit().putBoolean(KEY_GLOBAL_GLASS, checked).apply();
                    if (callback != null) callback.onSettingsChanged();
                }
        );
        content.addView(globalCard);

        // === Card 4: Tùy chỉnh LiquidGlass TabBar (iOS 27 Sliders + Switch) ===
        LinearLayout customCard = makeGlassCard(ctx, d);
        TextView customTitle = new TextView(ctx);
        customTitle.setText("Tùy chỉnh LiquidGlass TabBar");
        customTitle.setTextColor(Color.WHITE);
        customTitle.setTextSize(16.5f);
        customTitle.setTypeface(Typeface.DEFAULT_BOLD);
        customTitle.setPadding(0, 0, 0, Math.round(10f * d));
        customCard.addView(customTitle);

        final LiquidGlassControls.Ios27GlassSlider[] sliders = new LiquidGlassControls.Ios27GlassSlider[4];
        final TextView[] valLabels = new TextView[4];

        // 1. Transparency (0%..100%)
        sliders[0] = addSliderRow(ctx, customCard, d, "Độ trong suốt", "%",
                0f, 100f, getTransparencyPct(ctx), true, valLabels, 0, val -> {
                    sp.edit().putFloat(KEY_TRANSPARENCY, val).apply();
                    if (callback != null) callback.onSettingsChanged();
                });

        // 2. Refraction (0..36 dp)
        sliders[1] = addSliderRow(ctx, customCard, d, "Độ khúc xạ", "dp",
                0f, 36f, getRefractionDp(ctx), true, valLabels, 1, val -> {
                    sp.edit().putFloat(KEY_REFRACTION_DP, val).apply();
                    if (callback != null) callback.onSettingsChanged();
                });

        // 3. Blur (0..20 dp)
        sliders[2] = addSliderRow(ctx, customCard, d, "Độ mờ kính (Blur)", "dp",
                0f, 20f, getBlurDp(ctx), true, valLabels, 2, val -> {
                    sp.edit().putFloat(KEY_BLUR_DP, val).apply();
                    if (callback != null) callback.onSettingsChanged();
                });

        // 4. Chromatic Dispersion (0.0..1.2)
        sliders[3] = addSliderRow(ctx, customCard, d, "Tán sắc RGB", "",
                0f, 1.2f, getDispersion(ctx), false, valLabels, 3, val -> {
                    sp.edit().putFloat(KEY_DISPERSION, val).apply();
                    if (callback != null) callback.onSettingsChanged();
                });

        // Auto-collapse on scroll switch
        final LiquidGlassControls.Ios27GlassSwitch collapseSwitch = addSwitchRow(
                ctx, customCard, d,
                "Thu gọn TabBar khi cuộn xuống",
                "TabBar thu gọn vào nút Tìm kiếm (biến thành nút Menu) và thanh nhạc hạ xuống thay vị trí TabBar.",
                isScrollCollapseEnabled(ctx),
                checked -> {
                    sp.edit().putBoolean(KEY_SCROLL_COLLAPSE, checked).apply();
                    if (callback != null) callback.onSettingsChanged();
                }
        );

        // Reset defaults button
        addPillButton(ctx, customCard, d, "Khôi phục mặc định", Color.argb(50, 255, 255, 255), v -> {
            sp.edit()
                    .putFloat(KEY_TRANSPARENCY, 60f)
                    .putFloat(KEY_REFRACTION_DP, 24f)
                    .putFloat(KEY_BLUR_DP, 4f)
                    .putFloat(KEY_DISPERSION, 0.5f)
                    .putBoolean(KEY_SCROLL_COLLAPSE, true)
                    .apply();
            sliders[0].setValue(60f); valLabels[0].setText("60%");
            sliders[1].setValue(24f); valLabels[1].setText("24dp");
            sliders[2].setValue(4f); valLabels[2].setText("4dp");
            sliders[3].setValue(0.5f); valLabels[3].setText("0.50");
            collapseSwitch.setChecked(true, false);
            if (callback != null) callback.onSettingsChanged();
        });

        content.addView(customCard);

        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageRoot.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return pageRoot;
    }

    private static int getStatusBarHeight(Context ctx) {
        int resId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) return ctx.getResources().getDimensionPixelSize(resId);
        return Math.round(28f * ctx.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout makeGlassCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16f * d);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(215, 22, 22, 28));
        bg.setCornerRadius(22f * d);
        bg.setStroke(Math.round(1f * d), Color.argb(75, 255, 255, 255));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(14f * d);
        card.setLayoutParams(lp);
        return card;
    }

    private static TextView addPillButton(Context ctx, LinearLayout parent, float d, String text, int color, View.OnClickListener onClick) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(Math.round(14f * d), Math.round(12f * d), Math.round(14f * d), Math.round(12f * d));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(18f * d);
        bg.setStroke(Math.round(0.9f * d), Color.argb(90, 255, 255, 255));
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(8f * d);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(onClick);
        parent.addView(btn);
        return btn;
    }

    private static LiquidGlassControls.Ios27GlassSwitch addSwitchRow(
            Context ctx, LinearLayout parent, float d, String title, String subtitle,
            boolean initial, LiquidGlassControls.OnCheckedChangeListener onChange) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(6f * d), 0, Math.round(10f * d));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = Math.round(12f * d);
        textCol.setLayoutParams(tlp);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(ctx);
        tvSub.setText(subtitle);
        tvSub.setTextColor(Color.argb(175, 215, 215, 228));
        tvSub.setTextSize(12.5f);
        tvSub.setPadding(0, Math.round(3f * d), 0, 0);
        textCol.addView(tvSub);

        LiquidGlassControls.Ios27GlassSwitch sw = new LiquidGlassControls.Ios27GlassSwitch(ctx, initial, onChange);
        row.addView(textCol);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private static LiquidGlassControls.Ios27GlassSlider addSliderRow(
            Context ctx, LinearLayout parent, float d, String label, String unit,
            float min, float max, float initial, boolean integerDisplay,
            TextView[] outLabels, int idx, LiquidGlassControls.OnSliderChangeListener onChange) {
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, Math.round(4f * d), 0, Math.round(2f * d));

        TextView tvName = new TextView(ctx);
        tvName.setText(label);
        tvName.setTextColor(Color.argb(230, 240, 240, 248));
        tvName.setTextSize(13.5f);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tvName);

        TextView tvVal = new TextView(ctx);
        tvVal.setText(formatVal(initial, unit, integerDisplay));
        tvVal.setTextColor(Color.rgb(255, 130, 60));
        tvVal.setTextSize(13f);
        tvVal.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(tvVal);
        outLabels[idx] = tvVal;
        parent.addView(head);

        LiquidGlassControls.Ios27GlassSlider slider = new LiquidGlassControls.Ios27GlassSlider(ctx, min, max, initial, v -> {
            tvVal.setText(formatVal(v, unit, integerDisplay));
            if (onChange != null) onChange.onValueChanged(v);
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Math.round(8f * d);
        parent.addView(slider, slp);
        return slider;
    }

    private static String formatVal(float v, String unit, boolean integerDisplay) {
        if (integerDisplay) {
            return Math.round(v) + unit;
        }
        return String.format(java.util.Locale.US, "%.2f%s", v, unit);
    }

    private static void openUrl(Context ctx, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private LiquidGlassAuthorPage() {}
}
