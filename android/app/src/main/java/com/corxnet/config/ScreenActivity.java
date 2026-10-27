package com.corxnet.config;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** 页面共用的基础排版与系统栏留白，不持有设备或配置状态。 */
abstract class ScreenActivity extends Activity {
  protected final List<View> controls = new ArrayList<>();

  protected int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  protected LinearLayout vertical() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  protected TextView text(String value, int size, int color) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    return v;
  }

  protected GradientDrawable background(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  protected void gap(LinearLayout parent, int height) {
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  protected LinearLayout root() {
    LinearLayout root = vertical();
    root.setBackgroundColor(Color.rgb(244, 246, 250));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else {
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          }
          return insets;
        });
    return root;
  }

  protected LinearLayout card(LinearLayout parent, String eyebrow, String title) {
    LinearLayout card = vertical();
    card.setPadding(dp(20), dp(20), dp(20), dp(20));
    card.setBackground(background(Color.WHITE, 16));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(16);
    parent.addView(card, lp);
    TextView small = text(eyebrow, 11, Color.rgb(22, 119, 255));
    small.setLetterSpacing(0.12f);
    card.addView(small);
    gap(card, 6);
    TextView heading = text(title, 21, Color.rgb(20, 31, 53));
    heading.setTypeface(null, Typeface.BOLD);
    card.addView(heading);
    gap(card, 12);
    return card;
  }

  protected Button button(LinearLayout parent, String label, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(15);
    tint(b, primary);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
    lp.topMargin = dp(8);
    parent.addView(b, lp);
    b.setOnClickListener(v -> action.run());
    controls.add(b);
    return b;
  }

  protected void tint(Button button, boolean selected) {
    button.setTextColor(selected ? Color.WHITE : Color.rgb(22, 119, 255));
    button.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(
            selected ? Color.rgb(22, 119, 255) : Color.rgb(234, 242, 255)));
  }

  protected Button rowButton(LinearLayout parent, String label, Runnable action) {
    Button b = button(parent, label, false, action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1);
    lp.setMargins(dp(3), dp(4), dp(3), dp(4));
    b.setLayoutParams(lp);
    return b;
  }
}
