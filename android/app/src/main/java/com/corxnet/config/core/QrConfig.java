package com.corxnet.config.core;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** 二维码只承载已知参数，不接受命令字、外部 URL 或脚本。 */
public final class QrConfig {
  public final Map<String, String> network;
  public final Map<String, String> mqtt;

  public QrConfig(Map<String, String> network, Map<String, String> mqtt) {
    Map<String, String> n = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : network.entrySet())
      n.put(e.getKey(), Protocol.normalizeSetting(e.getKey(), e.getValue()));
    Map<String, String> m = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : mqtt.entrySet()) {
      if (!Protocol.MQTT_KEYS.contains(e.getKey()))
        throw new IllegalArgumentException("不支持的 MQTT 字段：" + e.getKey());
      Map<String, String> single = emptyMqtt();
      single.put(e.getKey(), e.getValue());
      m.put(e.getKey(), Protocol.validateMqtt(single).get(e.getKey()));
    }
    if (m.containsKey("heartbeat_topic")
        && m.containsKey("heartbeat_content")
        && m.get("heartbeat_topic").length() + m.get("heartbeat_content").length() > 100) {
      throw new IllegalArgumentException("心跳主题与内容合计最多 100 字节");
    }
    if (n.isEmpty() && m.isEmpty()) throw new IllegalArgumentException("没有需要保存的配置项");
    this.network = Collections.unmodifiableMap(n);
    this.mqtt = Collections.unmodifiableMap(m);
  }

  public static Map<String, String> emptyMqtt() {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : Protocol.MQTT_KEYS)
      values.put(key, key.equals("heartbeat_interval") ? "0" : "");
    return values;
  }

  private static JSONObject object(String text) {
    if (text == null || text.length() > 16384)
      throw new IllegalArgumentException("二维码内容为空或超过 16 KiB");
    try {
      JSONTokener tokener = new JSONTokener(text);
      Object value = tokener.nextValue();
      if (!(value instanceof JSONObject) || tokener.nextClean() != 0)
        throw new JSONException("invalid object");
      return (JSONObject) value;
    } catch (JSONException e) {
      throw new IllegalArgumentException("二维码必须是一个合法的 JSON 对象");
    }
  }

  public static String device(String text) {
    JSONObject value = object(text);
    if (value.length() != 1 || !(value.opt("mac") instanceof String))
      throw new IllegalArgumentException("设备二维码格式须为 {\"mac\":\"0090E2D72060\"}");
    return Protocol.mac((String) value.opt("mac"));
  }

  public static QrConfig configuration(String text) {
    JSONObject value = object(text);
    Iterator<String> keys = value.keys();
    while (keys.hasNext())
      if (!java.util.Arrays.asList("network", "mqtt").contains(keys.next()))
        throw new IllegalArgumentException("配置码仅支持 network 和 mqtt 两个对象");
    return new QrConfig(
        section(value, "network", Protocol.NETWORK_KEYS),
        section(value, "mqtt", Protocol.MQTT_KEYS));
  }

  private static Map<String, String> section(JSONObject root, String name, List<String> allowed) {
    Map<String, String> result = new LinkedHashMap<>();
    if (!root.has(name)) return result;
    Object value = root.opt(name);
    if (!(value instanceof JSONObject)) throw new IllegalArgumentException(name + " 须为 JSON 对象");
    JSONObject object = (JSONObject) value;
    Iterator<String> keys = object.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!allowed.contains(key)) throw new IllegalArgumentException("不支持的字段：" + name + "." + key);
      Object v = object.opt(key);
      boolean numeric =
          name.equals("network")
                  && java.util.Arrays.asList("mode", "port", "dhcp", "heartbeat").contains(key)
              || name.equals("mqtt") && key.equals("heartbeat_interval");
      if (!(v instanceof String) && !(numeric && (v instanceof Integer || v instanceof Long)))
        throw new IllegalArgumentException(name + "." + key + " 类型错误或为 null");
      result.put(key, String.valueOf(v));
    }
    return result;
  }

  public Map<String, String> mergeMqtt(Map<String, String> original) {
    Map<String, String> merged = new LinkedHashMap<>(original);
    merged.putAll(mqtt);
    return Protocol.validateMqtt(merged);
  }
}
