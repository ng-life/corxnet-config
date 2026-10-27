package configtool

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"sync"
	"testing"
	"time"
)

// 仅向 loopback 的随机端口收发；不占用设备端口，不发送真实广播。
func simulatedClient(t *testing.T, handle func([]byte) [][]byte) udpClient {
	t.Helper()
	server, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		b := make([]byte, 2048)
		for {
			n, a, e := server.ReadFromUDP(b)
			if e != nil {
				return
			}
			for _, reply := range handle(append([]byte(nil), b[:n]...)) {
				_, _ = server.WriteToUDP(reply, a)
			}
		}
	}()
	t.Cleanup(func() { _ = server.Close(); <-done })
	return udpClient{dial: func(ctx context.Context) (*udpSocket, error) {
		s, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if e != nil {
			return nil, e
		}
		stop := context.AfterFunc(ctx, func() { _ = s.Close() })
		return &udpSocket{UDPConn: s, destination: server.LocalAddr().(*net.UDPAddr), stop: stop}, nil
	}}
}
func TestUDPScanAndReadFilter(t *testing.T) {
	c := simulatedClient(t, func(p []byte) [][]byte {
		if len(p) == 5 {
			return [][]byte{{0, 1, 0x90, 0xe2, 0xd7, 0x20, 0x60, 0}, {0, 1, 0x90, 0xe2, 0xd7, 0x20, 0x60, 0}, {9}}
		}
		b := make([]byte, 256)
		copy(b[56:], testMAC[2:])
		copy(b[60:], testMAC[:2])
		bad := append([]byte(nil), b...)
		bad[59]++
		return [][]byte{{1}, bad, b}
	})
	ds, e := c.Discover(context.Background())
	if e != nil || len(ds) != 1 || ds[0].MAC != testMAC.String() {
		t.Fatalf("%+v %v", ds, e)
	}
	b, _, e := c.Fetch(context.Background(), testMAC)
	if e != nil || responseMAC(b) != testMAC {
		t.Fatal(e)
	}
}
func TestUDPSaveOrder(t *testing.T) {
	packets := make(chan []byte, 2)
	c := simulatedClient(t, func(p []byte) [][]byte { packets <- p; return [][]byte{{5, 1, 13, 10}} })
	payload, _ := makeSetting("port", []string{"50000"})
	if _, e := c.Apply(context.Background(), testMAC, payload); e != nil {
		t.Fatal(e)
	}
	for _, want := range [][]byte{networkPacket(testMAC, payload), savePacket(testMAC)} {
		select {
		case got := <-packets:
			if !bytes.Equal(got, want) {
				t.Fatalf("%X != %X", got, want)
			}
		case <-time.After(time.Second):
			t.Fatal("missing frame")
		}
	}
}
func TestMQTTWriteReadbackAndRecovery(t *testing.T) {
	for _, mode := range []string{"success", "mismatch", "invalid-pre-read", "restore-failure", "timeout-after-write", "ack-lost"} {
		t.Run(mode, func(t *testing.T) {
			original := sampleMQTT()
			wanted := original
			wanted.DeviceID = "new-device"
			wanted.HeartbeatTopic = "new/ping"
			wanted.HeartbeatContent = "new-body"
			wanted.HeartbeatInterval = 60
			current := original
			writes := 0
			var mu sync.Mutex
			c := simulatedClient(t, func(p []byte) [][]byte {
				mu.Lock()
				defer mu.Unlock()
				if bytes.Equal(p, []byte("\x33\xbb0090E2D72060\xbb\x33")) {
					if mode == "invalid-pre-read" || (mode == "timeout-after-write" && writes == 1) {
						return [][]byte{{0}}
					}
					return [][]byte{mqttResponse(current)}
				}
				if len(p) == 623 {
					writes++
					expected := wanted
					if writes > 1 {
						expected = original
					}
					if !bytes.Equal(p, mqttSaveFixture(expected)) {
						t.Error("write/recovery frame does not preserve all document fields")
					}
					if mode == "success" || mode == "ack-lost" {
						current = wanted
					}
					if mode == "mismatch" || mode == "timeout-after-write" {
						if writes == 1 {
							current.HeartbeatContent = "incorrect"
						} else {
							current = original
						}
					}
					if mode == "restore-failure" {
						current.HeartbeatContent = "incorrect"
					}
					if mode != "ack-lost" {
						return [][]byte{{0x44, 0xaa, 0xaa, 0x44}}
					}
				}
				return nil
			})
			got, e := c.WriteMQTT(context.Background(), testMAC, wanted)
			// 队列中读取响应意味着前面的写入已被模拟设备处理。
			_, _, _ = c.ReadMQTT(context.Background(), testMAC)
			mu.Lock()
			defer mu.Unlock()
			switch mode {
			case "success", "ack-lost":
				if e != nil || got != wanted || writes != 1 {
					t.Fatalf("%v writes=%d", e, writes)
				}
			case "mismatch", "timeout-after-write":
				if e == nil || current != original || writes != 2 {
					t.Fatalf("%v writes=%d", e, writes)
				}
			case "invalid-pre-read":
				if e == nil || writes != 0 {
					t.Fatalf("%v writes=%d", e, writes)
				}
			case "restore-failure":
				if e == nil || writes != 2 {
					t.Fatalf("%v writes=%d", e, writes)
				}
			}
		})
	}
}
func TestCancelReceive(t *testing.T) {
	c := simulatedClient(t, func([]byte) [][]byte { return nil })
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, _, e := c.Fetch(ctx, testMAC); done <- e }()
	cancel()
	select {
	case e := <-done:
		if e == nil {
			t.Fatal("expected cancellation")
		}
	case <-time.After(time.Second):
		t.Fatal(fmt.Errorf("cancel did not stop UDP read"))
	}
}

func TestMQTTReadFiltersOtherDevices(t *testing.T) {
	wanted := sampleMQTT()
	c := simulatedClient(t, func(p []byte) [][]byte {
		if !bytes.Equal(p, []byte("\x33\xbb0090E2D72060\xbb\x33")) {
			t.Error("unexpected MQTT read request")
		}
		other := mqttResponse(wanted)
		other[13] = '1'
		return [][]byte{{0x44, 0xaa, 0xaa, 0x44}, other, mqttResponse(wanted)}
	})
	got, _, err := c.ReadMQTT(context.Background(), testMAC)
	if err != nil || got != wanted {
		t.Fatal("target filtering", err)
	}
}

func TestMQTTSaveAcknowledgement(t *testing.T) {
	for _, mode := range []string{"valid", "invalid", "foreign-address"} {
		t.Run(mode, func(t *testing.T) {
			c := simulatedClient(t, func([]byte) [][]byte {
				if mode == "invalid" {
					return [][]byte{{0x44, 0xaa, 0xaa, 0x45}}
				}
				return [][]byte{{0}, {0x44, 0xaa, 0xaa, 0x44}}
			})
			s, err := c.open(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			address := s.destination.String()
			if mode == "foreign-address" {
				address = "127.0.0.1:1"
			}
			err = saveMQTT(s, mqttSaveFixture(sampleMQTT()), address)
			if (err == nil) != (mode == "valid") {
				t.Fatalf("ack %s: %v", mode, err)
			}
		})
	}
}
