package com.corxnet.config.core;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 串行设备会话；内容校验完成后才发送保存帧，部分完成不伪装成原子事务。 */
public final class DeviceRepository {
  public interface Factory {
    Transport open() throws IOException;
  }

  public interface Transport extends AutoCloseable {
    void send(byte[] frame) throws IOException;

    Packet receive(long deadlineNanos) throws IOException;

    @Override
    void close();
  }

  public static final class Packet {
    public final byte[] data;
    public final String address;

    public Packet(byte[] data, String address) {
      this.data = data;
      this.address = address;
    }
  }

  public static final class Reading {
    public final Map<String, String> values;
    public final String address;

    Reading(Map<String, String> values, String address) {
      this.values = values;
      this.address = address;
    }
  }

  public static final class DiscoveredDevice {
    public final String mac, address;

    public DiscoveredDevice(String mac, String address) {
      this.mac = mac;
      this.address = address;
    }
  }

  public static final class SaveResult {
    public String mac;
    public final List<String> completed = new ArrayList<>();
    public Map<String, String> mqtt;
    public Map<String, String> network;
    public String message;
    public boolean verified;
  }

  private final Factory factory;
  private volatile Transport active;
  private final long timeoutNanos;

  public DeviceRepository(Factory factory) {
    this(factory, 1_500_000_000L);
  }

  public DeviceRepository(Factory factory, long timeoutNanos) {
    this.factory = factory;
    this.timeoutNanos = timeoutNanos;
  }

  public void cancel() {
    Transport t = active;
    if (t != null) t.close();
  }

  private long deadline() {
    return System.nanoTime() + timeoutNanos;
  }

  public synchronized List<DiscoveredDevice> discover() throws IOException {
    try (Transport t = factory.open()) {
      active = t;
      t.send(new byte[5]);
      long deadline = deadline();
      Map<String, DiscoveredDevice> devices = new LinkedHashMap<>();
      while (true) {
        Packet packet;
        try {
          packet = t.receive(deadline);
        } catch (SocketTimeoutException complete) {
          return new ArrayList<>(devices.values());
        }
        byte[] b = packet.data;
        if (b.length < 8 || b[0] != 0 || b[1] != 1) continue;
        // 扫描只返回 MAC 后五字节，与 CLI 一样补首字节 00。
        String mac =
            String.format(
                Locale.ROOT,
                "00%02X%02X%02X%02X%02X",
                b[2] & 255,
                b[3] & 255,
                b[4] & 255,
                b[5] & 255,
                b[6] & 255);
        if (!devices.containsKey(mac)) devices.put(mac, new DiscoveredDevice(mac, packet.address));
      }
    } finally {
      active = null;
    }
  }

  public synchronized Reading readNetwork(String mac) throws IOException {
    try (Transport t = factory.open()) {
      active = t;
      return network(t, mac);
    } finally {
      active = null;
    }
  }

  public synchronized Reading readMqtt(String mac) throws IOException {
    try (Transport t = factory.open()) {
      active = t;
      return mqtt(t, mac);
    } finally {
      active = null;
    }
  }

  private Reading network(Transport t, String mac) throws IOException {
    t.send(Protocol.networkRead(mac));
    long deadline = deadline();
    while (true) {
      Packet p = t.receive(deadline);
      if (p.data.length >= 256
          && Protocol.responseMac(p.data).substring(2).equals(Protocol.mac(mac).substring(2))) {
        return new Reading(Protocol.parseNetwork(p.data), p.address);
      }
    }
  }

  private Reading mqtt(Transport t, String mac) throws IOException {
    t.send(Protocol.mqttRead(mac));
    long deadline = deadline();
    while (true) {
      Packet p = t.receive(deadline);
      try {
        return new Reading(Protocol.parseMqtt(mac, p.data), p.address);
      } catch (IllegalArgumentException ignored) {
        /* 忽略其他设备、保存应答及无效响应。 */
      }
    }
  }

  private void saveMqtt(Transport t, String address, byte[] frame) throws IOException {
    t.send(frame);
    long deadline = deadline();
    while (true) {
      Packet p = t.receive(deadline);
      if (p.address.equals(address)
          && Arrays.equals(p.data, new byte[] {0x44, (byte) 0xaa, (byte) 0xaa, 0x44})) return;
    }
  }

  private void verifiedMqtt(Transport t, String mac, Reading before, Map<String, String> wanted)
      throws IOException {
    byte[] frame = Protocol.mqttSave(mac, wanted);
    // 保存应答丢失时仍复读，避免误将成功写入当作失败恢复。
    try {
      saveMqtt(t, before.address, frame);
    } catch (SocketTimeoutException ignored) {
    }
    try {
      if (mqtt(t, mac).values.equals(wanted)) return;
    } catch (IOException ignored) {
    }
    try {
      try {
        saveMqtt(t, before.address, Protocol.mqttSave(mac, before.values));
      } catch (SocketTimeoutException ignored) {
      }
      if (mqtt(t, mac).values.equals(before.values)) throw new RestoredException();
    } catch (RestoredException restored) {
      throw new IOException("MQTT 复读核验失败；已恢复写入前完整配置并复读确认");
    } catch (IOException ignored) {
    }
    throw new IOException("MQTT 复读核验失败，恢复也未确认；请重新读取检查");
  }

  private static final class RestoredException extends IOException {}

  private void setting(Transport t, String mac, String key, String value, String address)
      throws IOException {
    byte[] payload = Protocol.setting(key, value);
    t.send(Protocol.networkFrame(mac, payload));
    long deadline = deadline();
    try {
      while (true) {
        Packet p = t.receive(deadline);
        if (p.address.equals(address) && Arrays.equals(p.data, new byte[] {payload[0], 1, 13, 10}))
          break;
      }
    } catch (SocketTimeoutException ignored) {
      // CLI 同样在配置应答超时后发送保存，最终以复读结果为准。
    }
    t.send(Protocol.networkSave(mac));
  }

  public synchronized SaveResult save(String deviceMac, QrConfig patch) {
    SaveResult result = new SaveResult();
    result.mac = Protocol.mac(deviceMac);
    // QrConfig 已校验所有单项；网络参数未发送前再检查合并后的 MQTT 总长度。
    try (Transport t = factory.open()) {
      active = t;
      Reading beforeNetwork = patch.network.isEmpty() ? null : network(t, result.mac);
      Reading beforeMqtt = patch.mqtt.isEmpty() ? null : mqtt(t, result.mac);
      Map<String, String> wanted = beforeMqtt == null ? null : patch.mergeMqtt(beforeMqtt.values);
      if (wanted != null) {
        verifiedMqtt(t, result.mac, beforeMqtt, wanted);
        result.mqtt = wanted;
        result.completed.add("MQTT（全部字段已复读确认）");
      }
      for (String key : Protocol.SAVE_ORDER) {
        if (!patch.network.containsKey(key)) continue;
        setting(t, result.mac, key, patch.network.get(key), beforeNetwork.address);
        result.completed.add(Protocol.LABELS.get(key) + "（保存帧已发送）");
        if (key.equals("mac")) result.mac = Protocol.mac(patch.network.get(key));
      }
      if (!patch.network.isEmpty()) {
        result.network = network(t, result.mac).values;
        List<String> mismatch = new ArrayList<>();
        for (Map.Entry<String, String> e : patch.network.entrySet()) {
          if (!e.getKey().equals("dns") && !e.getValue().equals(result.network.get(e.getKey())))
            mismatch.add(Protocol.LABELS.get(e.getKey()));
        }
        if (!mismatch.isEmpty()) throw new IOException("网络复读不一致：" + String.join("、", mismatch));
      }
      result.verified = true;
      result.message =
          patch.network.containsKey("dns") ? "保存完成，其余参数已复读确认；DNS 读取偏移未定义，仅确认保存帧已发送" : "保存完成，复读核验一致";
    } catch (IOException | RuntimeException e) {
      result.message =
          "未全部完成："
              + safeError(e)
              + "\n已完成："
              + (result.completed.isEmpty() ? "无" : String.join("、", result.completed))
              + "\n已完成部分不会自动回滚，请重新读取核对。";
    } finally {
      active = null;
    }
    return result;
  }

  public static String safeError(Exception e) {
    if (e instanceof SocketTimeoutException) return "设备响应超时，请检查同一 Wi-Fi、设备供电和 UDP 端口";
    String message = e.getMessage();
    return message == null || message.isEmpty() ? "设备通信失败，请检查 Wi-Fi 连接" : message;
  }
}
