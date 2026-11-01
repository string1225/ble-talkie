package cn.sunnystring.bletalkie.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native view primitives shared by the two activities. */
public final class UiKit {
    public static final int BACKGROUND = Color.rgb(244, 246, 241);
    public static final int INK = Color.rgb(27, 47, 39);
    public static final int MUTED = Color.rgb(103, 119, 108);
    public static final int GREEN = Color.rgb(29, 106, 77);
    public static final int PALE = Color.rgb(228, 237, 225);
    public static final int LINE = Color.rgb(220, 227, 216);
    public static final int RED = Color.rgb(160, 61, 48);

    private UiKit() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    public static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setFontFeatureSettings("kern");
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setLineSpacing(dp(context, 3), 1f);
        return text;
    }

    public static Button button(Context context, String value, boolean primary) {
        Button button = new Button(context);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? Color.WHITE : GREEN);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setStateListAnimator(null);
        GradientDrawable fill = shape(context, primary ? GREEN : PALE, 14);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x224D765B), fill, null));
        return button;
    }

    public static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    public static LinearLayout card(Context context) {
        LinearLayout view = column(context);
        view.setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20));
        view.setBackground(shape(context, Color.WHITE, 22));
        return view;
    }

    public static LinearLayout.LayoutParams block(Context context, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, top);
        return params;
    }

    public static void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
    }
}
