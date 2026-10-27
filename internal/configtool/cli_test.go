package configtool

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"strings"
	"testing"
)

type fakeClient struct {
	calls int
	mqtt  MQTTConfig
}

func (f *fakeClient) Discover(context.Context) ([]Device, error) { f.calls++; return []Device{}, nil }
func (f *fakeClient) Fetch(context.Context, MAC) ([]byte, string, error) {
	f.calls++
	return make([]byte, 256), "127.0.0.1:60000", nil
}
func (f *fakeClient) Apply(context.Context, MAC, []byte) (string, error) {
	f.calls++
	return "保存命令已发送，请重新读取核对。", nil
}
func (f *fakeClient) ReadMQTT(context.Context, MAC) (MQTTConfig, string, error) {
	f.calls++
	return f.mqtt, "127.0.0.1:60000", nil
}
func (f *fakeClient) WriteMQTT(_ context.Context, _ MAC, c MQTTConfig) (MQTTConfig, error) {
	f.calls++
	f.mqtt = c
	return c, nil
}
func TestCLIContract(t *testing.T) {
	cases := []struct {
		args  []string
		ok    bool
		calls int
	}{
		{[]string{"--interface", "query", "scan"}, true, 1}, {[]string{"scan", "-i", "serve"}, true, 1}, {[]string{"read", "0090E2D72060", "--interface=en0", "--raw"}, true, 1}, {[]string{"set", "0090E2D72060", "id", "sensor", "west"}, true, 1}, {[]string{}, false, 0}, {[]string{"scan", "extra"}, false, 0}, {[]string{"scan", "--unknown"}, false, 0}, {[]string{"set", "0090E2D72060", "port", "0"}, false, 0}, {[]string{"set", "0090E2D72060", "mode", "1", "2"}, false, 0}, {[]string{"read", "90E2D72060"}, false, 0}, {[]string{"mqtt-set", "0090E2D72060", "--username", "user"}, false, 0}, {[]string{"mqtt-set", "0090E2D72060", "--username=", "--password=", "--subscribe-topic=", "--publish-topic=", "--device-id=", "--heartbeat-topic=", "--heartbeat-content=", "--heartbeat-interval=0"}, true, 1}, {[]string{"serve", "--listen", "localhost:0"}, false, 0}, {[]string{"read", "--", "--raw"}, false, 0},
	}
	for _, c := range cases {
		t.Run(fmt.Sprint(c.args), func(t *testing.T) {
			f := &fakeClient{}
			root := newCommand(f)
			root.SetOut(io.Discard)
			root.SetErr(io.Discard)
			root.SetArgs(c.args)
			e := root.ExecuteContext(context.Background())
			if (e == nil) != c.ok || f.calls != c.calls {
				t.Fatalf("error=%v calls=%d", e, f.calls)
			}
		})
	}
}
func TestLocalCommandsAndExitCodes(t *testing.T) {
	for _, args := range [][]string{{"--help"}, {"--version"}, {"-V"}, {"completions", "bash"}, {"completions", "zsh"}, {"completions", "fish"}, {"__complete", "set", "0090E2D72060", ""}} {
		f := &fakeClient{}
		root := newCommand(f)
		var out bytes.Buffer
		root.SetOut(&out)
		root.SetErr(io.Discard)
		root.SetArgs(args)
		if e := root.Execute(); e != nil || f.calls != 0 || out.Len() == 0 {
			t.Fatalf("%v %v", args, e)
		}
	}
	var out, err bytes.Buffer
	if code := Run(context.Background(), []string{"scan", "--bad"}, strings.NewReader(""), &out, &err); code != 2 || out.Len() != 0 || err.Len() == 0 {
		t.Fatal("exit/output contract")
	}
}

func TestCLIMQTTHeartbeat(t *testing.T) {
	for _, interval := range []string{"0", "255", "-1", "256", "1.5"} {
		f := &fakeClient{}
		root := newCommand(f)
		root.SetOut(io.Discard)
		root.SetErr(io.Discard)
		root.SetArgs([]string{"mqtt-set", "0090E2D72060", "--username=", "--password=", "--subscribe-topic=", "--publish-topic=", "--device-id=", "--heartbeat-topic=ping", "--heartbeat-content=alive", "--heartbeat-interval=" + interval})
		err := root.Execute()
		valid := interval == "0" || interval == "255"
		if (err == nil) != valid {
			t.Fatalf("interval %s: %v", interval, err)
		}
		if valid && (f.calls != 1 || f.mqtt.HeartbeatTopic != "ping" || f.mqtt.HeartbeatContent != "alive") {
			t.Fatal("heartbeat fields lost")
		}
		if !valid && f.calls != 0 {
			t.Fatal("invalid interval reached device")
		}
	}
	f := &fakeClient{mqtt: sampleMQTT()}
	root := newCommand(f)
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetArgs([]string{"mqtt-read", "0090E2D72060"})
	if err := root.Execute(); err != nil {
		t.Fatal(err)
	}
	for _, part := range []string{"心跳主题：ping", "心跳内容：alive", "心跳间隔：30 秒"} {
		if !strings.Contains(out.String(), part) {
			t.Fatal("missing heartbeat output")
		}
	}
}
