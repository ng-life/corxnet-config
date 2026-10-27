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
	Username       string `json:"username"`
	Password       string `json:"password"`
	SubscribeTopic string `json:"subscribe_topic"`
	PublishTopic   string `json:"publish_topic"`
	DeviceID       string `json:"device_id"`
}

var mqttSlots = []int{101, 101, 101, 101, 102}

func (c MQTTConfig) values() []string {
	return []string{c.Username, c.Password, c.SubscribeTopic, c.PublishTopic, c.DeviceID}
}
func mqttReadPacket(m MAC) []byte {
	p := []byte{0x33, 0xbb}
	p = append(p, fmt.Sprintf("%X", m[:])...)
	return append(p, 0xbb, 0x33)
}
func encodeMQTT(m MAC, c MQTTConfig) ([]byte, error) {
	p := make([]byte, 623)
	p[0], p[1] = 0x44, 0xaa
	copy(p[2:14], fmt.Sprintf("%X", m[:]))
	offset := 14
	for i, v := range c.values() {
		if len(v) > mqttSlots[i]-1 {
			return nil, fmt.Errorf("MQTT 第 %d 个字段最多允许 %d 字节", i+1, mqttSlots[i]-1)
		}
		for _, b := range []byte(v) {
			if b == 0 || b > 127 {
				return nil, fmt.Errorf("MQTT 参数只支持不含 NUL 的 ASCII 字符")
			}
		}
		p[offset] = byte(len(v))
		copy(p[offset+1:], v)
		offset += mqttSlots[i]
	}
	p[520], p[621], p[622] = 255, 0xaa, 0x44
	return p, nil
}
func parseMQTT(p []byte, m MAC) (MQTTConfig, error) {
	fail := func(s string) (MQTTConfig, error) { return MQTTConfig{}, fmt.Errorf("MQTT 响应%s", s) }
	if len(p) != 623 || !bytes.Equal(p[:2], []byte{0x33, 0xbb}) {
		return fail("长度或帧头不正确")
	}
	if string(p[2:14]) != fmt.Sprintf("%X", m[:]) {
		return fail("中的 MAC 与请求设备不匹配")
	}
	if p[520] != 255 || p[621] != 0xbb || p[622] != 0x33 {
		return fail("保留区或帧尾不正确")
	}
	offset := 14
	v := make([]string, 5)
	for i, slot := range mqttSlots {
		n := int(p[offset])
		if n >= slot {
			return fail("字段长度超出协议范围")
		}
		for _, b := range p[offset+1 : offset+1+n] {
			if b == 0 || b > 127 {
				return fail("字段包含不支持的字符")
			}
		}
		for _, b := range p[offset+1+n : offset+slot] {
			if b != 0 {
				return fail("字段零填充格式不正确")
			}
		}
		v[i] = string(p[offset+1 : offset+1+n])
		offset += slot
	}
	for _, b := range p[521:621] {
		if b != 0 {
			return fail("保留区格式不正确")
		}
	}
	return MQTTConfig{v[0], v[1], v[2], v[3], v[4]}, nil
}
func hexText(b []byte) string {
	p := make([]string, len(b))
	for i, v := range b {
		p[i] = fmt.Sprintf("%02X", v)
	}
	return strings.Join(p, " ")
}

// MQTT 整体替换要求五个字段显式存在；空字符串是合法值。
func (c *MQTTConfig) UnmarshalJSON(data []byte) error {
	type fields struct {
		Username       *string `json:"username"`
		Password       *string `json:"password"`
		SubscribeTopic *string `json:"subscribe_topic"`
		PublishTopic   *string `json:"publish_topic"`
		DeviceID       *string `json:"device_id"`
	}
	var f fields
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if e := d.Decode(&f); e != nil {
		return fmt.Errorf("MQTT 配置字段无效")
	}
	if d.Decode(new(any)) != io.EOF || f.Username == nil || f.Password == nil || f.SubscribeTopic == nil || f.PublishTopic == nil || f.DeviceID == nil {
		return fmt.Errorf("缺少完整 MQTT 配置")
	}
	*c = MQTTConfig{*f.Username, *f.Password, *f.SubscribeTopic, *f.PublishTopic, *f.DeviceID}
	return nil
}
