package com.corxnet.config;

import android.test.ActivityInstrumentationTestCase2;
import com.corxnet.config.core.DeviceRepository;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** 使用模拟器自身 Wi-Fi 验证真实 socket 绑定和收包，不向控制器 60000 端口发送数据。 */
@SuppressWarnings("deprecation")
public final class WifiTransportTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public WifiTransportTest() {
    super(MainActivity.class);
  }

  public void testWildcardBindingReceivesLocalUdpReply() throws Exception {
    MainActivity activity = getActivity();
    try (WifiTransport transport = new WifiTransport(activity)) {
      Field field = WifiTransport.class.getDeclaredField("socket");
      field.setAccessible(true);
      DatagramSocket socket = (DatagramSocket) field.get(transport);
      assertTrue(
          "receiver must accept broadcast replies", socket.getLocalAddress().isAnyLocalAddress());
      assertEquals(60001, socket.getLocalPort());
      assertTrue(socket.getBroadcast());
      // 使用同一内核回环路径核对实际收包；这不证明路由器/真机 Wi-Fi 广播已联调。
      byte[] reply = new byte[] {0, 1, (byte) 0x90, (byte) 0xe2, (byte) 0xd7, 0x20, 0x60, 0};
      try (DatagramSocket sender = new DatagramSocket()) {
        sender.send(
            new DatagramPacket(reply, reply.length, InetAddress.getByName("127.0.0.1"), 60001));
      }
      DeviceRepository.Packet packet = transport.receive(System.nanoTime() + 2_000_000_000L);
      assertTrue(java.util.Arrays.equals(reply, packet.data));
    }
    // 会话关闭后端口应可重新绑定。
    try (DatagramSocket probe = new DatagramSocket(60001)) {
      assertEquals(60001, probe.getLocalPort());
    }
  }
}
