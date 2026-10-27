package com.corxnet.config.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DeviceRepositoryTest {
  private static final String MAC = "0090E2D72060";
  private static final String ADDRESS = "127.0.0.1:60000";

  private static class Simulator implements DeviceRepository.Transport {
    final ArrayDeque<DeviceRepository.Packet> replies = new ArrayDeque<>();
    final List<byte[]> sent = new ArrayList<>();
    final List<Integer> networkCommands = new ArrayList<>();
    Map<String, String> mqtt;
    byte[] network;
    int writes;
    String mode = "success";

    Simulator() throws Exception {
      mqtt = ProtocolTest.strings(ProtocolTest.fixtures().getJSONObject("mqtt_config"));
      network = ProtocolTest.hex(ProtocolTest.fixtures().getString("network_response"));
    }

    void reply(byte[] bytes) {
      replies.add(new DeviceRepository.Packet(bytes, ADDRESS));
    }

    byte[] mqttResponse() {
      byte[] p = Protocol.mqttSave(MAC, mqtt);
      p[0] = 0x33;
      p[1] = (byte) 0xbb;
      p[621] = (byte) 0xbb;
      p[622] = 0x33;
      return p;
    }

    @Override
    public void send(byte[] frame) {
      sent.add(frame.clone());
      if (frame.length == 16) {
        assertArrayEquals(ProtocolTest.hex("33bb303039304532443732303630bb33"), frame);
        if (!mode.equals("pre-read-failure")) {
          byte[] wrong = mqttResponse();
          wrong[13] = '1';
          reply(wrong);
          reply(mqttResponse());
        }
      } else if (frame.length == 623) {
        writes++;
        byte[] response = frame.clone();
        response[0] = 0x33;
        response[1] = (byte) 0xbb;
        response[621] = (byte) 0xbb;
        response[622] = 0x33;
        mqtt = Protocol.parseMqtt(MAC, response);
        if (mode.equals("restore-failure") || mode.equals("mismatch") && writes == 1)
          mqtt.put("heartbeat_content", "wrong");
        if (!mode.equals("ack-lost")) reply(new byte[] {0x44, (byte) 0xaa, (byte) 0xaa, 0x44});
      } else if (frame[0] == 10) reply(network.clone());
      else if (frame[0] != -1) {
        int command = frame[0] & 255;
        networkCommands.add(command);
        if (mode.equals("network-failure") && command == 5) return;
        switch (command) {
          case 1:
            System.arraycopy(frame, 7, network, 40, 4);
            break;
          case 4:
            network[0] = frame[7];
            break;
          case 5:
            network[8] = frame[7];
            network[9] = frame[8];
            break;
          case 9:
            network[254] = frame[7];
            break;
          case 248:
            System.arraycopy(frame, 9, network, 56, 4);
            System.arraycopy(frame, 7, network, 60, 2);
            break;
          default:
            break;
        }
        reply(new byte[] {frame[0], 1, 13, 10});
      }
    }

    @Override
    public DeviceRepository.Packet receive(long deadline) throws IOException {
      if (replies.isEmpty()) throw new SocketTimeoutException();
      return replies.remove();
    }

    @Override
    public void close() {}
  }

  @Test
  public void discoveryMatchesGoFramesFiltersAndDeduplicates() throws Exception {
    byte[] expected = ProtocolTest.hex(ProtocolTest.fixtures().getString("scan_request"));
    byte[] response = ProtocolTest.hex(ProtocolTest.fixtures().getString("scan_response"));
    java.util.ArrayDeque<DeviceRepository.Packet> packets = new java.util.ArrayDeque<>();
    packets.add(new DeviceRepository.Packet(new byte[] {1}, "invalid"));
    packets.add(new DeviceRepository.Packet(response, "192.168.0.18:60000"));
    packets.add(new DeviceRepository.Packet(response, "192.168.0.18:60000"));
    packets.add(
        new DeviceRepository.Packet(
            new byte[] {0, 1, 0x11, 0x22, 0x33, 0x44, 0x55, 0}, "192.168.0.19:60000"));
    boolean[] closed = {false};
    DeviceRepository.Transport transport =
        new DeviceRepository.Transport() {
          @Override
          public void send(byte[] frame) {
            assertArrayEquals(expected, frame);
          }

          @Override
          public DeviceRepository.Packet receive(long deadline) throws IOException {
            if (packets.isEmpty()) throw new SocketTimeoutException();
            return packets.remove();
          }

          @Override
          public void close() {
            closed[0] = true;
          }
        };
    List<DeviceRepository.DiscoveredDevice> devices =
        new DeviceRepository(() -> transport).discover();
    assertEquals(2, devices.size());
    assertEquals("0090E2D72060", devices.get(0).mac);
    assertEquals("192.168.0.18:60000", devices.get(0).address);
    assertEquals("001122334455", devices.get(1).mac);
    assertTrue(closed[0]);
  }

  @Test
  public void emptyDiscoveryReturnsEmptyList() throws Exception {
    DeviceRepository.Transport transport =
        new DeviceRepository.Transport() {
          @Override
          public void send(byte[] frame) {
            assertArrayEquals(new byte[5], frame);
          }

          @Override
          public DeviceRepository.Packet receive(long deadline) throws IOException {
            throw new SocketTimeoutException();
          }

          @Override
          public void close() {}
        };
    assertTrue(new DeviceRepository(() -> transport).discover().isEmpty());
  }

  @Test
  public void readsWithTargetFiltering() throws Exception {
    Simulator s = new Simulator();
    DeviceRepository repository = new DeviceRepository(() -> s);
    assertEquals(s.mqtt, repository.readMqtt(MAC).values);
    assertEquals("50000", repository.readNetwork(MAC).values.get("port"));
  }

  @Test
  public void mqttReadbackRestoresAllFieldsAndHandlesLostAck() throws Exception {
    for (String mode :
        List.of("success", "ack-lost", "mismatch", "restore-failure", "pre-read-failure")) {
      Simulator s = new Simulator();
      s.mode = mode;
      Map<String, String> before = new LinkedHashMap<>(s.mqtt);
      DeviceRepository repository = new DeviceRepository(() -> s);
      DeviceRepository.SaveResult result =
          repository.save(
              MAC,
              new QrConfig(
                  Map.of(),
                  Map.of(
                      "heartbeat_topic",
                      "new/topic",
                      "heartbeat_content",
                      "new-body",
                      "heartbeat_interval",
                      "60")));
      if (List.of("success", "ack-lost").contains(mode)) {
        assertTrue(mode + ":" + result.message, result.verified);
        assertEquals(1, s.writes);
        assertEquals(before.get("password"), result.mqtt.get("password"));
        assertEquals("60", result.mqtt.get("heartbeat_interval"));
      } else {
        assertFalse(mode, result.verified);
        if (mode.equals("pre-read-failure")) assertEquals(0, s.writes);
        else assertEquals(2, s.writes);
        if (mode.equals("mismatch")) {
          assertEquals(before, s.mqtt);
          assertTrue(result.message.contains("已恢复"));
        }
        if (mode.equals("restore-failure")) assertTrue(result.message.contains("恢复也未确认"));
      }
    }
  }

  @Test
  public void validatesMergedConfigBeforeAnyWrite() throws Exception {
    Simulator s = new Simulator();
    s.mqtt.put("heartbeat_topic", "t".repeat(100));
    s.mqtt.put("heartbeat_content", "");
    DeviceRepository.SaveResult result =
        new DeviceRepository(() -> s)
            .save(MAC, new QrConfig(Map.of("port", "1883"), Map.of("heartbeat_content", "x")));
    assertFalse(result.verified);
    assertEquals(0, s.writes);
    assertTrue(s.networkCommands.isEmpty());
  }

  @Test
  public void savesMqttBeforeDisruptiveNetworkSettingsAndUpdatesMac() throws Exception {
    Simulator s = new Simulator();
    DeviceRepository repository = new DeviceRepository(() -> s);
    DeviceRepository.SaveResult result =
        repository.save(
            MAC,
            new QrConfig(
                Map.of("mac", "0090E2D72061", "ip", "192.168.0.19", "dhcp", "0", "mode", "7"),
                Map.of("device_id", "new-device")));
    assertTrue(result.message, result.verified);
    assertEquals(List.of(4, 9, 1, 248), s.networkCommands);
    assertEquals("0090E2D72061", result.mac);
    int mqttWrite = -1, firstNetworkWrite = -1;
    for (int i = 0; i < s.sent.size(); i++) {
      if (s.sent.get(i).length == 623) mqttWrite = i;
      if ((s.sent.get(i)[0] & 255) == 4) firstNetworkWrite = i;
    }
    assertTrue(mqttWrite >= 0 && mqttWrite < firstNetworkWrite);
  }

  @Test
  public void reportsPartialSuccessInsteadOfAtomicSuccess() throws Exception {
    Simulator s = new Simulator();
    s.mode = "network-failure";
    DeviceRepository.SaveResult result =
        new DeviceRepository(() -> s)
            .save(MAC, new QrConfig(Map.of("port", "1883"), Map.of("device_id", "new-device")));
    assertFalse(result.verified);
    assertTrue(result.completed.get(0).startsWith("MQTT"));
    assertTrue(result.message.contains("端口"));
    assertEquals("new-device", s.mqtt.get("device_id"));
  }
}
