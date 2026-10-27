package com.corxnet.config;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.corxnet.config.core.DeviceRepository;
import com.corxnet.config.core.Protocol;
import com.corxnet.config.core.QrConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 仅在进程内持有凭据和编辑草稿；旋转屏幕保留会话，不将参数写入 Bundle 或磁盘。 */
final class ConfigController {
  final Map<String, String> network = new LinkedHashMap<>();
  final Map<String, String> mqtt = new LinkedHashMap<>();
  final Map<String, String> networkDraft = new LinkedHashMap<>();
  final Map<String, String> mqttDraft = new LinkedHashMap<>();
  String mac = "";
  String status = "正在连接设备…";
  String networkError = "";
  String mqttError = "";
  boolean busy;
  boolean error;
  long revision;
  int selectedTab;
  QrConfig preview;
  Runnable listener;
  private boolean closed;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final DeviceRepository repository;

  ConfigController(Context context) {
    Context app = context.getApplicationContext();
    repository = ((CorxnetApplication) app).repository();
  }

  void changed() {
    if (!closed && listener != null) listener.run();
  }

  void message(String text, boolean failed) {
    status = text;
    error = failed;
    changed();
  }

  private boolean start(String text) {
    if (busy || closed) return false;
    busy = true;
    error = false;
    status = text;
    changed();
    return true;
  }

  void refresh() {
    if (mac.isEmpty() || !start("正在读取网络参数与 MQTT 参数…")) return;
    String target = mac;
    executor.execute(
        () -> {
          Map<String, String> n = null, m = null;
          String ne = "", me = "";
          try {
            n = repository.readNetwork(target).values;
          } catch (Exception e) {
            ne = DeviceRepository.safeError(e);
          }
          try {
            m = repository.readMqtt(target).values;
          } catch (Exception e) {
            me = DeviceRepository.safeError(e);
          }
          Map<String, String> networkResult = n, mqttResult = m;
          String networkFailure = ne, mqttFailure = me;
          main.post(
              () -> {
                if (closed) return;
                network.clear();
                networkDraft.clear();
                mqtt.clear();
                mqttDraft.clear();
                if (networkResult != null) {
                  network.putAll(networkResult);
                  networkDraft.putAll(networkResult);
                }
                if (mqttResult != null) {
                  mqtt.putAll(mqttResult);
                  mqttDraft.putAll(mqttResult);
                }
                networkError = networkFailure;
                mqttError = mqttFailure;
                revision++;
                busy = false;
                message(
                    networkResult != null && mqttResult != null
                        ? "读取完成，可以编辑或扫描快捷配置码"
                        : "读取未全部完成，请检查 Wi-Fi 后重试；已成功的参数仍可编辑",
                    networkResult == null || mqttResult == null);
              });
        });
  }

  QrConfig networkEdits() {
    Map<String, String> changes = new LinkedHashMap<>();
    for (String key : Protocol.NETWORK_KEYS) {
      String value = networkDraft.getOrDefault(key, "");
      if (key.equals("dns") && value.isEmpty()) continue;
      if (!value.equals(network.get(key))) changes.put(key, value);
    }
    return new QrConfig(changes, java.util.Collections.emptyMap());
  }

  QrConfig mqttEdits() {
    return new QrConfig(java.util.Collections.emptyMap(), new LinkedHashMap<>(mqttDraft));
  }

  void preview(QrConfig patch) {
    if (busy || mac.isEmpty()) return;
    if (!patch.mqtt.isEmpty() && !mqtt.isEmpty()) patch.mergeMqtt(mqtt);
    preview = patch;
    changed();
  }

  void savePreview() {
    QrConfig patch = preview;
    if (patch == null || !start("正在保存并复读核验，请保持 Wi-Fi 连接…")) return;
    preview = null;
    String target = mac;
    executor.execute(
        () -> {
          DeviceRepository.SaveResult result = repository.save(target, patch);
          main.post(
              () -> {
                if (closed) return;
                mac = result.mac;
                if (result.mqtt != null) {
                  mqtt.clear();
                  mqtt.putAll(result.mqtt);
                  mqttDraft.clear();
                  mqttDraft.putAll(result.mqtt);
                }
                if (result.network != null) {
                  network.clear();
                  network.putAll(result.network);
                  networkDraft.clear();
                  networkDraft.putAll(result.network);
                } else if (!patch.network.isEmpty()) {
                  // 网络参数可能已经部分生效，清除旧基线，避免继续依据旧值保存。
                  network.clear();
                  networkDraft.clear();
                  networkError = "请重新读取，确认网络配置是否已生效";
                }
                revision++;
                busy = false;
                message(result.message, !result.verified);
              });
        });
  }

  void close() {
    closed = true;
    listener = null;
    repository.cancel();
    executor.shutdownNow();
    network.clear();
    mqtt.clear();
    networkDraft.clear();
    mqttDraft.clear();
    preview = null;
  }
}
