package com.corxnet.config.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 纯协议编解码；与 Go CLI 和 docs/MQTT.md 保持一致，不执行网络操作。 */
public final class Protocol {
  public static final List<String> NETWORK_KEYS =
      java.util.Arrays.asList(
          "ip",
          "target",
          "gateway",
          "netmask",
          "port",
          "mode",
          "mac",
          "dhcp",
          "dns",
          "hostname",
          "id",
          "heartbeat");
  public static final List<String> MQTT_KEYS =
      java.util.Arrays.asList(
          "username",
          "password",
          "subscribe_topic",
          "publish_topic",
          "device_id",
          "heartbeat_topic",
          "heartbeat_content",
          "heartbeat_interval");
  public static final List<String> SAVE_ORDER =
      java.util.Arrays.asList(
          "dns",
          "hostname",
          "id",
          "heartbeat",
          "target",
          "gateway",
          "netmask",
          "mode",
          "port",
          "dhcp",
          "ip",
          "mac");
  public static final Map<String, String> LABELS;

  static {
    Map<String, String> labels = new LinkedHashMap<>();
    String[] names = {
      "本地 IP",
      "目标 IP",
      "网关",
      "子网掩码",
      "端口",
      "工作模式",
      "MAC 地址",
      "DHCP",
      "DNS 地址（只写）",
      "DNS 网址",
      "唯一 ID",
      "网络心跳"
    };
    for (int i = 0; i < NETWORK_KEYS.size(); i++) labels.put(NETWORK_KEYS.get(i), names[i]);
    String[] mqtt = {"用户名", "密码", "订阅主题", "发布主题", "设备 ID", "心跳主题", "心跳内容", "心跳间隔（秒）"};
    for (int i = 0; i < MQTT_KEYS.size(); i++) labels.put(MQTT_KEYS.get(i), mqtt[i]);
    LABELS = Collections.unmodifiableMap(labels);
  }

  private Protocol() {}

  public static String mac(String input) {
    if (input == null
        || !input.matches(
            "(?i)([0-9a-f]{12}|([0-9a-f]{2}:){5}[0-9a-f]{2}|([0-9a-f]{2}-){5}[0-9a-f]{2})")) {
      throw new IllegalArgumentException("MAC 须为完整的 12 位十六进制，可用冒号或连字符分隔");
    }
    return input.replace(":", "").replace("-", "").toUpperCase(Locale.ROOT);
  }

  public static String displayMac(String value) {
    String m = mac(value);
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < 12; i += 2) {
      if (i > 0) result.append(':');
      result.append(m, i, i + 2);
    }
    return result.toString();
  }

  private static byte[] macBytes(String value) {
    String m = mac(value);
    byte[] bytes = new byte[6];
    for (int i = 0; i < 6; i++)
      bytes[i] = (byte) Integer.parseInt(m.substring(i * 2, i * 2 + 2), 16);
    return bytes;
  }

  public static int integer(String value, int min, int max, String name) {
    if (value == null || !value.matches("[0-9]+"))
      throw new IllegalArgumentException(name + "须为整数");
    try {
      int n = Integer.parseInt(value);
      if (n < min || n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + "须为 " + min + "–" + max);
    }
  }

  private static byte[] ipv4(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) throw new IllegalArgumentException("IPv4 地址无效");
    byte[] bytes = new byte[4];
    for (int i = 0; i < 4; i++) {
      if (parts[i].length() > 1 && parts[i].startsWith("0"))
        throw new IllegalArgumentException("IPv4 不允许前导零");
      bytes[i] = (byte) integer(parts[i], 0, 255, "IPv4 段");
    }
    return bytes;
  }

  public static String normalizeSetting(String key, String value) {
    setting(key, value);
    if (key.equals("mac")) return mac(value);
    if (java.util.Arrays.asList("port", "mode", "dhcp", "heartbeat").contains(key))
      return String.valueOf(Integer.parseInt(value));
    return value;
  }

  public static byte[] setting(String key, String value) {
    if (value == null) throw new IllegalArgumentException("缺少 " + LABELS.getOrDefault(key, key));
    switch (key) {
      case "ip":
        return concat(new byte[] {1}, ipv4(value));
      case "target":
        return concat(new byte[] {2}, ipv4(value));
      case "gateway":
        return concat(new byte[] {3}, ipv4(value));
      case "netmask":
        return concat(new byte[] {7}, ipv4(value));
      case "dns":
        return concat(new byte[] {0x19}, ipv4(value));
      case "mode":
        return new byte[] {4, (byte) integer(value, 0, 9, "模式"), -1, -1};
      case "port":
        int port = integer(value, 1, 65535, "端口");
        return new byte[] {5, (byte) port, (byte) (port >> 8), -1, -1};
      case "dhcp":
        return new byte[] {9, (byte) integer(value, 0, 1, "DHCP"), -1, -1};
      case "heartbeat":
        return new byte[] {12, (byte) integer(value, 0, 1, "网络心跳")};
      case "mac":
        return concat(new byte[] {(byte) 0xf8}, macBytes(value));
      case "id":
      case "hostname":
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        int max = key.equals("id") ? 12 : 56;
        if (text.length > max)
          throw new IllegalArgumentException(LABELS.get(key) + "最多 " + max + " 字节");
        return key.equals("id")
            ? concat(new byte[] {0x12, (byte) text.length}, text)
            : concat(new byte[] {0x20}, text);
      default:
        throw new IllegalArgumentException("不支持的网络参数：" + key);
    }
  }

  public static byte[] networkFrame(String mac, byte[] payload) {
    return concat(new byte[] {payload[0]}, Arrays.copyOfRange(macBytes(mac), 1, 6), payload);
  }

  public static byte[] networkRead(String mac) {
    return networkFrame(mac, new byte[] {10, -1, -1, -1, -1});
  }

  public static byte[] networkSave(String mac) {
    return concat(
        new byte[] {-1}, Arrays.copyOfRange(macBytes(mac), 1, 6), new byte[] {-1, -1, -1, -1});
  }

  public static byte[] mqttRead(String mac) {
    return concat(
        new byte[] {0x33, (byte) 0xbb},
        mac(mac).getBytes(StandardCharsets.US_ASCII),
        new byte[] {(byte) 0xbb, 0x33});
  }

  public static String responseMac(byte[] frame) {
    if (frame.length < 256) throw new IllegalArgumentException("网络响应不足 256 字节");
    return String.format(
        Locale.ROOT,
        "%02X%02X%02X%02X%02X%02X",
        u(frame[60]),
        u(frame[61]),
        u(frame[56]),
        u(frame[57]),
        u(frame[58]),
        u(frame[59]));
  }

  public static Map<String, String> parseNetwork(byte[] frame) {
    if (frame.length < 256) throw new IllegalArgumentException("网络响应不足 256 字节");
    Map<String, String> values = new LinkedHashMap<>();
    values.put("mode", String.valueOf(u(frame[0])));
    values.put("port", String.valueOf(u(frame[8]) | (u(frame[9]) << 8)));
    for (int i = 0; i < 4; i++) {
      int p = 40 + i * 4;
      values.put(
          java.util.Arrays.asList("ip", "target", "gateway", "netmask").get(i),
          u(frame[p]) + "." + u(frame[p + 1]) + "." + u(frame[p + 2]) + "." + u(frame[p + 3]));
    }
    values.put("mac", responseMac(frame));
    values.put(
        "hostname", new String(frame, 131, Math.min(u(frame[130]), 56), StandardCharsets.UTF_8));
    values.put("id", new String(frame, 181, Math.min(u(frame[180]), 12), StandardCharsets.UTF_8));
    values.put("dhcp", frame[254] == 1 ? "1" : "0");
    values.put("heartbeat", frame[252] == 1 ? "1" : "0");
    return values; // DNS 读取偏移未定义，不推断其值。
  }

  private static byte[] ascii(String key, String value) {
    if (value == null)
      throw new IllegalArgumentException("缺少 MQTT " + LABELS.getOrDefault(key, key));
    if (value.length() > 100)
      throw new IllegalArgumentException(LABELS.getOrDefault(key, key) + "最多 100 字节");
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) == 0 || value.charAt(i) > 127) {
        throw new IllegalArgumentException("MQTT 参数只支持不含 NUL 的 ASCII 字符");
      }
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  public static Map<String, String> validateMqtt(Map<String, String> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String key : MQTT_KEYS) {
      String value = values.get(key);
      if (key.equals("heartbeat_interval")) value = String.valueOf(integer(value, 0, 255, "心跳间隔"));
      else ascii(key, value);
      result.put(key, value);
    }
    if (result.get("heartbeat_topic").length() + result.get("heartbeat_content").length() > 100) {
      throw new IllegalArgumentException("心跳主题与内容合计最多 100 字节");
    }
    return result;
  }

  public static byte[] mqttSave(String mac, Map<String, String> values) {
    Map<String, String> fields = validateMqtt(values);
    byte[] frame = new byte[623];
    frame[0] = 0x44;
    frame[1] = (byte) 0xaa;
    System.arraycopy(mac(mac).getBytes(StandardCharsets.US_ASCII), 0, frame, 2, 12);
    for (int i = 0; i < 5; i++) {
      byte[] text = ascii(MQTT_KEYS.get(i), fields.get(MQTT_KEYS.get(i)));
      frame[14 + i * 101] = (byte) text.length;
      System.arraycopy(text, 0, frame, 15 + i * 101, text.length);
    }
    frame[519] = (byte) fields.get("heartbeat_topic").length();
    frame[520] = (byte) Integer.parseInt(fields.get("heartbeat_interval"));
    byte[] heartbeat =
        ascii("心跳主题与内容", fields.get("heartbeat_topic") + fields.get("heartbeat_content"));
    System.arraycopy(heartbeat, 0, frame, 521, heartbeat.length);
    frame[621] = (byte) 0xaa;
    frame[622] = 0x44;
    return frame;
  }

  public static Map<String, String> parseMqtt(String mac, byte[] frame) {
    if (frame.length != 623
        || u(frame[0]) != 0x33
        || u(frame[1]) != 0xbb
        || u(frame[621]) != 0xbb
        || u(frame[622]) != 0x33) {
      throw new IllegalArgumentException("MQTT 响应长度或帧头尾不正确");
    }
    if (!new String(frame, 2, 12, StandardCharsets.US_ASCII).equals(mac(mac)))
      throw new IllegalArgumentException("MQTT 响应设备不匹配");
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < 5; i++) {
      int offset = 14 + i * 101;
      int length = u(frame[offset]);
      if (length > 100) throw new IllegalArgumentException("MQTT 字段长度越界");
      for (int j = offset + 1 + length; j < offset + 101; j++)
        if (frame[j] != 0) throw new IllegalArgumentException("MQTT 零填充格式不正确");
      values.put(
          MQTT_KEYS.get(i), new String(frame, offset + 1, length, StandardCharsets.ISO_8859_1));
    }
    int end = 621;
    while (end > 521 && frame[end - 1] == 0) end--;
    int topicLength = u(frame[519]);
    if (topicLength > end - 521) throw new IllegalArgumentException("心跳主题长度越界");
    values.put("heartbeat_topic", new String(frame, 521, topicLength, StandardCharsets.ISO_8859_1));
    values.put(
        "heartbeat_content",
        new String(frame, 521 + topicLength, end - 521 - topicLength, StandardCharsets.ISO_8859_1));
    values.put("heartbeat_interval", String.valueOf(u(frame[520])));
    return validateMqtt(values);
  }

  public static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.write(part, 0, part.length);
    return out.toByteArray();
  }

  private static int u(byte value) {
    return value & 0xff;
  }
}
