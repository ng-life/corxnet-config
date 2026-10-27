package configtool

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func request(h http.Handler, method, path, body, origin, host, marker string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Host = host
	if origin != "" {
		r.Header.Set("Origin", origin)
	}
	if marker != "" {
		r.Header.Set("X-Corxnet-Request", marker)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func TestOrigin(t *testing.T) {
	for _, c := range []struct {
		h, o, m string
		want    bool
	}{{"127.0.0.1:8080", "http://127.0.0.1:8080", "1", true}, {"[::1]", "http://[::1]", "1", true}, {"localhost:8080", "", "1", true}, {"evil.test:8080", "http://evil.test:8080", "1", false}, {"127.0.0.1:8080", "http://evil.test", "1", false}, {"localhost:8080", "", "", false}, {"localhost:99999", "", "1", false}} {
		if originAllowed(c.h, c.o, c.m) != c.want {
			t.Fatal(c)
		}
	}
}
func TestHTTPValidationAndAssets(t *testing.T) {
	f := &fakeClient{}
	h := newWebHandler("", f)
	for _, c := range []struct {
		method, path, body, host, origin, marker string
		status                                   int
	}{{"GET", "/", "", "localhost:8080", "", "", 200}, {"GET", "/api/info", "", "localhost:8080", "", "", 200}, {"GET", "/favicon.ico", "", "localhost", "", "", 204}, {"POST", "/api/read", `{"mac":"bad"}`, "localhost:8080", "", "1", 400}, {"POST", "/api/set", `{"mac":"0090E2D72060","setting":"port","value":"0"}`, "localhost:8080", "", "1", 400}, {"POST", "/api/mqtt/set", `{"mac":"0090E2D72060"}`, "localhost:8080", "", "1", 400}, {"POST", "/api/read", `{"mac":"0090E2D72060","unknown":0}`, "localhost:8080", "", "1", 400}, {"POST", "/api/read", `{"mac":"0090E2D72060"} {}`, "localhost:8080", "", "1", 400}, {"POST", "/api/read", strings.Repeat("x", 16385), "localhost:8080", "", "1", 400}, {"POST", "/api/scan", "{}", "evil.test", "", "1", 403}, {"POST", "/api/scan", "{}", "localhost:8080", "http://evil.test", "1", 403}, {"POST", "/api/scan", "{}", "localhost:8080", "", "", 403}, {"GET", "/api/scan", "", "localhost:8080", "", "1", 404}} {
		w := request(h, c.method, c.path, c.body, c.origin, c.host, c.marker)
		if w.Code != c.status {
			t.Fatalf("%s %s: %d %s", c.method, c.path, w.Code, w.Body)
		}
		if w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("Content-Security-Policy") == "" {
			t.Fatal("security headers")
		}
	}
	if f.calls != 0 {
		t.Fatal("invalid requests opened device")
	}
	w := request(h, "GET", "/api/info", "", "", "localhost", "")
	if strings.TrimSpace(w.Body.String()) != `{"interface":null}` {
		t.Fatal(w.Body.String())
	}
	w = request(h, "GET", "/", "", "", "localhost", "")
	if w.Body.String() != page || !strings.Contains(w.Header().Get("Content-Type"), "text/html") {
		t.Fatal("embedded page")
	}
}
func TestHTTPMQTTBaselineAndRestore(t *testing.T) {
	f := &fakeClient{mqtt: sampleMQTT()}
	s := newWebHandler("", f).(*webState)
	ctx := context.Background()
	body := []byte(`{"mac":"0090E2D72060"}`)
	if _, e := s.dispatch(ctx, "/api/mqtt/read", body); e != nil {
		t.Fatal(e)
	}
	original := f.mqtt
	f.mqtt.DeviceID = "external-change"
	_, _ = s.dispatch(ctx, "/api/mqtt/read", body)
	if s.originals[testMAC] != original {
		t.Fatal("baseline overwritten")
	}
	if _, e := s.dispatch(ctx, "/api/mqtt/set", []byte(`{"mac":"0090E2D72060","config":{"username":""}}`)); e == nil {
		t.Fatal("partial config accepted")
	}
	if _, e := s.dispatch(ctx, "/api/mqtt/restore", body); e != nil || f.mqtt != original {
		t.Fatal("restore", e)
	}
	w := request(s, "POST", "/api/read", string(body), "", "localhost", "1")
	var result map[string]any
	if e := json.Unmarshal(w.Body.Bytes(), &result); e != nil || !strings.Contains(result["raw"].(string), ":") {
		t.Fatal("HTTP raw contract", e)
	}
}
