package com.corxnet.config;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.corxnet.config.core.Protocol;
import com.corxnet.config.core.QrConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeviceConfigActivity extends ScreenActivity {
  private static final int CONFIG_SCAN = 101;
  private ConfigController controller;
  private TextView device, status, networkStatus, mqttStatus;
  private ProgressBar progress;
  private Button quick, refresh, networkSave, mqttSave;
  private Button networkTab, mqttTab;
  private ScrollView networkPanel, mqttPanel;
  private final Map<String, EditText> networkInputs = new LinkedHashMap<>();
  private final Map<String, EditText> mqttInputs = new LinkedHashMap<>();
  private long rendered = -1;
  private boolean populating;
  private AlertDialog previewDialog;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    Object retained = getLastNonConfigurationInstance();
    controller =
        retained instanceof ConfigController
            ? (ConfigController) retained
            : new ConfigController(this);
    if (controller.mac.isEmpty()) {
      try {
        controller.mac = Protocol.mac(getIntent().getStringExtra("mac"));
      } catch (IllegalArgumentException e) {
        finish();
        return;
      }
      if (state != null) controller.selectedTab = state.getInt("tab", 0);
    }
    buildUi();
    controller.listener = this::render;
    render();
    if (!(retained instanceof ConfigController)) controller.refresh();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return controller;
  }

  @Override
  protected void onDestroy() {
    controller.listener = null;
    if (previewDialog != null) previewDialog.dismiss();
    if (!isChangingConfigurations()) controller.close();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putInt("tab", controller.selectedTab);
    super.onSaveInstanceState(state);
  }

  private void buildUi() {
    LinearLayout root = root();
    LinearLayout header = vertical();
    header.setPadding(dp(16), dp(8), dp(16), dp(8));
    root.addView(header);
    button(header, "返回选择设备", false, this::onBackPressed);
    device = text("", 20, Color.rgb(20, 31, 53));
    device.setTypeface(Typeface.MONOSPACE);
    gap(header, 8);
    header.addView(device);
    LinearLayout actions = new LinearLayout(this);
    header.addView(actions);
    refresh = rowButton(actions, "重新读取参数", controller::refresh);
    quick = rowButton(actions, "扫码一键配置", () -> scan(CONFIG_SCAN));
    TextView paste = text("粘贴配置码 JSON", 13, Color.rgb(22, 119, 255));
    paste.setPadding(dp(4), dp(8), dp(4), dp(8));
    paste.setMinHeight(dp(48));
    paste.setGravity(android.view.Gravity.CENTER_VERTICAL);
    paste.setOnClickListener(v -> pasteJson());
    controls.add(paste);
    header.addView(paste);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    header.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
    status = text("", 13, Color.rgb(72, 87, 109));
    status.setTextIsSelectable(true);
    gap(header, 6);
    header.addView(status);
    LinearLayout tabs = new LinearLayout(this);
    tabs.setPadding(dp(13), 0, dp(13), 0);
    root.addView(tabs);
    networkTab = rowButton(tabs, "网络配置", () -> chooseTab(0));
    mqttTab = rowButton(tabs, "MQTT 配置", () -> chooseTab(1));
    android.widget.FrameLayout panels = new android.widget.FrameLayout(this);
    root.addView(panels, new LinearLayout.LayoutParams(-1, 0, 1));
    networkPanel = formPanel(panels);
    mqttPanel = formPanel(panels);
    LinearLayout network = card((LinearLayout) networkPanel.getChildAt(0), "NETWORK", "网络参数");
    networkStatus = text("正在读取", 13, Color.rgb(96, 112, 135));
    network.addView(networkStatus);
    createFields(network, Protocol.NETWORK_KEYS, networkInputs, controller.networkDraft);
    LinearLayout mqtt = card((LinearLayout) mqttPanel.getChildAt(0), "MQTT", "MQTT 参数");
    mqttStatus = text("正在读取", 13, Color.rgb(96, 112, 135));
    mqtt.addView(mqttStatus);
    createFields(mqtt, Protocol.MQTT_KEYS, mqttInputs, controller.mqttDraft);
    LinearLayout footer = vertical();
    footer.setPadding(dp(16), dp(4), dp(16), dp(12));
    root.addView(footer);
    networkSave =
        button(
            footer,
            "预览并保存网络修改",
            true,
            () -> attempt(() -> controller.preview(controller.networkEdits())));
    mqttSave =
        button(
            footer,
            "预览并保存 MQTT 参数",
            true,
            () -> attempt(() -> controller.preview(controller.mqttEdits())));
    setContentView(root);
  }

  private ScrollView formPanel(android.widget.FrameLayout parent) {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout fields = vertical();
    fields.setSaveFromParentEnabled(false);
    fields.setPadding(dp(16), dp(12), dp(16), 0);
    scroll.addView(fields);
    parent.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
    return scroll;
  }

  private void chooseTab(int tab) {
    if (controller.selectedTab != tab) {
      View focused = getCurrentFocus();
      if (focused instanceof EditText) {
        focused.clearFocus();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(focused.getWindowToken(), 0);
      }
    }
    controller.selectedTab = tab;
    render();
  }

  private void createFields(
      LinearLayout parent,
      List<String> keys,
      Map<String, EditText> inputs,
      Map<String, String> draft) {
    for (String key : keys) {
      gap(parent, 14);
      TextView label = text(Protocol.LABELS.get(key), 13, Color.rgb(72, 87, 109));
      parent.addView(label);
      EditText input = new EditText(this);
      input.setTextSize(16);
      input.setSingleLine(true);
      input.setSaveEnabled(false);
      input.setId(View.generateViewId());
      label.setLabelFor(input.getId());
      input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
      if (java.util.Arrays.asList("port", "mode", "dhcp", "heartbeat", "heartbeat_interval")
          .contains(key)) input.setInputType(InputType.TYPE_CLASS_NUMBER);
      input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
      input.setHint(hint(key));
      parent.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
      inputs.put(key, input);
      controls.add(input);
      input.addTextChangedListener(
          new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
              if (!populating) draft.put(key, s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
          });
    }
  }

  private String hint(String key) {
    switch (key) {
      case "mode":
        return "0–9，7 为自建 MQTT 服务器";
      case "port":
        return "1–65535";
      case "dhcp":
        return "0 静态 / 1 DHCP";
      case "heartbeat":
        return "0 关闭 / 1 开启";
      case "heartbeat_interval":
        return "0–255 秒";
      case "dns":
        return "协议未定义读取位置；留空保持不变";
      case "heartbeat_content":
        return "与心跳主题合计最多 100 字节";
      case "id":
        return "最多 12 字节";
      case "hostname":
        return "最多 56 字节";
      default:
        return "";
    }
  }

  private void render() {
    device.setText(Protocol.displayMac(controller.mac));
    boolean networkSelected = controller.selectedTab == 0;
    networkPanel.setVisibility(networkSelected ? View.VISIBLE : View.GONE);
    mqttPanel.setVisibility(networkSelected ? View.GONE : View.VISIBLE);
    networkSave.setVisibility(networkSelected ? View.VISIBLE : View.GONE);
    mqttSave.setVisibility(networkSelected ? View.GONE : View.VISIBLE);
    tint(networkTab, networkSelected);
    tint(mqttTab, !networkSelected);
    networkTab.setSelected(networkSelected);
    mqttTab.setSelected(!networkSelected);
    status.setText(controller.status);
    status.setTextColor(controller.error ? Color.rgb(180, 35, 35) : Color.rgb(72, 87, 109));
    progress.setVisibility(controller.busy ? View.VISIBLE : View.GONE);
    for (View v : controls) v.setEnabled(!controller.busy);
    networkTab.setEnabled(true);
    mqttTab.setEnabled(true);
    refresh.setEnabled(!controller.busy && !controller.mac.isEmpty());
    quick.setEnabled(!controller.busy && !controller.mac.isEmpty());
    networkSave.setEnabled(!controller.busy && !controller.network.isEmpty());
    mqttSave.setEnabled(!controller.busy && !controller.mqtt.isEmpty());
    for (EditText e : networkInputs.values())
      e.setEnabled(!controller.busy && !controller.network.isEmpty());
    for (EditText e : mqttInputs.values())
      e.setEnabled(!controller.busy && !controller.mqtt.isEmpty());
    networkStatus.setText(
        controller.networkError.isEmpty()
            ? controller.network.isEmpty() ? "尚未读取" : "已读取 · 保存时只发送修改项"
            : controller.networkError);
    mqttStatus.setText(
        controller.mqttError.isEmpty()
            ? controller.mqtt.isEmpty() ? "尚未读取" : "已读取 · 保存前保留原值，保存后复读核验"
            : controller.mqttError);
    if (rendered != controller.revision) {
      populating = true;
      networkInputs.forEach((k, v) -> v.setText(controller.networkDraft.getOrDefault(k, "")));
      mqttInputs.forEach((k, v) -> v.setText(controller.mqttDraft.getOrDefault(k, "")));
      populating = false;
      rendered = controller.revision;
    }
    if (controller.preview != null && previewDialog == null && !isFinishing()) showPreview();
  }

  private void attempt(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException e) {
      controller.message(e.getMessage(), true);
    }
  }

  private void scan(int purpose) {
    Intent intent = new Intent(this, ScannerActivity.class);
    intent.putExtra("title", "扫描快捷配置码");
    startActivityForResult(intent, purpose);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == CONFIG_SCAN && result == RESULT_OK && data != null) {
      handleJson(data.getStringExtra("json"));
    }
  }

  private void handleJson(String json) {
    attempt(() -> controller.preview(QrConfig.configuration(json)));
  }

  private void pasteJson() {
    EditText input = new EditText(this);
    input.setHint("粘贴配置码 JSON（network / mqtt）");
    input.setMinLines(4);
    input.setSaveEnabled(false);
    input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    LinearLayout box = vertical();
    box.setPadding(dp(20), 0, dp(20), 0);
    box.addView(input);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("输入快捷配置码")
            .setView(box)
            .setPositiveButton("配置码", (d, w) -> handleJson(input.getText().toString()))
            .setNegativeButton("取消", null)
            .create();
    dialog.show();
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }

  private void showPreview() {
    QrConfig patch = controller.preview;
    StringBuilder text = new StringBuilder("设备：" + Protocol.displayMac(controller.mac) + "\n");
    appendPreview(text, "网络参数", patch.network, controller.network);
    appendPreview(text, "MQTT 参数", patch.mqtt, controller.mqtt);
    text.append("\n未列出的字段保持不变。保存前重新读取并合并 MQTT；先保存 MQTT，再保存网络。网络修改可能使设备地址改变。两个保存过程不是原子事务。");
    TextView details = text(text.toString(), 14, Color.rgb(45, 60, 80));
    details.setPadding(dp(24), dp(8), dp(24), dp(12));
    details.setTextIsSelectable(true);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(details);
    previewDialog =
        new AlertDialog.Builder(this)
            .setTitle("预览配置")
            .setView(scroll)
            .setNegativeButton("取消", (d, w) -> controller.preview = null)
            .setPositiveButton(
                patch.network.isEmpty() ? "保存 MQTT" : patch.mqtt.isEmpty() ? "保存网络参数" : "一键保存两种参数",
                (d, w) -> controller.savePreview())
            .create();
    previewDialog.setOnCancelListener(d -> controller.preview = null);
    previewDialog.setOnDismissListener(d -> previewDialog = null);
    previewDialog.show();
    previewDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }

  private void appendPreview(
      StringBuilder text, String title, Map<String, String> patch, Map<String, String> before) {
    if (patch.isEmpty()) return;
    text.append("\n").append(title).append("\n");
    for (Map.Entry<String, String> e : patch.entrySet()) {
      text.append(Protocol.LABELS.get(e.getKey()))
          .append("：")
          .append(before.getOrDefault(e.getKey(), "未读取"))
          .append(" → ")
          .append(e.getValue().isEmpty() ? "（空）" : e.getValue())
          .append("\n");
    }
  }

  @Override
  public void onBackPressed() {
    if (controller.busy) {
      controller.message("正在通信，请等待保存或读取完成后再退出", false);
      return;
    }
    super.onBackPressed();
  }
}
