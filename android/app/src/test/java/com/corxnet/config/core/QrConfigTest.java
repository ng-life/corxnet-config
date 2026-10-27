package com.corxnet.config.core;

import static org.junit.Assert.*;

import java.util.Map;
import org.junit.Test;

public class QrConfigTest {
  @Test
  public void readsDeviceQrAndRejectsConfigAsDevice() {
    assertEquals("0090E2D72060", QrConfig.device("{\"mac\":\"0090e2d72060\"}"));
    for (String bad :
        new String[] {
          "{}",
          "[]",
          "{\"mac\":null}",
          "{\"mac\":\"0090E2D72060\",\"network\":{}}",
          "{\"mac\":\"0090E2D72060\"}{}"
        }) {
      assertThrows(IllegalArgumentException.class, () -> QrConfig.device(bad));
    }
  }

  @Test
  public void mergesOnlyExplicitMqttFieldsAndPreservesEmptyValues() {
    QrConfig patch =
        QrConfig.configuration(
            "{\"network\":{\"port\":1883,\"mode\":\"7\"},\"mqtt\":{\"password\":\"\",\"heartbeat_interval\":30}}");
    Map<String, String> before = QrConfig.emptyMqtt();
    before.put("password", "synthetic");
    before.put("username", "retained");
    before.put("heartbeat_content", "alive");
    Map<String, String> after = patch.mergeMqtt(before);
    assertEquals("retained", after.get("username"));
    assertEquals("", after.get("password"));
    assertEquals("alive", after.get("heartbeat_content"));
    assertEquals("30", after.get("heartbeat_interval"));
    assertEquals("1883", patch.network.get("port"));
    assertEquals("synthetic", before.get("password"));
  }

  @Test
  public void rejectsUnknownUnsafeAndInvalidFields() {
    for (String bad :
        new String[] {
          "{}",
          "{\"network\":{}}",
          "{\"command\":\"FF\"}",
          "{\"mac\":\"0090E2D72060\"}",
          "{\"network\":{\"relay\":1}}",
          "{\"mqtt\":{\"password\":null}}",
          "{\"mqtt\":{\"heartbeat_interval\":1.5}}",
          "{\"network\":{\"port\":true}}",
          "{\"mqtt\":{\"heartbeat_interval\":256}}",
          "{\"network\":null}"
        }) {
      assertThrows(bad, IllegalArgumentException.class, () -> QrConfig.configuration(bad));
    }
  }

  @Test
  public void validatesCombinedLengthAfterMerging() {
    Map<String, String> before = QrConfig.emptyMqtt();
    before.put("heartbeat_topic", "t".repeat(100));
    QrConfig patch = new QrConfig(Map.of(), Map.of("heartbeat_content", "x"));
    assertThrows(IllegalArgumentException.class, () -> patch.mergeMqtt(before));
  }
}
