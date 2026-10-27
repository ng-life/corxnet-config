package com.corxnet.config;

import android.app.Application;
import com.corxnet.config.core.DeviceRepository;

/** 统一会话创建边界；测试 APK 注入内存传输，正式应用始终使用 Wi-Fi UDP。 */
public final class CorxnetApplication extends Application {
  DeviceRepository.Factory transportFactory;

  @Override
  public void onCreate() {
    super.onCreate();
    transportFactory = () -> new WifiTransport(this);
  }

  DeviceRepository repository() {
    return new DeviceRepository(() -> transportFactory.open());
  }
}
