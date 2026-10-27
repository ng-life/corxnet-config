package com.corxnet.config;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.corxnet.config.core.DeviceRepository;
import com.corxnet.config.core.Protocol;
import com.corxnet.config.core.QrConfig;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** 模拟传输仅存在于测试 APK；覆盖 Activity 跳转和 Tab 业务，不访问真实控制器。 */
@SuppressWarnings("deprecation")
public final class AppFlowTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public AppFlowTest() {
    super(MainActivity.class);
  }

  private MainActivity selection;
  private DeviceConfigActivity activity;
  private ConfigController controller;
  private CorxnetApplication app;
  private DeviceRepository.Factory originalFactory;
  private final FakeDevice fake = new FakeDevice();

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    selection = getActivity();
    app = (CorxnetApplication) selection.getApplication();
    originalFactory = app.transportFactory;
    app.transportFactory = () -> fake;
  }

  @Override
  protected void tearDown() throws Exception {
    if (activity != null) ui(activity::finish);
    super.tearDown();
    if (app != null) app.transportFactory = originalFactory;
  }

  private Object field(Object target, String name) {
    try {
      Field f = target.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(target);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private View find(View root, String text) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View found = find(((ViewGroup) root).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private Button button(String text) {
    return (Button)
        find((activity == null ? selection : activity).getWindow().getDecorView(), text);
  }

  private void ui(Runnable action) {
    java.util.concurrent.atomic.AtomicReference<Throwable> failure =
        new java.util.concurrent.atomic.AtomicReference<>();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                action.run();
              } catch (Throwable e) {
                failure.set(e);
              }
            });
    getInstrumentation().waitForIdleSync();
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  private void settled() throws Exception {
    for (int i = 0; i < 150; i++) {
      boolean[] busy = {true};
      ui(
          () ->
              busy[0] =
                  controller == null
                      ? ((DeviceSelectionController) field(selection, "controller")).busy
                      : controller.busy);
      if (!busy[0]) return;
      Thread.sleep(30);
    }
    fail("operation did not settle");
  }

  private void configOpened(Instrumentation.ActivityMonitor monitor) throws Exception {
    activity = (DeviceConfigActivity) monitor.waitForActivityWithTimeout(5000);
    getInstrumentation().removeMonitor(monitor);
    assertNotNull("configuration Activity did not open", activity);
    controller = (ConfigController) field(activity, "controller");
    settled();
  }

  private void openConfig() throws Exception {
    Instrumentation.ActivityMonitor monitor =
        getInstrumentation().addMonitor(DeviceConfigActivity.class.getName(), null, false);
    ui(
        () ->
            selection.onActivityResult(
                100,
                Activity.RESULT_OK,
                new Intent().putExtra("json", "{\"mac\":\"0090E2D72060\"}")));
    configOpened(monitor);
  }

  private AlertDialog dialog() {
    return (AlertDialog) field(activity, "previewDialog");
  }

  @SuppressWarnings("unchecked")
  private EditText input(String group, String key) {
    return ((Map<String, EditText>) field(activity, group)).get(key);
  }

  public void testDeviceQrOpensConfigurationTabsAndCombinedPreviewSave() throws Exception {
    ui(
        () -> {
          assertNotNull(button("扫描设备二维码"));
          assertNotNull(button("扫描局域网设备"));
          assertNull(find(selection.getWindow().getDecorView(), "网络参数"));
        });
    openConfig();
    ui(
        () -> {
          assertEquals("0090E2D72060", controller.mac);
          assertEquals("50000", controller.network.get("port"));
          assertEquals("30", controller.mqtt.get("heartbeat_interval"));
          assertEquals(View.VISIBLE, ((View) field(activity, "networkPanel")).getVisibility());
          assertEquals(View.GONE, ((View) field(activity, "mqttPanel")).getVisibility());
          activity.onActivityResult(
              101,
              Activity.RESULT_OK,
              new Intent()
                  .putExtra(
                      "json",
                      "{\"network\":{\"port\":1883},\"mqtt\":{\"heartbeat_content\":\"new-body\"}}"));
        });
    ui(
        () -> {
          assertNotNull(dialog());
          assertEquals(
              "一键保存两种参数", dialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
          assertEquals(0, fake.writes);
          dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
    settled();
    ui(
        () -> {
          assertFalse(controller.status, controller.error);
          assertEquals("1883", controller.network.get("port"));
          assertEquals("new-body", controller.mqtt.get("heartbeat_content"));
          assertEquals("retained", controller.mqtt.get("username"));
          assertEquals(1, fake.writes);
        });
  }

  public void testSeparateTabsPreserveDraftAndSaveIndependently() throws Exception {
    openConfig();
    ui(
        () -> {
          input("networkInputs", "port").setText("1884");
          button("MQTT 配置").performClick();
          assertEquals(View.GONE, ((View) field(activity, "networkPanel")).getVisibility());
          assertEquals(View.VISIBLE, ((View) field(activity, "mqttPanel")).getVisibility());
          assertEquals(View.GONE, button("预览并保存网络修改").getVisibility());
          button("网络配置").performClick();
          assertEquals("1884", input("networkInputs", "port").getText().toString());
          button("预览并保存网络修改").performClick();
        });
    ui(() -> dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
    ui(
        () -> {
          assertEquals(50000, fake.port);
          assertEquals(0, fake.writes);
          button("预览并保存网络修改").performClick();
        });
    ui(() -> dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    settled();
    ui(
        () -> {
          assertEquals("1884", controller.network.get("port"));
          assertEquals(0, fake.writes);
          button("MQTT 配置").performClick();
          input("mqttInputs", "heartbeat_interval").setText("60");
          button("预览并保存 MQTT 参数").performClick();
        });
    ui(() -> dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    settled();
    ui(
        () -> {
          assertEquals("60", controller.mqtt.get("heartbeat_interval"));
          assertEquals(1, fake.writes);
        });
  }

  public void testLanDiscoveryDeduplicatesAndSelectionNavigates() throws Exception {
    ui(() -> button("扫描局域网设备").performClick());
    settled();
    ui(
        () -> {
          DeviceSelectionController discovery =
              (DeviceSelectionController) field(selection, "controller");
          assertEquals(2, discovery.devices.size());
          assertEquals(0, fake.reads);
          assertEquals(0, fake.writes);
          assertNotNull(find(selection.getWindow().getDecorView(), "00:90:E2:D7:20:60"));
        });
    Instrumentation.ActivityMonitor monitor =
        getInstrumentation().addMonitor(DeviceConfigActivity.class.getName(), null, false);
    ui(
        () ->
            ((View) find(selection.getWindow().getDecorView(), "00:90:E2:D7:20:60").getParent())
                .performClick());
    configOpened(monitor);
    ui(
        () -> {
          assertEquals("0090E2D72060", controller.mac);
          activity.onBackPressed();
        });
    activity = null;
    controller = null;
    for (int i = 0; i < 100; i++) {
      boolean[] resumed = {false};
      ui(() -> resumed[0] = button("扫描设备二维码").isEnabled());
      if (resumed[0]) break;
      Thread.sleep(30);
    }
    ui(
        () -> {
          assertTrue(button("扫描设备二维码").isEnabled());
          assertNotNull(find(selection.getWindow().getDecorView(), "00:90:E2:D7:20:60"));
        });
  }

  public void testMalformedDeviceQrDoesNotNavigateOrRead() {
    ui(
        () -> {
          selection.onActivityResult(
              100, Activity.RESULT_OK, new Intent().putExtra("json", "{\"mac\":\"bad\"}"));
          assertTrue(((DeviceSelectionController) field(selection, "controller")).error);
          assertEquals(0, fake.reads);
          assertEquals(0, fake.writes);
        });
  }

  public void testScannerCameraPreviewStartsAndCloses() throws Exception {
    getInstrumentation()
        .getUiAutomation()
        .grantRuntimePermission("com.corxnet.config", android.Manifest.permission.CAMERA);
    Instrumentation.ActivityMonitor monitor =
        getInstrumentation().addMonitor(ScannerActivity.class.getName(), null, false);
    ui(() -> button("扫描设备二维码").performClick());
    ScannerActivity scanner = (ScannerActivity) monitor.waitForActivityWithTimeout(5000);
    assertNotNull(scanner);
    boolean opened = false;
    for (int i = 0; i < 100; i++) {
      if (field(scanner, "camera") != null) {
        opened = true;
        break;
      }
      Thread.sleep(50);
    }
    assertTrue("camera did not open", opened);
    getInstrumentation().runOnMainSync(scanner::finish);
    getInstrumentation().waitForIdleSync();
    getInstrumentation().removeMonitor(monitor);
    assertEquals(0, fake.writes);
  }

  private static final class FakeDevice implements DeviceRepository.Transport {
    final ArrayDeque<DeviceRepository.Packet> replies = new ArrayDeque<>();
    Map<String, String> mqtt = QrConfig.emptyMqtt();
    int writes, reads, port = 50000;

    FakeDevice() {
      mqtt.put("username", "retained");
      mqtt.put("heartbeat_topic", "ping");
      mqtt.put("heartbeat_content", "alive");
      mqtt.put("heartbeat_interval", "30");
    }

    void reply(byte[] data) {
      replies.add(new DeviceRepository.Packet(data, "127.0.0.1:60000"));
    }

    @Override
    public void send(byte[] frame) {
      if (frame.length == 5) {
        reply(new byte[] {0, 1, (byte) 0x90, (byte) 0xe2, (byte) 0xd7, 0x20, 0x60, 0});
        reply(new byte[] {0, 1, (byte) 0x90, (byte) 0xe2, (byte) 0xd7, 0x20, 0x60, 0});
        reply(new byte[] {9});
        reply(new byte[] {0, 1, 0x11, 0x22, 0x33, 0x44, 0x55, 0});
      } else if (frame.length == 16) {
        reads++;
        byte[] response = Protocol.mqttSave("0090E2D72060", mqtt);
        response[0] = 0x33;
        response[1] = (byte) 0xbb;
        response[621] = (byte) 0xbb;
        response[622] = 0x33;
        reply(response);
      } else if (frame.length == 623) {
        writes++;
        byte[] response = frame.clone();
        response[0] = 0x33;
        response[1] = (byte) 0xbb;
        response[621] = (byte) 0xbb;
        response[622] = 0x33;
        mqtt = new LinkedHashMap<>(Protocol.parseMqtt("0090E2D72060", response));
        reply(new byte[] {0x44, (byte) 0xaa, (byte) 0xaa, 0x44});
      } else if (frame[0] == 10) {
        reads++;
        byte[] response = new byte[256];
        response[0] = 7;
        response[8] = (byte) port;
        response[9] = (byte) (port >> 8);
        response[40] = (byte) 192;
        response[41] = (byte) 168;
        response[43] = 18;
        response[56] = (byte) 0xe2;
        response[57] = (byte) 0xd7;
        response[58] = 0x20;
        response[59] = 0x60;
        response[61] = (byte) 0x90;
        reply(response);
      } else if (frame[0] == 5) {
        port = (frame[7] & 255) | ((frame[8] & 255) << 8);
        reply(new byte[] {5, 1, 13, 10});
      }
    }

    @Override
    public DeviceRepository.Packet receive(long deadline) throws SocketTimeoutException {
      if (replies.isEmpty()) throw new SocketTimeoutException();
      return replies.remove();
    }

    @Override
    public void close() {}
  }
}
