//! 同步处理设备请求：每次只占用一个 UDP 60001 socket。
use super::*;
use serde::Deserialize;
use serde_json::{json, Value};
use std::{collections::HashMap, io::Read};
use tiny_http::{Header, Method, Request, Response, Server};

const PAGE: &str = include_str!("web.html");
const MAX_BODY: u64 = 16 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Input {
    mac: String,
    setting: Option<String>,
    value: Option<String>,
    config: Option<MqttConfig>,
}

struct State {
    interface: Option<String>,
    // 凭据只驻留进程内存，限制设备数量以约束内存占用。
    originals: HashMap<[u8; 6], MqttConfig>,
}

pub(super) fn serve(listen: SocketAddr, interface: Option<String>) -> Result<(), String> {
    let server = Server::http(listen).map_err(|e| format!("启动 HTTP 服务失败：{e}"))?;
    println!(
        "网页配置服务：http://{}（Ctrl+C 停止）",
        server.server_addr()
    );
    let mut state = State {
        interface,
        originals: HashMap::new(),
    };
    for mut request in server.incoming_requests() {
        let (status, body, content_type) =
            if request.method() == &Method::Get && request.url() == "/" {
                (200, PAGE.to_owned(), "text/html; charset=utf-8")
            } else if request.method() == &Method::Get && request.url() == "/api/info" {
                (
                    200,
                    json!({"interface": state.interface}).to_string(),
                    "application/json",
                )
            } else if request.method() == &Method::Get && request.url() == "/favicon.ico" {
                (204, String::new(), "image/x-icon")
            } else if request.method() != &Method::Post || !request.url().starts_with("/api/") {
                (
                    404,
                    json!({"error":"接口不存在"}).to_string(),
                    "application/json",
                )
            } else if !same_origin(&request) {
                (
                    403,
                    json!({"error":"仅允许同源网页请求"}).to_string(),
                    "application/json",
                )
            } else {
                let result =
                    read_body(&mut request).and_then(|body| state.dispatch(request.url(), &body));
                match result {
                    Ok(value) => (200, value.to_string(), "application/json"),
                    Err(error) => (400, json!({"error": error}).to_string(), "application/json"),
                }
            };
        let response = Response::from_string(body).with_status_code(status)
            .with_header(header("Content-Type", content_type))
            .with_header(header("Cache-Control", "no-store"))
            .with_header(header("X-Content-Type-Options", "nosniff"))
            .with_header(header("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"));
        // 不输出请求内容或 MQTT 凭据。
        if let Err(e) = request.respond(response) {
            eprintln!("HTTP 响应失败：{e}");
        }
    }
    Ok(())
}

fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name, value).expect("固定 HTTP 头有效")
}

fn same_origin(request: &Request) -> bool {
    let get = |name: &'static str| {
        request
            .headers()
            .iter()
            .find(|h| h.field.equiv(name))
            .map(|h| h.value.as_str())
    };
    origin_allowed(get("Host"), get("Origin"), get("X-Corxnet-Request"))
}

fn origin_allowed(host: Option<&str>, origin: Option<&str>, marker: Option<&str>) -> bool {
    let Some(host) = host else {
        return false;
    };
    // 仅允许 IP 或 localhost，避免恶意域名 DNS 重绑定到本机服务。
    let authority = host == "localhost"
        || host.parse::<std::net::IpAddr>().is_ok()
        || host
            .strip_prefix('[')
            .and_then(|h| h.strip_suffix(']'))
            .is_some_and(|h| h.parse::<std::net::Ipv6Addr>().is_ok())
        || host.parse::<SocketAddr>().is_ok()
        || host
            .strip_prefix("localhost:")
            .is_some_and(|p| p.parse::<u16>().is_ok());
    authority && marker == Some("1") && origin.is_none_or(|o| o == format!("http://{host}"))
}

fn read_body(request: &mut Request) -> Result<String, String> {
    if request.body_length().is_some_and(|n| n as u64 > MAX_BODY) {
        return Err("请求体超过 16 KiB".into());
    }
    let mut body = String::new();
    request
        .as_reader()
        .take(MAX_BODY + 1)
        .read_to_string(&mut body)
        .map_err(|_| "读取请求失败")?;
    if body.len() as u64 > MAX_BODY {
        return Err("请求体超过 16 KiB".into());
    }
    Ok(body)
}

impl State {
    fn dispatch(&mut self, path: &str, body: &str) -> Result<Value, String> {
        if path == "/api/scan" {
            return discover(self.interface.as_deref()).map(|devices| json!({"devices":devices}));
        }
        if !matches!(
            path,
            "/api/read" | "/api/set" | "/api/mqtt/read" | "/api/mqtt/set" | "/api/mqtt/restore"
        ) {
            return Err("接口不存在".into());
        }
        let input: Input = serde_json::from_str(body).map_err(|_| "请求 JSON 格式或字段无效")?;
        let mac = parse_full_device_mac(&input.mac)?;
        let interface = self.interface.as_deref();
        match path {
            "/api/read" => {
                let (bytes, address) = fetch_config(mac, interface)?;
                Ok(
                    json!({"address":address.to_string(), "config":parse_network_config(&bytes), "raw":hex(&bytes)}),
                )
            }
            "/api/set" => {
                let key = Setting::from_str(input.setting.as_deref().ok_or("缺少参数名")?, false)
                    .map_err(|_| "参数名无效")?;
                let value = input.value.ok_or("缺少参数值")?;
                let payload = make_setting(key, &[value])?;
                apply_setting(mac, payload, interface).map(|message| json!({"message":message}))
            }
            "/api/mqtt/read" => {
                let s = socket(interface)?;
                let (config, address) = receive_mqtt_config(&s, mac)?;
                if self.originals.len() >= 128 && !self.originals.contains_key(&mac) {
                    return Err("原值缓存已满，请重启服务释放缓存".into());
                }
                self.originals.entry(mac).or_insert_with(|| config.clone());
                Ok(json!({"config":config,"address":address.to_string()}))
            }
            _ => {
                let original = self
                    .originals
                    .get(&mac)
                    .ok_or("请先读取 MQTT 配置，保存原值后再写入")?
                    .clone();
                let config = if path == "/api/mqtt/restore" {
                    original
                } else {
                    input.config.ok_or("缺少完整 MQTT 配置")?
                };
                let packet = encode_mqtt_save(mac, &config)?;
                let s = socket(interface)?;
                // 写入前再次读取；失败时禁止发送保存帧。
                receive_mqtt_config(&s, mac)?;
                broadcast(&s, &packet)?;
                let (actual, _) = receive_mqtt_config(&s, mac)
                    .map_err(|_| "保存帧已发送，但复读失败；原值仍保留，可重试读取或恢复")?;
                if actual != config {
                    return Err("保存帧已发送，但复读值不一致；原值仍保留，可执行恢复".into());
                }
                Ok(json!({"config":actual,"message":"保存完成，复读核验一致。"}))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_cross_origin_and_rebinding() {
        assert!(origin_allowed(
            Some("127.0.0.1:8080"),
            Some("http://127.0.0.1:8080"),
            Some("1")
        ));
        assert!(!origin_allowed(
            Some("127.0.0.1:8080"),
            Some("http://evil.test"),
            Some("1")
        ));
        assert!(!origin_allowed(
            Some("evil.test:8080"),
            Some("http://evil.test:8080"),
            Some("1")
        ));
        assert!(!origin_allowed(Some("localhost:8080"), None, None));
        assert!(origin_allowed(
            Some("127.0.0.1"),
            Some("http://127.0.0.1"),
            Some("1")
        ));
        assert!(origin_allowed(
            Some("[::1]"),
            Some("http://[::1]"),
            Some("1")
        ));
    }
    #[test]
    fn invalid_requests_never_open_udp_socket() {
        let mut s = State {
            interface: Some("nonexistent-test-interface".into()),
            originals: HashMap::new(),
        };
        assert!(s
            .dispatch("/api/read", r#"{"mac":"bad"}"#)
            .unwrap_err()
            .contains("MAC"));
        assert!(s
            .dispatch(
                "/api/set",
                r#"{"mac":"0090E2D72060","setting":"port","value":"0"}"#
            )
            .is_err());
        assert!(s
            .dispatch("/api/mqtt/set", r#"{"mac":"0090E2D72060"}"#)
            .unwrap_err()
            .contains("先读取"));
        assert!(s
            .dispatch("/api/mqtt/restore", r#"{"mac":"0090E2D72060"}"#)
            .unwrap_err()
            .contains("先读取"));
    }
    #[test]
    fn serve_cli_and_completion_include_listen() {
        let cli = Cli::try_parse_from([
            "corxnet-config",
            "serve",
            "--listen",
            "127.0.0.1:0",
            "-i",
            "en0",
        ])
        .unwrap();
        assert_eq!(cli.interface.as_deref(), Some("en0"));
        assert!(matches!(cli.command, Commands::Serve { listen } if listen.port() == 0));
        let mut output = Vec::new();
        generate(
            Shell::Bash,
            &mut Cli::command(),
            "corxnet-config",
            &mut output,
        );
        let script = String::from_utf8(output).unwrap();
        assert!(script.contains("serve") && script.contains("--listen"));
    }

    #[test]
    fn configuration_maps_documented_offsets() {
        let mut b = [0u8; 256];
        b[8..10].copy_from_slice(&50000u16.to_le_bytes());
        b[40..44].copy_from_slice(&[192, 168, 1, 5]);
        b[254] = 1;
        let config = serde_json::to_value(parse_network_config(&b)).unwrap();
        assert_eq!(config["port"], 50000);
        assert_eq!(config["ip"], "192.168.1.5");
        assert_eq!(config["dhcp"], 1);
        assert!(config.get("dns").is_none());
    }
}
