package com.corxnet.config;

import android.os.Handler;
import android.os.Looper;
import com.corxnet.config.core.DeviceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 设备发现与参数会话分离；选择页不会读取或保存设备配置。 */
final class DeviceSelectionController {
  final List<DeviceRepository.DiscoveredDevice> devices = new ArrayList<>();
  String status = "手机连接设备所在 Wi-Fi，扫码或扫描局域网选择设备";
  boolean busy, error, closed;
  Runnable listener;
  private final DeviceRepository repository;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());

  DeviceSelectionController(DeviceRepository repository) {
    this.repository = repository;
  }

  void changed() {
    if (!closed && listener != null) listener.run();
  }

  void message(String text, boolean failed) {
    status = text;
    error = failed;
    changed();
  }

  void discover() {
    if (busy || closed) return;
    busy = true;
    devices.clear();
    message("正在扫描局域网设备…", false);
    executor.execute(
        () -> {
          try {
            List<DeviceRepository.DiscoveredDevice> result = repository.discover();
            main.post(
                () -> {
                  if (closed) return;
                  devices.clear();
                  devices.addAll(result);
                  busy = false;
                  message(
                      result.isEmpty()
                          ? "未发现设备，请检查同一 Wi-Fi、设备供电和网络隔离设置"
                          : "发现 " + result.size() + " 台设备，点击进入配置",
                      false);
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!closed) {
                    busy = false;
                    message(DeviceRepository.safeError(e), true);
                  }
                });
          }
        });
  }

  void close() {
    closed = true;
    listener = null;
    repository.cancel();
    executor.shutdownNow();
  }
}
