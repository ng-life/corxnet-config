package com.corxnet.config;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import com.corxnet.config.core.DeviceRepository;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/** 每个会话绑定实际 Wi-Fi 网络，不改变全局路由，避免移动数据/VPN 截走广播。 */
final class WifiTransport implements DeviceRepository.Transport {
  private final DatagramSocket socket;
  private final WifiManager.MulticastLock lock;

  WifiTransport(Context context) throws IOException {
    ConnectivityManager cm =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    Network wifi = null;
    for (Network network : cm.getAllNetworks()) {
      NetworkCapabilities caps = cm.getNetworkCapabilities(network);
      LinkProperties links = cm.getLinkProperties(network);
      if (caps == null
          || links == null
          || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
          || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue;
      for (LinkAddress link : links.getLinkAddresses()) {
        if (link.getAddress() instanceof Inet4Address) {
          wifi = network;
          break;
        }
      }
      if (wifi != null) break;
    }
    if (wifi == null) throw new IOException("请先连接与控制器相同局域网的 Wi-Fi（无需互联网）");
    WifiManager manager =
        (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    lock = manager.createMulticastLock("corxnet-device-config");
    lock.setReferenceCounted(false);
    socket = new DatagramSocket(null);
    try {
      socket.setBroadcast(true);
      socket.setReuseAddress(false);
      wifi.bindSocket(socket);
      // Wi-Fi 网络绑定负责选路；接收端必须绑定通配地址，兼容设备的广播回复。
      // 绑定具体 Wi-Fi IP 会在部分系统上丢弃目标地址为广播地址的数据包。
      socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 60001));
      lock.acquire();
    } catch (IOException | RuntimeException e) {
      socket.close();
      if (lock.isHeld()) lock.release();
      throw new IOException("无法绑定 Wi-Fi UDP 60001，请确认没有其他配置程序占用该端口", e);
    }
  }

  @Override
  public void send(byte[] frame) throws IOException {
    socket.send(
        new DatagramPacket(frame, frame.length, InetAddress.getByName("255.255.255.255"), 60000));
  }

  @Override
  public DeviceRepository.Packet receive(long deadlineNanos) throws IOException {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) throw new SocketTimeoutException();
    socket.setSoTimeout((int) Math.max(1, Math.min(remaining / 1_000_000L, Integer.MAX_VALUE)));
    byte[] buffer = new byte[2048];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    socket.receive(packet);
    return new DeviceRepository.Packet(
        Arrays.copyOf(buffer, packet.getLength()),
        packet.getAddress().getHostAddress() + ":" + packet.getPort());
  }

  @Override
  public synchronized void close() {
    socket.close();
    if (lock.isHeld()) lock.release();
  }
}
