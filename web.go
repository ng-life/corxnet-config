package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed web.html
var page string

type webState struct {
	mu        sync.Mutex
	iface     string
	client    deviceClient
	originals map[MAC]MQTTConfig
}
type input struct {
	MAC     string      `json:"mac"`
	Setting *string     `json:"setting"`
	Value   *string     `json:"value"`
	Config  *MQTTConfig `json:"config"`
}

func newWebHandler(iface string, client deviceClient) http.Handler {
	return &webState{iface: iface, client: client, originals: map[MAC]MQTTConfig{}}
}
func serveHTTP(ctx context.Context, listen, iface string, client deviceClient, w io.Writer) error {
	listener, e := net.Listen("tcp", listen)
	if e != nil {
		return fmt.Errorf("启动 HTTP 服务失败：%w", e)
	}
	server := &http.Server{Handler: newWebHandler(iface, client), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }}
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-ctx.Done():
			_ = server.Close()
		case <-done:
		}
	}()
	fmt.Fprintf(w, "网页配置服务：http://%s（Ctrl+C 停止）\n", listener.Addr())
	e = server.Serve(listener)
	if e == http.ErrServerClosed {
		return nil
	}
	return e
}
func originAllowed(host, origin, marker string) bool {
	h := host
	if name, port, e := net.SplitHostPort(host); e == nil {
		if _, e = strconv.ParseUint(port, 10, 16); e != nil {
			return false
		}
		h = name
	} else if strings.HasPrefix(host, "[") && strings.HasSuffix(host, "]") {
		h = host[1 : len(host)-1]
	}
	return (h == "localhost" || net.ParseIP(h) != nil) && marker == "1" && (origin == "" || origin == "http://"+host)
}
func (s *webState) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
	respond := func(status int, v any) { w.WriteHeader(status); _ = json.NewEncoder(w).Encode(v) }
	switch {
	case r.Method == "GET" && r.URL.RequestURI() == "/":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, page)
		return
	case r.Method == "GET" && r.URL.RequestURI() == "/api/info":
		var iface any
		if s.iface != "" {
			iface = s.iface
		}
		respond(200, map[string]any{"interface": iface})
		return
	case r.Method == "GET" && r.URL.RequestURI() == "/favicon.ico":
		w.WriteHeader(204)
		return
	case r.Method != "POST" || !strings.HasPrefix(r.URL.RequestURI(), "/api/"):
		respond(404, map[string]string{"error": "接口不存在"})
		return
	case !originAllowed(r.Host, r.Header.Get("Origin"), r.Header.Get("X-Corxnet-Request")):
		respond(403, map[string]string{"error": "仅允许同源网页请求"})
		return
	}
	body, e := io.ReadAll(io.LimitReader(r.Body, 16*1024+1))
	if e != nil || len(body) > 16*1024 {
		respond(400, map[string]string{"error": "请求体无效或超过 16 KiB"})
		return
	}
	// HTTP 请求可以并发到达，但设备操作与恢复基线必须串行访问。
	s.mu.Lock()
	defer s.mu.Unlock()
	result, e := s.dispatch(r.Context(), r.URL.RequestURI(), body)
	if e != nil {
		respond(400, map[string]string{"error": e.Error()})
		return
	}
	respond(200, result)
}
func (s *webState) dispatch(ctx context.Context, path string, body []byte) (any, error) {
	if path == "/api/scan" {
		ds, e := s.client.Discover(ctx)
		return map[string]any{"devices": ds}, e
	}
	switch path {
	case "/api/read", "/api/set", "/api/mqtt/read", "/api/mqtt/set", "/api/mqtt/restore":
	default:
		return nil, fmt.Errorf("接口不存在")
	}
	var in input
	decoder := json.NewDecoder(strings.NewReader(string(body)))
	decoder.DisallowUnknownFields()
	if e := decoder.Decode(&in); e != nil {
		return nil, fmt.Errorf("请求 JSON 格式或字段无效")
	}
	if decoder.Decode(new(any)) != io.EOF {
		return nil, fmt.Errorf("请求 JSON 格式无效")
	}
	m, e := parseMAC(in.MAC)
	if e != nil {
		return nil, e
	}
	switch path {
	case "/api/read":
		b, address, e := s.client.Fetch(ctx, m)
		if e != nil {
			return nil, e
		}
		return map[string]any{"address": address, "config": parseNetworkConfig(b), "raw": strings.ReplaceAll(hexText(b), " ", ":")}, nil
	case "/api/set":
		if in.Setting == nil || in.Value == nil {
			return nil, fmt.Errorf("缺少参数名或参数值")
		}
		p, e := makeSetting(*in.Setting, []string{*in.Value})
		if e != nil {
			return nil, e
		}
		message, e := s.client.Apply(ctx, m, p)
		return map[string]string{"message": message}, e
	case "/api/mqtt/read":
		c, address, e := s.client.ReadMQTT(ctx, m)
		if e != nil {
			return nil, e
		}
		if _, ok := s.originals[m]; !ok {
			if len(s.originals) >= 128 {
				return nil, fmt.Errorf("原值缓存已满，请重启服务释放缓存")
			}
			s.originals[m] = c
		}
		return map[string]any{"config": c, "address": address}, nil
	default:
		original, ok := s.originals[m]
		if !ok {
			return nil, fmt.Errorf("请先读取 MQTT 配置，保存原值后再写入")
		}
		wanted := original
		if path == "/api/mqtt/set" {
			if in.Config == nil {
				return nil, fmt.Errorf("缺少完整 MQTT 配置")
			}
			wanted = *in.Config
		}
		if _, e := encodeMQTT(m, wanted); e != nil {
			return nil, e
		}
		actual, e := s.client.WriteMQTT(ctx, m, wanted)
		if e != nil {
			return nil, e
		}
		return map[string]any{"config": actual, "message": "保存完成，复读核验一致。"}, nil
	}
}
