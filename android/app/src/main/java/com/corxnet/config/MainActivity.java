package com.corxnet.config;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.corxnet.config.core.DeviceRepository;
import com.corxnet.config.core.Protocol;
import com.corxnet.config.core.QrConfig;

/** 启动页只负责选设备，配置表单位于独立的 DeviceConfigActivity。 */
public final class MainActivity extends ScreenActivity {
  private static final int DEVICE_SCAN = 100;
  private DeviceSelectionController controller;
  private LinearLayout devices;
  private TextView status;
  private ProgressBar progress;
  private boolean navigating;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    Object retained = getLastNonConfigurationInstance();
    controller =
        retained instanceof DeviceSelectionController
            ? (DeviceSelectionController) retained
            : new DeviceSelectionController(((CorxnetApplication) getApplication()).repository());
    LinearLayout root = root();
    LinearLayout header = vertical();
    header.setPadding(dp(16), dp(24), dp(16), dp(12));
    root.addView(header);
    TextView brand = text("CORXNET  /  SELECT DEVICE", 11, Color.rgb(96, 112, 135));
    brand.setLetterSpacing(0.12f);
    header.addView(brand);
    gap(header, 8);
    TextView title = text("选择设备", 28, Color.rgb(20, 31, 53));
    title.setTypeface(null, Typeface.BOLD);
    header.addView(title);
    gap(header, 8);
    header.addView(text("连接设备所在 Wi-Fi，然后扫码或扫描局域网。", 14, Color.rgb(96, 112, 135)));
    button(
        header,
        "扫描设备二维码",
        true,
        () -> {
          Intent intent = new Intent(this, ScannerActivity.class);
          intent.putExtra("title", "扫描设备二维码");
          startActivityForResult(intent, DEVICE_SCAN);
        });
    button(header, "扫描局域网设备", false, controller::discover);
    button(header, "输入设备 MAC / 设备码", false, this::inputDevice);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    header.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
    status = text("", 14, Color.rgb(72, 87, 109));
    gap(header, 8);
    header.addView(status);
    ScrollView list = new ScrollView(this);
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    devices = vertical();
    devices.setPadding(dp(16), dp(4), dp(16), dp(20));
    list.addView(devices);
    setContentView(root);
    controller.listener = this::render;
    render();
  }

  @Override
  protected void onResume() {
    super.onResume();
    navigating = false;
    if (controller != null) render();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return controller;
  }

  @Override
  protected void onDestroy() {
    controller.listener = null;
    if (!isChangingConfigurations()) controller.close();
    super.onDestroy();
  }

  private void render() {
    status.setText(controller.status);
    status.setTextColor(controller.error ? Color.rgb(180, 35, 35) : Color.rgb(72, 87, 109));
    progress.setVisibility(controller.busy ? View.VISIBLE : View.GONE);
    for (View v : controls) v.setEnabled(!controller.busy && !navigating);
    devices.removeAllViews();
    if (controller.devices.isEmpty()) {
      devices.addView(
          text(controller.busy ? "等待设备响应…" : "局域网设备将显示在这里", 14, Color.rgb(96, 112, 135)));
    }
    for (DeviceRepository.DiscoveredDevice device : controller.devices) {
      LinearLayout row = vertical();
      row.setPadding(dp(20), dp(18), dp(20), dp(18));
      row.setBackground(background(Color.WHITE, 14));
      TextView mac = text(Protocol.displayMac(device.mac), 18, Color.rgb(20, 31, 53));
      mac.setTypeface(Typeface.MONOSPACE);
      row.addView(mac);
      row.addView(text(device.address + " · 点击进入配置", 13, Color.rgb(96, 112, 135)));
      row.setContentDescription("配置设备 " + Protocol.displayMac(device.mac));
      row.setFocusable(true);
      row.setClickable(true);
      row.setEnabled(!controller.busy && !navigating);
      row.setOnClickListener(v -> selectDevice(device.mac));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = dp(10);
      devices.addView(row, lp);
    }
  }

  private void selectDevice(String value) {
    if (controller.busy || navigating) return;
    try {
      String mac = Protocol.mac(value);
      navigating = true;
      render();
      startActivity(new Intent(this, DeviceConfigActivity.class).putExtra("mac", mac));
    } catch (IllegalArgumentException e) {
      controller.message(e.getMessage(), true);
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == DEVICE_SCAN && result == RESULT_OK && data != null) {
      try {
        selectDevice(QrConfig.device(data.getStringExtra("json")));
      } catch (IllegalArgumentException e) {
        controller.message(e.getMessage(), true);
      }
    }
  }

  private void inputDevice() {
    EditText input = new EditText(this);
    input.setHint("0090E2D72060 或 {\"mac\":\"0090E2D72060\"}");
    input.setSaveEnabled(false);
    input.setSingleLine(false);
    input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    LinearLayout box = vertical();
    box.setPadding(dp(20), 0, dp(20), 0);
    box.addView(input);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("选择设备")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "进入配置",
                (d, w) -> {
                  String value = input.getText().toString().trim();
                  try {
                    selectDevice(value.startsWith("{") ? QrConfig.device(value) : value);
                  } catch (IllegalArgumentException e) {
                    controller.message(e.getMessage(), true);
                  }
                })
            .create();
    dialog.show();
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }
}
