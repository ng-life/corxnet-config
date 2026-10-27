package configtool

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/netip"
	"strconv"
	"strings"
)

type MAC [6]byte

func parseMAC(s string) (m MAC, err error) {
	text := strings.ReplaceAll(strings.ReplaceAll(s, ":", ""), "-", "")
	if len(text) != 12 {
		return m, fmt.Errorf("MAC 地址格式应为 00:11:22:33:44:55 或 12 位十六进制")
	}
	if strings.ContainsAny(s, ":-") {
		parts := strings.Split(strings.ReplaceAll(s, "-", ":"), ":")
		if len(parts) != 6 {
			return m, fmt.Errorf("MAC 地址必须包含六个字节")
		}
		for _, p := range parts {
			if len(p) != 2 {
				return m, fmt.Errorf("MAC 地址每字节须为两位十六进制")
			}
		}
	}
	b, e := hex.DecodeString(text)
	if e != nil {
		return m, fmt.Errorf("MAC 地址包含无效字节")
	}
	copy(m[:], b)
	return m, nil
}
func (m MAC) String() string { return formatMAC(m[:]) }
func formatMAC(b []byte) string {
	p := make([]string, len(b))
	for i, v := range b {
		p[i] = fmt.Sprintf("%02X", v)
	}
	return strings.Join(p, ":")
}
func networkPacket(m MAC, payload []byte) []byte {
	p := []byte{payload[0]}
	p = append(p, m[1:]...)
	return append(p, payload...)
}
func readPacket(m MAC) []byte { return networkPacket(m, []byte{10, 255, 255, 255, 255}) }

// 保存帧沿用设备样本的十字节格式，与读取帧分别编码。
func savePacket(m MAC) []byte {
	p := append([]byte{255}, m[1:]...)
	return append(p, 255, 255, 255, 255)
}

var settings = []string{"ip", "target", "gateway", "mode", "port", "netmask", "mac", "dhcp", "id", "dns", "hostname", "heartbeat"}

func makeSetting(key string, values []string) ([]byte, error) {
	if len(values) == 0 {
		return nil, fmt.Errorf("缺少参数值")
	}
	if key != "id" && key != "hostname" && len(values) != 1 {
		return nil, fmt.Errorf("此参数只接受一个值")
	}
	v := values[0]
	switch key {
	case "ip", "target", "gateway", "netmask", "dns":
		a, e := netip.ParseAddr(v)
		if e != nil || !a.Is4() {
			return nil, fmt.Errorf("IPv4 地址无效")
		}
		b := a.As4()
		cmd := map[string]byte{"ip": 1, "target": 2, "gateway": 3, "netmask": 7, "dns": 0x19}[key]
		return append([]byte{cmd}, b[:]...), nil
	case "mode":
		n, e := strconv.ParseUint(v, 10, 8)
		if e != nil || n > 9 {
			return nil, fmt.Errorf("模式须为 0 到 9")
		}
		return []byte{4, byte(n), 255, 255}, nil
	case "port":
		n, e := strconv.ParseUint(v, 10, 16)
		if e != nil || n == 0 {
			return nil, fmt.Errorf("端口须为 1 到 65535")
		}
		return []byte{5, byte(n), byte(n >> 8), 255, 255}, nil
	case "dhcp", "heartbeat":
		if v != "0" && v != "1" {
			return nil, fmt.Errorf("值只能是 0 或 1")
		}
		if key == "dhcp" {
			return []byte{9, v[0] - '0', 255, 255}, nil
		}
		return []byte{12, v[0] - '0'}, nil
	case "mac":
		m, e := parseMAC(v)
		if e != nil {
			return nil, e
		}
		return append([]byte{248}, m[:]...), nil
	case "id", "hostname":
		text := strings.Join(values, " ")
		max, cmd := 12, byte(18)
		if key == "hostname" {
			max, cmd = 56, 32
		}
		if len(text) > max {
			return nil, fmt.Errorf("文本最多 %d 字节", max)
		}
		p := []byte{cmd}
		if key == "id" {
			p = append(p, byte(len(text)))
		}
		return append(p, []byte(text)...), nil
	default:
		return nil, fmt.Errorf("参数名无效：%s", key)
	}
}

type NetworkConfig struct {
	Mode      byte   `json:"mode"`
	Port      uint16 `json:"port"`
	IP        string `json:"ip"`
	Target    string `json:"target"`
	Gateway   string `json:"gateway"`
	Netmask   string `json:"netmask"`
	MAC       string `json:"mac"`
	DHCP      byte   `json:"dhcp"`
	Heartbeat byte   `json:"heartbeat"`
	Hostname  string `json:"hostname"`
	ID        string `json:"id"`
}

func responseMAC(b []byte) MAC { return MAC{b[60], b[61], b[56], b[57], b[58], b[59]} }
func parseNetworkConfig(b []byte) NetworkConfig {
	ip := func(i int) string { return netip.AddrFrom4([4]byte(b[i : i+4])).String() }
	c := NetworkConfig{Mode: b[0], Port: binary.LittleEndian.Uint16(b[8:10]), IP: ip(40), Target: ip(44), Gateway: ip(48), Netmask: ip(52), MAC: responseMAC(b).String(), Hostname: strings.ToValidUTF8(string(b[131:131+min(int(b[130]), 56)]), "�"), ID: strings.ToValidUTF8(string(b[181:181+min(int(b[180]), 12)]), "�")}
	if b[254] == 1 {
		c.DHCP = 1
	}
	if b[252] == 1 {
		c.Heartbeat = 1
	}
	return c
}

type MQTTConfig struct {
	Username          string `json:"username"`
	Password          string `json:"password"`
	SubscribeTopic    string `json:"subscribe_topic"`
	PublishTopic      string `json:"publish_topic"`
	DeviceID          string `json:"device_id"`
	HeartbeatTopic    string `json:"heartbeat_topic"`
	HeartbeatContent  string `json:"heartbeat_content"`
	HeartbeatInterval int    `json:"heartbeat_interval"`
}

const mqttFrameSize = 623

func (c MQTTConfig) values() []string {
	return []string{c.Username, c.Password, c.SubscribeTopic, c.PublishTopic, c.DeviceID}
}

func mqttReadPacket() []byte { return []byte{0x33, 0xbb, 0xbb, 0x33} }

func validateMQTTText(name, value string) error {
	if len(value) > 100 {
		return fmt.Errorf("MQTT %s最多允许 100 字节", name)
	}
	for _, b := range []byte(value) {
		if b == 0 || b > 127 {
			return fmt.Errorf("MQTT %s只支持不含 NUL 的 ASCII 字符", name)
		}
	}
	return nil
}

func encodeMQTT(m MAC, c MQTTConfig) ([]byte, error) {
	if c.HeartbeatInterval < 0 || c.HeartbeatInterval > 255 {
		return nil, fmt.Errorf("MQTT 心跳间隔须为 0 到 255 秒")
	}
	p := make([]byte, mqttFrameSize)
	p[0], p[1] = 0x44, 0xaa
	copy(p[2:14], fmt.Sprintf("%X", m[:]))
	offset := 14
	names := []string{"用户名", "密码", "订阅主题", "发布主题", "设备 ID"}
	for i, v := range c.values() {
		if err := validateMQTTText(names[i], v); err != nil {
			return nil, err
		}
		p[offset] = byte(len(v))
		copy(p[offset+1:offset+101], v)
		offset += 101
	}
	// 文档的心跳长度只计主题；主题和内容共用 100 字节，不添加分隔符。
	heartbeat := c.HeartbeatTopic + c.HeartbeatContent
	if err := validateMQTTText("心跳主题与内容合计", heartbeat); err != nil {
		return nil, err
	}
	p[519], p[520] = byte(len(c.HeartbeatTopic)), byte(c.HeartbeatInterval)
	copy(p[521:621], heartbeat)
	p[621], p[622] = 0xaa, 0x44
	return p, nil
}

func parseMQTT(p []byte, m MAC) (MQTTConfig, error) {
	fail := func(s string) (MQTTConfig, error) { return MQTTConfig{}, fmt.Errorf("MQTT 响应%s", s) }
	if len(p) != mqttFrameSize || !bytes.Equal(p[:2], []byte{0x44, 0xaa}) || !bytes.Equal(p[621:], []byte{0xaa, 0x44}) {
		return fail("长度或帧格式不正确")
	}
	if string(p[2:14]) != fmt.Sprintf("%X", m[:]) {
		return fail("中的设备标识与请求设备不匹配")
	}
	offset := 14
	v := make([]string, 5)
	for i := range v {
		n := int(p[offset])
		if n > 100 {
			return fail("字段长度超出协议范围")
		}
		value := string(p[offset+1 : offset+1+n])
		if err := validateMQTTText("字段", value); err != nil {
			return fail("字段包含不支持的字符")
		}
		for _, b := range p[offset+1+n : offset+101] {
			if b != 0 {
				return fail("字段零填充格式不正确")
			}
		}
		v[i] = value
		offset += 101
	}
	heartbeat := bytes.TrimRight(p[521:621], "\x00")
	n := int(p[519])
	if n > len(heartbeat) {
		return fail("心跳主题长度超出内容范围")
	}
	if err := validateMQTTText("心跳主题与内容", string(heartbeat)); err != nil {
		return fail("心跳字段包含不支持的字符")
	}
	return MQTTConfig{
		Username: v[0], Password: v[1], SubscribeTopic: v[2], PublishTopic: v[3], DeviceID: v[4],
		HeartbeatTopic: string(heartbeat[:n]), HeartbeatContent: string(heartbeat[n:]),
		HeartbeatInterval: int(p[520]),
	}, nil
}
func hexText(b []byte) string {
	p := make([]string, len(b))
	for i, v := range b {
		p[i] = fmt.Sprintf("%02X", v)
	}
	return strings.Join(p, " ")
}

// MQTT 整体替换要求全部字段显式存在；空字符串和零间隔是合法值。
func (c *MQTTConfig) UnmarshalJSON(data []byte) error {
	type fields struct {
		Username          *string `json:"username"`
		Password          *string `json:"password"`
		SubscribeTopic    *string `json:"subscribe_topic"`
		PublishTopic      *string `json:"publish_topic"`
		DeviceID          *string `json:"device_id"`
		HeartbeatTopic    *string `json:"heartbeat_topic"`
		HeartbeatContent  *string `json:"heartbeat_content"`
		HeartbeatInterval *int    `json:"heartbeat_interval"`
	}
	var f fields
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if e := d.Decode(&f); e != nil {
		return fmt.Errorf("MQTT 配置字段无效")
	}
	if d.Decode(new(any)) != io.EOF || f.Username == nil || f.Password == nil || f.SubscribeTopic == nil || f.PublishTopic == nil || f.DeviceID == nil || f.HeartbeatTopic == nil || f.HeartbeatContent == nil || f.HeartbeatInterval == nil {
		return fmt.Errorf("缺少完整 MQTT 配置")
	}
	*c = MQTTConfig{
		Username: *f.Username, Password: *f.Password, SubscribeTopic: *f.SubscribeTopic,
		PublishTopic: *f.PublishTopic, DeviceID: *f.DeviceID,
		HeartbeatTopic: *f.HeartbeatTopic, HeartbeatContent: *f.HeartbeatContent,
		HeartbeatInterval: *f.HeartbeatInterval,
	}
	return nil
}
