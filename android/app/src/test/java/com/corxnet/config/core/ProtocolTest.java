package com.corxnet.config.core;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ProtocolTest {
  static JSONObject fixtures() throws Exception {
    try (InputStream input = ProtocolTest.class.getResourceAsStream("/go-protocol-fixtures.json")) {
      assertNotNull(input);
      return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  static byte[] hex(String text) {
    byte[] data = new byte[text.length() / 2];
    for (int i = 0; i < data.length; i++)
      data[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
    return data;
  }

  static Map<String, String> strings(JSONObject object) {
    Map<String, String> values = new LinkedHashMap<>();
    java.util.Iterator<String> keys = object.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      values.put(key, String.valueOf(object.opt(key)));
    }
    return values;
  }

  @Test
  public void matchesGoNetworkFramesAndResponse() throws Exception {
    JSONObject f = fixtures();
    String mac = f.getString("mac");
    assertArrayEquals(hex(f.getString("network_read")), Protocol.networkRead(mac));
    assertArrayEquals(hex(f.getString("network_save")), Protocol.networkSave(mac));
    JSONArray settings = f.getJSONArray("settings");
    for (int i = 0; i < settings.length(); i++) {
      JSONObject c = settings.getJSONObject(i);
      byte[] payload = Protocol.setting(c.getString("key"), c.getString("value"));
      assertArrayEquals(c.getString("key"), hex(c.getString("payload")), payload);
      assertArrayEquals(hex(c.getString("frame")), Protocol.networkFrame(mac, payload));
    }
    Map<String, String> expected = strings(f.getJSONObject("network_config"));
    expected.put("mac", Protocol.mac(expected.get("mac")));
    assertEquals(expected, Protocol.parseNetwork(hex(f.getString("network_response"))));
  }

  @Test
  public void matchesGoMqttFramesAndAllEightFields() throws Exception {
    JSONObject f = fixtures();
    String mac = f.getString("mac");
    Map<String, String> config = strings(f.getJSONObject("mqtt_config"));
    assertArrayEquals(hex(f.getString("mqtt_read")), Protocol.mqttRead(mac));
    assertArrayEquals(hex(f.getString("mqtt_save")), Protocol.mqttSave(mac, config));
    assertEquals(config, Protocol.parseMqtt(mac, hex(f.getString("mqtt_response"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Protocol.parseMqtt(mac, hex(f.getString("mqtt_save"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Protocol.parseMqtt("0090E2D72061", hex(f.getString("mqtt_response"))));
    assertArrayEquals(
        new byte[] {
          0x33,
          (byte) 0xbb,
          'A',
          'B',
          'C',
          'D',
          'E',
          'F',
          '1',
          '2',
          '3',
          '4',
          '5',
          '6',
          (byte) 0xbb,
          0x33
        },
        Protocol.mqttRead("AB:CD:EF:12:34:56"));
  }

  @Test
  public void validatesBoundariesAndMalformedResponses() throws Exception {
    String mac = "0090E2D72060";
    for (int interval : new int[] {0, 255}) {
      Map<String, String> c = QrConfig.emptyMqtt();
      c.put("device_id", "i".repeat(100));
      c.put("heartbeat_topic", "t".repeat(40));
      c.put("heartbeat_content", "c".repeat(60));
      c.put("heartbeat_interval", String.valueOf(interval));
      byte[] response = Protocol.mqttSave(mac, c);
      response[0] = 0x33;
      response[1] = (byte) 0xbb;
      response[621] = (byte) 0xbb;
      response[622] = 0x33;
      assertEquals(c, Protocol.parseMqtt(mac, response));
    }
    Map<String, String> original = strings(fixtures().getJSONObject("mqtt_config"));
    for (Map.Entry<String, String> bad :
        Map.of(
                "device_id",
                "x".repeat(101),
                "username",
                "中文",
                "password",
                "a\0b",
                "heartbeat_interval",
                "256")
            .entrySet()) {
      Map<String, String> c = new LinkedHashMap<>(original);
      c.put(bad.getKey(), bad.getValue());
      assertThrows(IllegalArgumentException.class, () -> Protocol.mqttSave(mac, c));
    }
    Map<String, String> combined = new LinkedHashMap<>(original);
    combined.put("heartbeat_topic", "t".repeat(100));
    combined.put("heartbeat_content", "x");
    assertThrows(IllegalArgumentException.class, () -> Protocol.mqttSave(mac, combined));
    for (int offset : new int[] {0, 2, 14, 114, 519, 530, 621, 622}) {
      byte[] bad = hex(fixtures().getString("mqtt_response"));
      bad[offset] = (byte) 0xfe;
      assertThrows(IllegalArgumentException.class, () -> Protocol.parseMqtt(mac, bad));
    }
    for (String invalid : new String[] {"90E2D72060", "00:90:E2:D7:20:GG", "0:090:E2:D7:20:60"})
      assertThrows(IllegalArgumentException.class, () -> Protocol.mac(invalid));
    assertThrows(IllegalArgumentException.class, () -> Protocol.setting("port", "0"));
    assertThrows(IllegalArgumentException.class, () -> Protocol.setting("mode", "10"));
    assertThrows(IllegalArgumentException.class, () -> Protocol.setting("id", "中文中文中文"));
    assertThrows(IllegalArgumentException.class, () -> Protocol.setting("ip", "192.168.00.1"));
  }
}
