package configtool

import (
	"context"
	"fmt"
	"net"
	"time"
)

const timeout = 1500 * time.Millisecond

var broadcastAddress = &net.UDPAddr{IP: net.IPv4bcast, Port: 60000}

type Device struct {
	Address string `json:"address"`
	MAC     string `json:"mac"`
}

// deviceClient 为 HTTP、CLI 和隔离测试提供相同的设备操作边界。
type deviceClient interface {
	Discover(context.Context) ([]Device, error)
	Fetch(context.Context, MAC) ([]byte, string, error)
	Apply(context.Context, MAC, []byte) (string, error)
	ReadMQTT(context.Context, MAC) (MQTTConfig, string, error)
	WriteMQTT(context.Context, MAC, MQTTConfig) (MQTTConfig, error)
}
type udpClient struct {
	iface string
	dial  func(context.Context) (*udpSocket, error)
}
type udpSocket struct {
	*net.UDPConn
	destination *net.UDPAddr
	stop        func() bool
}

func (s *udpSocket) Close() error {
	if s.stop != nil {
		s.stop()
	}
	return s.UDPConn.Close()
}

func (c udpClient) open(ctx context.Context) (*udpSocket, error) {
	if c.dial != nil {
		return c.dial(ctx)
	}
	ip, e := localIP(c.iface)
	if e != nil {
		return nil, e
	}
	lc := net.ListenConfig{Control: socketControl(c.iface)}
	p, e := lc.ListenPacket(ctx, "udp4", net.JoinHostPort(ip.String(), "60001"))
	if e != nil {
		return nil, fmt.Errorf("绑定 UDP 本地端口 60001 或网卡失败：%w", e)
	}
	s := p.(*net.UDPConn)
	// 取消命令时立即中断收包；关闭连接同时结束取消监听。
	stop := context.AfterFunc(ctx, func() { _ = s.Close() })
	return &udpSocket{UDPConn: s, destination: broadcastAddress, stop: stop}, nil
}
func send(s *udpSocket, p []byte) error {
	_, e := s.WriteToUDP(p, s.destination)
	if e != nil {
		return fmt.Errorf("发送广播失败：%w", e)
	}
	return nil
}
func (c udpClient) Discover(ctx context.Context) ([]Device, error) {
	s, e := c.open(ctx)
	if e != nil {
		return nil, e
	}
	defer s.Close()
	if e = send(s, make([]byte, 5)); e != nil {
		return nil, e
	}
	_ = s.SetReadDeadline(time.Now().Add(timeout))
	devices := []Device{}
	seen := map[string]bool{}
	b := make([]byte, 2048)
	for {
		n, a, e := s.ReadFromUDP(b)
		if e != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			if ne, ok := e.(net.Error); ok && ne.Timeout() {
				return devices, nil
			}
			return nil, e
		}
		if n >= 8 && b[0] == 0 && b[1] == 1 {
			m := MAC{}
			copy(m[1:], b[2:7])
			text := m.String()
			if !seen[text] {
				devices = append(devices, Device{a.String(), text})
				seen[text] = true
			}
		}
	}
}
func (c udpClient) Fetch(ctx context.Context, m MAC) ([]byte, string, error) {
	s, e := c.open(ctx)
	if e != nil {
		return nil, "", e
	}
	defer s.Close()
	if e = send(s, readPacket(m)); e != nil {
		return nil, "", e
	}
	_ = s.SetReadDeadline(time.Now().Add(timeout))
	b := make([]byte, 2048)
	for {
		n, a, e := s.ReadFromUDP(b)
		if e != nil {
			return nil, "", fmt.Errorf("读取超时或接收失败：%w", e)
		}
		if n >= 256 {
			actual := responseMAC(b)
			if string(actual[1:]) == string(m[1:]) {
				return append([]byte(nil), b[:n]...), a.String(), nil
			}
		}
	}
}
func (c udpClient) Apply(ctx context.Context, m MAC, p []byte) (string, error) {
	s, e := c.open(ctx)
	if e != nil {
		return "", e
	}
	defer s.Close()
	if e = send(s, networkPacket(m, p)); e != nil {
		return "", e
	}
	_ = s.SetReadDeadline(time.Now().Add(timeout))
	_, _, e = s.ReadFromUDP(make([]byte, 512))
	ack := e == nil
	if e != nil {
		if ne, ok := e.(net.Error); !ok || !ne.Timeout() {
			return "", e
		}
	}
	if ctx.Err() != nil {
		return "", ctx.Err()
	}
	if e = send(s, savePacket(m)); e != nil {
		return "", e
	}
	if ack {
		return "保存命令已发送，请重新读取核对。", nil
	}
	return "配置响应超时；保存命令已发送，请重新读取核对。", nil
}
func receiveMQTT(s *udpSocket, m MAC) (MQTTConfig, string, error) {
	if e := send(s, mqttReadPacket(m)); e != nil {
		return MQTTConfig{}, "", e
	}
	_ = s.SetReadDeadline(time.Now().Add(timeout))
	b := make([]byte, 2048)
	var invalid error
	for {
		n, a, e := s.ReadFromUDP(b)
		if e != nil {
			if invalid != nil {
				return MQTTConfig{}, "", fmt.Errorf("读取 MQTT 参数超时；%w", invalid)
			}
			return MQTTConfig{}, "", fmt.Errorf("读取 MQTT 参数超时或接收失败：%w", e)
		}
		c, e := parseMQTT(b[:n], m)
		if e == nil {
			return c, a.String(), nil
		}
		invalid = e
	}
}
func (c udpClient) ReadMQTT(ctx context.Context, m MAC) (MQTTConfig, string, error) {
	s, e := c.open(ctx)
	if e != nil {
		return MQTTConfig{}, "", e
	}
	defer s.Close()
	return receiveMQTT(s, m)
}
func (c udpClient) WriteMQTT(ctx context.Context, m MAC, wanted MQTTConfig) (MQTTConfig, error) {
	p, e := encodeMQTT(m, wanted)
	if e != nil {
		return MQTTConfig{}, e
	}
	s, e := c.open(ctx)
	if e != nil {
		return MQTTConfig{}, e
	}
	defer s.Close()
	// 写前必须成功读取，原值只保留在内存。写后核验失败则尝试恢复并复读。
	original, _, e := receiveMQTT(s, m)
	if e != nil {
		return MQTTConfig{}, e
	}
	if ctx.Err() != nil {
		return MQTTConfig{}, ctx.Err()
	}
	if e = send(s, p); e != nil {
		return MQTTConfig{}, e
	}
	actual, _, readErr := receiveMQTT(s, m)
	if readErr == nil && actual == wanted {
		return actual, nil
	}
	recovery, _ := encodeMQTT(m, original)
	if e = send(s, recovery); e == nil {
		restored, _, err := receiveMQTT(s, m)
		if err == nil && restored == original {
			return MQTTConfig{}, fmt.Errorf("保存后复读核验失败；已恢复写入前原值并复读确认")
		}
	}
	return MQTTConfig{}, fmt.Errorf("保存后复读核验失败，恢复也未确认；请重新读取检查设备配置")
}
