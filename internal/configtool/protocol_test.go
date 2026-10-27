package configtool

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
	"unicode/utf8"
)

var testMAC = MAC{0, 0x90, 0xe2, 0xd7, 0x20, 0x60}

func sampleMQTT() MQTTConfig {
	return MQTTConfig{Username: "user-example", Password: "not-a-real-password;hmacsha256", SubscribeTopic: "SFQ257YC4K/WH-01/control", PublishTopic: "SFQ257YC4K/WH-01/event", DeviceID: "SFQ257YC4KWH-01", HeartbeatTopic: "ping", HeartbeatContent: "alive", HeartbeatInterval: 30}
}
func mqttResponse(c MQTTConfig) []byte {
	// 独立按文档构造响应，不调用被测编码器。
	p := make([]byte, 623)
	p[0], p[1], p[621], p[622] = 0x44, 0xaa, 0xaa, 0x44
	copy(p[2:14], "0090E2D72060")
	for i, v := range []string{c.Username, c.Password, c.SubscribeTopic, c.PublishTopic, c.DeviceID} {
		offset := 14 + i*101
		p[offset] = byte(len(v))
		copy(p[offset+1:offset+101], v)
	}
	p[519], p[520] = byte(len(c.HeartbeatTopic)), byte(c.HeartbeatInterval)
	copy(p[521:621], c.HeartbeatTopic+c.HeartbeatContent)
	return p
}
func TestDocumentedPackets(t *testing.T) {
	cases := []struct {
		key    string
		values []string
		want   []byte
	}{
		{"ip", []string{"192.168.0.18"}, []byte{1, 192, 168, 0, 18}}, {"target", []string{"1.2.3.4"}, []byte{2, 1, 2, 3, 4}}, {"gateway", []string{"1.2.3.4"}, []byte{3, 1, 2, 3, 4}}, {"netmask", []string{"255.255.255.0"}, []byte{7, 255, 255, 255, 0}}, {"dns", []string{"1.2.3.4"}, []byte{25, 1, 2, 3, 4}}, {"mode", []string{"9"}, []byte{4, 9, 255, 255}}, {"port", []string{"50000"}, []byte{5, 0x50, 0xc3, 255, 255}}, {"dhcp", []string{"1"}, []byte{9, 1, 255, 255}}, {"heartbeat", []string{"0"}, []byte{12, 0}}, {"mac", []string{"00:90:E2:D7:20:60"}, []byte{248, 0, 0x90, 0xe2, 0xd7, 0x20, 0x60}}, {"id", []string{"sensor", "west"}, append([]byte{18, 11}, []byte("sensor west")...)}, {"hostname", []string{"broker"}, []byte{32, 'b', 'r', 'o', 'k', 'e', 'r'}},
	}
	for _, c := range cases {
		t.Run(c.key, func(t *testing.T) {
			p, e := makeSetting(c.key, c.values)
			if e != nil || !bytes.Equal(p, c.want) {
				t.Fatalf("%X %v", p, e)
			}
			frame := networkPacket(testMAC, p)
			if !bytes.Equal(frame[1:6], testMAC[1:]) || !bytes.Equal(frame[6:], c.want) {
				t.Fatal("address or payload")
			}
		})
	}
	if !bytes.Equal(readPacket(testMAC), []byte{10, 0x90, 0xe2, 0xd7, 0x20, 0x60, 10, 255, 255, 255, 255}) {
		t.Fatal("read frame")
	}
	if !bytes.Equal(savePacket(testMAC), []byte{255, 0x90, 0xe2, 0xd7, 0x20, 0x60, 255, 255, 255, 255}) {
		t.Fatal("save frame")
	}
	for _, c := range []struct{ k, v string }{{"port", "0"}, {"port", "65536"}, {"mode", "10"}, {"dhcp", "2"}, {"heartbeat", "-1"}, {"ip", "::1"}, {"id", strings.Repeat("中", 5)}, {"hostname", strings.Repeat("x", 57)}, {"probe", "0"}} {
		if _, e := makeSetting(c.k, []string{c.v}); e == nil {
			t.Fatalf("accepted %s", c.k)
		}
	}
}
func TestMAC(t *testing.T) {
	for _, s := range []string{"0090e2d72060", "00:90:E2:D7:20:60", "00-90-E2-D7-20-60"} {
		m, e := parseMAC(s)
		if e != nil || m != testMAC {
			t.Fatal(s)
		}
	}
	for _, s := range []string{"90:E2:D7:20:60", "00:90:E2:D7:20:GG", "00::90:E2:D7:20:60", "0:090:E2:D7:20:60"} {
		if _, e := parseMAC(s); e == nil {
			t.Fatal(s)
		}
	}
}
func TestMQTTLayoutAndValidation(t *testing.T) {
	c := sampleMQTT()
	p, e := encodeMQTT(testMAC, c)
	if e != nil || len(p) != 623 {
		t.Fatal(e)
	}
	if string(p[2:14]) != "0090E2D72060" || p[14] != 12 || p[115] != 30 || p[216] != 24 || p[317] != 22 || p[418] != 15 || p[519] != 4 || p[520] != 30 || string(p[521:530]) != "pingalive" || p[621] != 170 || p[622] != 68 {
		t.Fatal("capture offsets")
	}
	if !bytes.Equal(mqttReadPacket(), []byte{51, 187, 187, 51}) {
		t.Fatal("read")
	}
	if !bytes.Equal(p, mqttResponse(c)) {
		t.Fatal("encoded frame differs from document fixture")
	}
	got, e := parseMQTT(mqttResponse(c), testMAC)
	if e != nil || got != c {
		t.Fatal(e)
	}
	for _, offset := range []int{0, 2, 14, 114, 519, 621, 622} {
		bad := mqttResponse(c)
		bad[offset] = 0xfe
		if _, e := parseMQTT(bad, testMAC); e == nil {
			t.Fatalf("accepted bad offset %d", offset)
		}
	}
	for _, v := range []string{strings.Repeat("x", 101), "a\x00b", "中文"} {
		bad := c
		bad.Username = v
		if _, e := encodeMQTT(testMAC, bad); e == nil {
			t.Fatal("accepted bad field")
		}
	}
	for _, s := range []string{`{}`, `{"username":""}`, `{"username":null,"password":"","subscribe_topic":"","publish_topic":"","device_id":""}`, `{"username":"","password":"","subscribe_topic":"","publish_topic":"","device_id":"","other":1}`} {
		var c MQTTConfig
		if json.Unmarshal([]byte(s), &c) == nil {
			t.Fatal("accepted partial MQTT config")
		}
	}
}
func TestNetworkOffsets(t *testing.T) {
	b := make([]byte, 256)
	b[8], b[9] = 0x50, 0xc3
	copy(b[40:], []byte{192, 168, 1, 5})
	copy(b[56:], testMAC[2:])
	copy(b[60:], testMAC[:2])
	b[130] = 255
	copy(b[131:], "broker")
	b[180] = 255
	b[254] = 1
	b[252] = 2
	c := parseNetworkConfig(b)
	if c.Port != 50000 || c.IP != "192.168.1.5" || c.MAC != testMAC.String() || c.DHCP != 1 || c.Heartbeat != 0 || utf8.RuneCountInString(c.Hostname) != 56 || len(c.ID) != 12 {
		t.Fatalf("%+v", c)
	}
}

func TestMQTTDocumentBoundaries(t *testing.T) {
	for _, c := range []MQTTConfig{
		{},
		{DeviceID: strings.Repeat("i", 100), HeartbeatTopic: strings.Repeat("t", 40), HeartbeatContent: strings.Repeat("c", 60), HeartbeatInterval: 255},
		{HeartbeatTopic: strings.Repeat("t", 100)},
		{HeartbeatContent: "body-only", HeartbeatInterval: 1},
	} {
		p, err := encodeMQTT(testMAC, c)
		if err != nil {
			t.Fatal(err)
		}
		got, err := parseMQTT(mqttResponse(c), testMAC)
		if err != nil || got != c {
			t.Fatalf("round trip: %v", err)
		}
		if !bytes.Equal(p, mqttResponse(c)) {
			t.Fatal("document fixture mismatch")
		}
	}
	for _, c := range []MQTTConfig{
		{DeviceID: strings.Repeat("i", 101)},
		{HeartbeatTopic: strings.Repeat("t", 60), HeartbeatContent: strings.Repeat("c", 41)},
		{HeartbeatInterval: -1}, {HeartbeatInterval: 256},
		{HeartbeatContent: "a\x00b"}, {HeartbeatTopic: "中文"},
	} {
		if _, err := encodeMQTT(testMAC, c); err == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
	for _, offset := range []int{519, 521, 530} {
		p := mqttResponse(sampleMQTT())
		p[offset] = 255
		if _, err := parseMQTT(p, testMAC); err == nil {
			t.Fatalf("invalid heartbeat at %d accepted", offset)
		}
	}
	// 旧版带读取帧头的响应不再作为文档协议接受。
	p := mqttResponse(sampleMQTT())
	p[0], p[1], p[621], p[622] = 0x33, 0xbb, 0xbb, 0x33
	if _, err := parseMQTT(p, testMAC); err == nil {
		t.Fatal("legacy response accepted")
	}
}

func TestMQTTCompleteJSON(t *testing.T) {
	original := sampleMQTT()
	data, err := json.Marshal(original)
	if err != nil {
		t.Fatal(err)
	}
	var decoded MQTTConfig
	if err = json.Unmarshal(data, &decoded); err != nil || decoded != original {
		t.Fatal("complete fields", err)
	}
	var fields map[string]json.RawMessage
	if err = json.Unmarshal(data, &fields); err != nil {
		t.Fatal(err)
	}
	for key := range fields {
		missing := map[string]json.RawMessage{}
		for k, v := range fields {
			if k != key {
				missing[k] = v
			}
		}
		partial, _ := json.Marshal(missing)
		if err = json.Unmarshal(partial, &decoded); err == nil {
			t.Fatalf("missing %s accepted", key)
		}
	}
	for _, value := range []string{"null", "1.5", "\"30\""} {
		fields["heartbeat_interval"] = json.RawMessage(value)
		invalid, _ := json.Marshal(fields)
		if err = json.Unmarshal(invalid, &decoded); err == nil {
			t.Fatalf("invalid interval %s accepted", value)
		}
	}
}
