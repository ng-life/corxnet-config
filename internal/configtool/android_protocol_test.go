package configtool

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

// Android JVM 测试消费同一组不含真实凭据的样本；Go 端保证样本不偏离 CLI。
func TestAndroidProtocolFixtures(t *testing.T) {
	data, err := os.ReadFile("../../android/app/src/test/resources/go-protocol-fixtures.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		ScanRequest     string        `json:"scan_request"`
		ScanResponse    string        `json:"scan_response"`
		MAC             string        `json:"mac"`
		NetworkRead     string        `json:"network_read"`
		NetworkSave     string        `json:"network_save"`
		MQTTRead        string        `json:"mqtt_read"`
		MQTTSave        string        `json:"mqtt_save"`
		MQTTResponse    string        `json:"mqtt_response"`
		MQTTConfig      MQTTConfig    `json:"mqtt_config"`
		NetworkResponse string        `json:"network_response"`
		NetworkConfig   NetworkConfig `json:"network_config"`
		Settings        []struct {
			Key, Value, Payload, Frame string
		} `json:"settings"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	mac, err := parseMAC(fixture.MAC)
	if err != nil {
		t.Fatal(err)
	}
	check := func(name string, got []byte, want string) {
		t.Helper()
		if hex.EncodeToString(got) != want {
			t.Fatalf("%s differs from shared Android fixture", name)
		}
	}
	check("scan", make([]byte, 5), fixture.ScanRequest)
	check("scan response", append(append([]byte{0, 1}, mac[1:]...), 0), fixture.ScanResponse)
	check("network read", readPacket(mac), fixture.NetworkRead)
	check("network save", savePacket(mac), fixture.NetworkSave)
	check("MQTT read", mqttReadPacket(mac), fixture.MQTTRead)
	for _, setting := range fixture.Settings {
		payload, err := makeSetting(setting.Key, []string{setting.Value})
		if err != nil {
			t.Fatal(err)
		}
		check(setting.Key, payload, setting.Payload)
		check(setting.Key+" frame", networkPacket(mac, payload), setting.Frame)
	}
	frame, err := encodeMQTT(mac, fixture.MQTTConfig)
	if err != nil {
		t.Fatal(err)
	}
	check("MQTT save", frame, fixture.MQTTSave)
	response, err := hex.DecodeString(fixture.MQTTResponse)
	if err != nil {
		t.Fatal(err)
	}
	config, err := parseMQTT(response, mac)
	if err != nil || config != fixture.MQTTConfig || !bytes.Equal(response[14:621], frame[14:621]) {
		t.Fatal("MQTT response differs from shared Android fixture")
	}
	network, err := hex.DecodeString(fixture.NetworkResponse)
	if err != nil || len(network) != 256 || parseNetworkConfig(network) != fixture.NetworkConfig {
		t.Fatal("network response differs from shared Android fixture")
	}
}
