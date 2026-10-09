use std::{
    net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket},
    process,
    time::Duration,
};

use clap::{CommandFactory, Parser, Subcommand, ValueEnum};
use clap_complete::{generate, Shell};
#[cfg(not(target_os = "linux"))]
use network_interface::{Addr, NetworkInterface, NetworkInterfaceConfig};
#[cfg(target_os = "linux")]
use std::os::fd::AsRawFd;

const DEST_PORT: u16 = 60000;
const LOCAL_PORT: u16 = 60001;
const BROADCAST: Ipv4Addr = Ipv4Addr::new(255, 255, 255, 255);
const TIMEOUT: Duration = Duration::from_millis(1500);

#[derive(Debug, Parser)]
#[command(name = "corxnet-config", version, about = "UDP 控制器网络配置工具")]
struct Cli {
    #[arg(
        short,
        long,
        global = true,
        value_name = "网卡",
        help = "指定收发广播的网卡"
    )]
    interface: Option<String>,
    #[command(subcommand)]
    command: Commands,
}

#[derive(Debug, Subcommand)]
enum Commands {
    #[command(about = "扫描局域网设备")]
    Scan,
    #[command(about = "读取设备网络配置")]
    Read {
        #[arg(value_name = "完整MAC", help = "设备完整 MAC 地址")]
        mac: String,
        #[arg(long, help = "打印完整 256 字节响应")]
        raw: bool,
    },
    #[command(about = "设置一项设备参数并保存")]
    Set {
        #[arg(value_name = "完整MAC")]
        mac: String,
        #[arg(value_enum, value_name = "参数")]
        setting: Setting,
        #[arg(required = true, num_args = 1.., value_name = "值")]
        value: Vec<String>,
    },
    #[command(about = "读取设备 MQTT 配置")]
    MqttRead {
        #[arg(value_name = "MAC", help = "设备完整 MAC 地址")]
        mac: String,
    },
    #[command(about = "保存设备 MQTT 配置")]
    MqttSet {
        #[arg(value_name = "MAC", help = "设备完整 MAC 地址")]
        mac: String,
        #[arg(long, value_name = "用户名")]
        username: String,
        #[arg(long, value_name = "密码")]
        password: String,
        #[arg(
            long,
            value_name = "主题",
            help = "订阅主题，例如设备接收控制命令的主题"
        )]
        subscribe_topic: String,
        #[arg(long, value_name = "主题", help = "发布主题，例如设备上报事件的主题")]
        publish_topic: String,
        #[arg(long, value_name = "设备ID")]
        device_id: String,
    },
    #[command(about = "生成 shell 补全脚本")]
    Completions {
        #[arg(value_enum, value_name = "SHELL")]
        shell: ShellKind,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
enum Setting {
    Ip,
    Target,
    Gateway,
    Mode,
    Port,
    Netmask,
    Mac,
    Dhcp,
    Id,
    Dns,
    Hostname,
    Heartbeat,
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum ShellKind {
    Bash,
    Zsh,
    Fish,
}

fn main() {
    let cli = Cli::parse();
    if let Err(e) = run(cli) {
        eprintln!("错误：{e}");
        process::exit(2);
    }
}

fn run(cli: Cli) -> Result<(), String> {
    match cli.command {
        Commands::Scan => scan(cli.interface.as_deref()),
        Commands::Read { mac, raw } => {
            read_config(parse_full_device_mac(&mac)?, cli.interface.as_deref(), raw)
        }
        Commands::Set {
            mac,
            setting,
            value,
        } => configure(
            parse_full_device_mac(&mac)?,
            make_setting(setting, &value)?,
            cli.interface.as_deref(),
        ),
        Commands::MqttRead { mac } => {
            mqtt_read(parse_full_device_mac(&mac)?, cli.interface.as_deref())
        }
        Commands::MqttSet {
            mac,
            username,
            password,
            subscribe_topic,
            publish_topic,
            device_id,
        } => {
            let mac = parse_full_device_mac(&mac)?;
            let config = MqttConfig {
                username,
                password,
                subscribe_topic,
                publish_topic,
                device_id,
            };
            let packet = encode_mqtt_save(mac, &config)?;
            let s = socket(cli.interface.as_deref())?;
            broadcast(&s, &packet)?;
            println!("MQTT 配置保存广播已发送；请运行 mqtt-read 读取并核对配置。");
            Ok(())
        }
        Commands::Completions { shell } => {
            let shell = match shell {
                ShellKind::Bash => Shell::Bash,
                ShellKind::Zsh => Shell::Zsh,
                ShellKind::Fish => Shell::Fish,
            };
            generate(
                shell,
                &mut Cli::command(),
                "corxnet-config",
                &mut std::io::stdout(),
            );
            Ok(())
        }
    }
}

fn parse_full_device_mac(s: &str) -> Result<[u8; 6], String> {
    if s.len() == 12 && s.bytes().all(|b| b.is_ascii_hexdigit()) {
        let mut mac = [0; 6];
        for (i, chunk) in s.as_bytes().chunks_exact(2).enumerate() {
            mac[i] = u8::from_str_radix(std::str::from_utf8(chunk).unwrap(), 16)
                .map_err(|_| "MAC 地址格式无效")?;
        }
        Ok(mac)
    } else {
        parse_mac(s)
    }
}

const MQTT_FIELD_SLOTS: [usize; 5] = [101, 101, 101, 101, 102];
const MQTT_TRAILER_OFFSET: usize = 520;
const MQTT_PACKET_LEN: usize = 623;

#[derive(Debug, Clone, PartialEq, Eq)]
struct MqttConfig {
    username: String,
    password: String,
    subscribe_topic: String,
    publish_topic: String,
    device_id: String,
}

impl MqttConfig {
    fn values(&self) -> [&str; 5] {
        [
            &self.username,
            &self.password,
            &self.subscribe_topic,
            &self.publish_topic,
            &self.device_id,
        ]
    }
}

fn encode_mqtt_read(mac: [u8; 6]) -> Vec<u8> {
    let mut packet = vec![0x33, 0xbb];
    append_ascii_mac(&mut packet, mac);
    packet.extend_from_slice(&[0xbb, 0x33]);
    packet
}

fn encode_mqtt_save(mac: [u8; 6], config: &MqttConfig) -> Result<Vec<u8>, String> {
    let mut packet = vec![0x44, 0xaa];
    append_ascii_mac(&mut packet, mac);
    for (value, slot) in config.values().into_iter().zip(MQTT_FIELD_SLOTS) {
        let bytes = value.as_bytes();
        if !bytes.is_ascii() {
            return Err("MQTT 参数只支持 ASCII 字符".into());
        }
        if bytes.contains(&0) {
            return Err("MQTT 参数不能包含 NUL 字符".into());
        }
        let max_len = slot - 1;
        if bytes.len() > max_len {
            return Err(format!(
                "MQTT 字段最多允许 {max_len} 字节，当前 {} 字节",
                bytes.len()
            ));
        }
        packet.push(bytes.len() as u8);
        packet.extend_from_slice(bytes);
        packet.resize(packet.len() + max_len - bytes.len(), 0);
    }
    packet.push(0xff);
    packet.resize(MQTT_TRAILER_OFFSET + 101, 0);
    packet.extend_from_slice(&[0xaa, 0x44]);
    if packet.len() != MQTT_PACKET_LEN {
        return Err("MQTT 保存报文长度与协议样本不符".into());
    }
    Ok(packet)
}

fn append_ascii_mac(packet: &mut Vec<u8>, mac: [u8; 6]) {
    for byte in mac {
        packet.extend_from_slice(format!("{byte:02X}").as_bytes());
    }
}

fn parse_mqtt_response(packet: &[u8], expected_mac: [u8; 6]) -> Result<MqttConfig, String> {
    if packet.len() != MQTT_PACKET_LEN || packet[..2] != [0x33, 0xbb] {
        return Err("MQTT 响应长度或帧头不正确".into());
    }
    let mut mac_text = Vec::with_capacity(12);
    append_ascii_mac(&mut mac_text, expected_mac);
    if packet[2..14] != mac_text {
        return Err("MQTT 响应中的 MAC 与请求设备不匹配".into());
    }
    if packet[MQTT_TRAILER_OFFSET] != 0xff || packet[MQTT_PACKET_LEN - 2..] != [0xbb, 0x33] {
        return Err("MQTT 响应保留区或帧尾不正确".into());
    }
    let mut offset = 14;
    let mut values = Vec::with_capacity(5);
    for (field_index, slot) in MQTT_FIELD_SLOTS.into_iter().enumerate() {
        let len = packet[offset] as usize;
        if len > slot - 1 {
            return Err("MQTT 响应字段长度超出协议字段范围".into());
        }
        let content = &packet[offset + 1..offset + 1 + len];
        let value = std::str::from_utf8(content)
            .map_err(|_| "MQTT 响应字段不是有效 UTF-8")?
            .to_string();
        if !content.is_ascii() || content.contains(&0) {
            return Err("MQTT 响应字段包含不支持的字符".into());
        }
        if let Some(padding_offset) = packet[offset + 1 + len..offset + slot]
            .iter()
            .position(|byte| *byte != 0)
        {
            return Err(format!(
                "MQTT 第 {} 个字段的零填充格式不正确（字段偏移 {}）",
                field_index + 1,
                offset + 1 + len + padding_offset
            ));
        }
        values.push(value);
        offset += slot;
    }
    if packet[MQTT_TRAILER_OFFSET + 1..MQTT_PACKET_LEN - 2]
        .iter()
        .any(|byte| *byte != 0)
    {
        return Err("MQTT 响应保留区格式不正确".into());
    }
    let mut values = values.into_iter();
    Ok(MqttConfig {
        username: values.next().unwrap(),
        password: values.next().unwrap(),
        subscribe_topic: values.next().unwrap(),
        publish_topic: values.next().unwrap(),
        device_id: values.next().unwrap(),
    })
}

fn mqtt_read(mac: [u8; 6], interface: Option<&str>) -> Result<(), String> {
    let socket = socket(interface)?;
    let (config, from) = receive_mqtt_config(&socket, mac)?;
    println!("MQTT 配置（设备 {from}）");
    println!("  用户名：{}", config.username);
    println!("  密码：{}", config.password);
    println!("  订阅主题：{}", config.subscribe_topic);
    println!("  发布主题：{}", config.publish_topic);
    println!("  设备 ID：{}", config.device_id);
    Ok(())
}

fn receive_mqtt_config(
    socket: &UdpSocket,
    mac: [u8; 6],
) -> Result<(MqttConfig, SocketAddr), String> {
    broadcast(&socket, &encode_mqtt_read(mac))?;
    let mut buf = [0u8; 2048];
    let mut last_invalid = None;
    loop {
        let (len, from) = match socket.recv_from(&mut buf) {
            Ok(packet) => packet,
            Err(e) => {
                let detail = last_invalid
                    .map(|reason| format!("；收到但无法解析的响应：{reason}"))
                    .unwrap_or_default();
                return Err(format!("读取 MQTT 参数超时或接收失败：{e}{detail}"));
            }
        };
        match parse_mqtt_response(&buf[..len], mac) {
            Ok(config) => return Ok((config, from)),
            Err(reason) => last_invalid = Some(reason),
        }
    }
}

#[cfg(test)]
fn mqtt_save_from_response(response: &[u8]) -> Result<Vec<u8>, String> {
    if response.len() != MQTT_PACKET_LEN || response[..2] != [0x33, 0xbb] {
        return Err("无法从该 MQTT 响应生成恢复报文".into());
    }
    let mut packet = response.to_vec();
    packet[..2].copy_from_slice(&[0x44, 0xaa]);
    packet[MQTT_PACKET_LEN - 2..].copy_from_slice(&[0xaa, 0x44]);
    Ok(packet)
}

fn socket(interface: Option<&str>) -> Result<UdpSocket, String> {
    let local_ip = match interface {
        #[cfg(target_os = "linux")]
        _ => Ipv4Addr::UNSPECIFIED,
        #[cfg(not(target_os = "linux"))]
        Some(name) => interface_ipv4(name)?,
        #[cfg(not(target_os = "linux"))]
        None => Ipv4Addr::UNSPECIFIED,
    };
    let s = UdpSocket::bind(SocketAddrV4::new(local_ip, LOCAL_PORT))
        .map_err(|e| format!("绑定 UDP 本地端口 {LOCAL_PORT} 失败：{e}"))?;
    #[cfg(target_os = "linux")]
    if let Some(name) = interface {
        bind_interface(&s, name)?;
    }
    s.set_broadcast(true).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(TIMEOUT))
        .map_err(|e| e.to_string())?;
    Ok(s)
}

#[cfg(not(target_os = "linux"))]
fn interface_ipv4(name: &str) -> Result<Ipv4Addr, String> {
    let interfaces = NetworkInterface::show().map_err(|e| format!("读取网卡列表失败：{e}"))?;
    let interface = interfaces
        .iter()
        .find(|interface| interface.name.eq_ignore_ascii_case(name))
        .ok_or_else(|| format!("找不到网卡“{name}”；请先查看本机网卡名称"))?;
    interface
        .addr
        .iter()
        .find_map(|addr| match addr {
            Addr::V4(address) if !address.ip.is_unspecified() => Some(address.ip),
            _ => None,
        })
        .ok_or_else(|| format!("网卡“{name}”没有可用的 IPv4 地址"))
}

#[cfg(target_os = "linux")]
fn bind_interface(socket: &UdpSocket, name: &str) -> Result<(), String> {
    const SOL_SOCKET: i32 = 1;
    const SO_BINDTODEVICE: i32 = 25;
    unsafe extern "C" {
        fn setsockopt(
            fd: i32,
            level: i32,
            option: i32,
            value: *const std::ffi::c_void,
            length: u32,
        ) -> i32;
    }
    let name = std::ffi::CString::new(name).map_err(|_| "网卡名称不能包含 NUL 字符")?;
    let result = unsafe {
        setsockopt(
            socket.as_raw_fd(),
            SOL_SOCKET,
            SO_BINDTODEVICE,
            name.as_ptr().cast(),
            name.as_bytes_with_nul().len() as u32,
        )
    };
    if result != 0 {
        return Err(format!("绑定网卡失败：{}", std::io::Error::last_os_error()));
    }
    Ok(())
}

fn broadcast(s: &UdpSocket, bytes: &[u8]) -> Result<(), String> {
    s.send_to(
        bytes,
        SocketAddr::V4(SocketAddrV4::new(BROADCAST, DEST_PORT)),
    )
    .map(|_| ())
    .map_err(|e| format!("发送广播失败：{e}"))
}
fn scan(interface: Option<&str>) -> Result<(), String> {
    let s = socket(interface)?;
    broadcast(&s, &[0; 5])?;
    println!("扫描设备中（监听 UDP {LOCAL_PORT}，等待 {TIMEOUT:?}）…");
    let mut buf = [0u8; 2048];
    let mut found = 0;
    loop {
        match s.recv_from(&mut buf) {
            Ok((n, from)) => {
                if n >= 8 && buf[0] == 0 && buf[1] == 1 {
                    let mac = format_scanned_mac(&buf[2..7]);
                    println!("设备：{from}  MAC：{mac}");
                    found += 1;
                } else {
                    println!("收到来自 {from} 的非扫描响应（{n} 字节）");
                }
            }
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                break
            }
            Err(e) => return Err(format!("接收失败：{e}")),
        }
    }
    if found == 0 {
        println!("未发现设备。请检查网卡、广播及防火墙设置。");
    }
    Ok(())
}
fn read_config(mac: [u8; 6], interface: Option<&str>, raw: bool) -> Result<(), String> {
    let s = socket(interface)?;
    let mut packet = vec![0x0a];
    packet.extend_from_slice(&mac[1..]);
    packet.push(0x0a);
    packet.extend_from_slice(&[0xff; 4]);
    broadcast(&s, &packet)?;
    let mut buf = [0u8; 2048];
    loop {
        let (n, from) = s
            .recv_from(&mut buf)
            .map_err(|e| format!("读取超时或接收失败：{e}"))?;
        if n >= 256 {
            print_config(&buf[..256]);
            if raw {
                print_raw(&buf[..n.min(buf.len())]);
            }
            println!("设备地址：{from}");
            return Ok(());
        }
        eprintln!("收到来自 {from} 的短响应（{n} 字节）：{}", hex(&buf[..n]));
    }
}
fn print_config(b: &[u8]) {
    let modes = [
        "TCP 单连接服务器",
        "TCP 客户端",
        "UDP 服务器",
        "MODBUS 服务器",
        "UDP 客户端",
        "MODBUS 客户端",
        "TCP 多连接服务器",
        "自建 MQTT 服务器",
        "蚂蚁云开平台",
        "第三方云平台",
    ];
    let ip = |i| Ipv4Addr::new(b[i], b[i + 1], b[i + 2], b[i + 3]).to_string();
    let port = u16::from_le_bytes([b[8], b[9]]);
    let mac = format!("{}:{}", hex(&b[60..62]), hex(&b[56..60]));
    println!("设备配置（读取响应 256 字节）\n  工作模式：{}（{}）\n  端口：{port}\n  本地 IP：{}\n  目标 IP：{}\n  网关：{}\n  子网掩码：{}\n  MAC：{}\n  DHCP：{}\n  心跳：{}",
        b[0], modes.get(b[0] as usize).copied().unwrap_or("未知"), ip(40), ip(44), ip(48), ip(52),
        mac, if b[254] == 1 {"开启"} else {"关闭"}, if b[252] == 1 {"开启"} else {"关闭"});
    let dns_len = (b[130] as usize).min(56);
    let id_len = (b[180] as usize).min(12);
    println!(
        "  DNS 网址：{}\n  唯一 ID：{}",
        String::from_utf8_lossy(&b[131..131 + dns_len]),
        String::from_utf8_lossy(&b[181..181 + id_len])
    );
}

fn print_raw(bytes: &[u8]) {
    println!("原始数据（偏移 / 十六进制 / ASCII）：");
    for (base, chunk) in bytes.chunks(16).enumerate() {
        let offset = base * 16;
        let hex_part = chunk
            .iter()
            .map(|b| format!("{b:02X}"))
            .collect::<Vec<_>>()
            .join(" ");
        let ascii = chunk
            .iter()
            .map(|b| {
                if b.is_ascii_graphic() || *b == b' ' {
                    *b as char
                } else {
                    '.'
                }
            })
            .collect::<String>();
        println!("{offset:03X}: {hex_part:<47} {ascii}");
    }
}

fn configure(mac: [u8; 6], payload: Vec<u8>, interface: Option<&str>) -> Result<(), String> {
    let s = socket(interface)?;
    let mut packet = vec![payload[0]];
    packet.extend_from_slice(&mac[1..]);
    packet.extend_from_slice(&payload);
    broadcast(&s, &packet)?;
    match s.recv_from(&mut [0u8; 512]) {
        Ok((n, from)) => println!("收到 {from} 的响应（{n} 字节）"),
        Err(e)
            if e.kind() == std::io::ErrorKind::WouldBlock
                || e.kind() == std::io::ErrorKind::TimedOut =>
        {
            eprintln!("配置响应超时；仍将尝试发送保存命令。")
        }
        Err(e) => return Err(format!("接收配置响应失败：{e}")),
    }
    let mut save = vec![0xff];
    save.extend_from_slice(&mac[1..]);
    save.extend_from_slice(&[0xff; 4]);
    broadcast(&s, &save)?;
    println!("保存命令已发送。");
    Ok(())
}
fn make_setting(key: Setting, v: &[String]) -> Result<Vec<u8>, String> {
    let one = || {
        if v.len() == 1 {
            Ok(v[0].as_str())
        } else {
            Err("此参数只接受一个值".to_string())
        }
    };
    let mut out = match key {
        Setting::Ip | Setting::Target | Setting::Gateway | Setting::Netmask | Setting::Dns => {
            let ip: Ipv4Addr = one()?.parse().map_err(|_| "IPv4 地址无效")?;
            let cmd = match key {
                Setting::Ip => 1,
                Setting::Target => 2,
                Setting::Gateway => 3,
                Setting::Netmask => 7,
                _ => 0x19,
            };
            let mut x = vec![cmd];
            x.extend_from_slice(&ip.octets());
            x
        }
        Setting::Mode => {
            let n: u8 = one()?.parse().map_err(|_| "模式须为 0 到 9")?;
            if n > 9 {
                return Err("模式须为 0 到 9".into());
            }
            vec![4, n, 0xff, 0xff]
        }
        Setting::Port => {
            let n: u16 = one()?.parse().map_err(|_| "端口须为 1 到 65535")?;
            if n == 0 {
                return Err("端口不能为 0".into());
            }
            vec![5, (n & 255) as u8, (n >> 8) as u8, 0xff, 0xff]
        }
        Setting::Dhcp => vec![9, parse_switch(one()?)?, 0xff, 0xff],
        Setting::Heartbeat => vec![0x0c, parse_switch(one()?)?],
        Setting::Mac => {
            let m = parse_mac(one()?)?;
            let mut x = vec![0xf8];
            x.extend_from_slice(&m);
            x
        }
        Setting::Id | Setting::Hostname => {
            let text = v.join(" ");
            let max = if key == Setting::Id { 12 } else { 56 };
            if text.as_bytes().len() > max {
                return Err(format!("文本最多 {max} 字节"));
            }
            let mut x = vec![if key == Setting::Id { 0x12 } else { 0x20 }];
            if key == Setting::Id {
                x.push(text.len() as u8)
            }
            x.extend_from_slice(text.as_bytes());
            x
        }
    };
    // Setting command uses its command byte as the leading byte; trailing bytes are payload.
    Ok(std::mem::take(&mut out))
}
fn parse_switch(s: &str) -> Result<u8, String> {
    match s {
        "0" => Ok(0),
        "1" => Ok(1),
        _ => Err("值只能是 0 或 1".into()),
    }
}
fn parse_mac(s: &str) -> Result<[u8; 6], String> {
    let parts: Vec<_> = s.split(|c| c == ':' || c == '-').collect();
    if parts.len() != 6 {
        return Err("MAC 地址格式应为 00:11:22:33:44:55".into());
    }
    let mut m = [0; 6];
    for (i, p) in parts.iter().enumerate() {
        m[i] = u8::from_str_radix(p, 16).map_err(|_| "MAC 地址包含无效字节")?;
    }
    Ok(m)
}
fn format_mac(b: &[u8]) -> String {
    b.iter()
        .map(|x| format!("{x:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}

fn format_scanned_mac(suffix: &[u8]) -> String {
    let mut mac = [0; 6];
    mac[1..].copy_from_slice(suffix);
    format_mac(&mac)
}
fn hex(b: &[u8]) -> String {
    b.iter()
        .map(|x| format!("{x:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}

#[cfg(test)]
mod mqtt_tests {
    use super::*;

    fn sample_config() -> MqttConfig {
        MqttConfig {
            username: "user-example".into(),
            password: "not-a-real-password;hmacsha256".into(),
            subscribe_topic: "SFQ257YC4K/WH-01/control".into(),
            publish_topic: "SFQ257YC4K/WH-01/event".into(),
            device_id: "SFQ257YC4KWH-01".into(),
        }
    }

    #[test]
    fn mqtt_save_layout_matches_capture_lengths_and_round_trips() {
        let mac = [0x00, 0x90, 0xe2, 0xd7, 0x20, 0x60];
        let save = encode_mqtt_save(mac, &sample_config()).unwrap();
        assert_eq!(save.len(), MQTT_PACKET_LEN);
        assert_eq!(&save[..2], &[0x44, 0xaa]);
        assert_eq!(&save[2..14], b"0090E2D72060");
        assert_eq!(save[14], 12);
        assert_eq!(save[115], 30);
        assert_eq!(save[216], 24);
        assert_eq!(save[317], 22);
        assert_eq!(save[418], 15);
        assert_eq!(save[MQTT_TRAILER_OFFSET], 0xff);
        assert_eq!(&save[MQTT_PACKET_LEN - 2..], &[0xaa, 0x44]);

        let mut response = save;
        response[..2].copy_from_slice(&[0x33, 0xbb]);
        response[MQTT_PACKET_LEN - 2..].copy_from_slice(&[0xbb, 0x33]);
        assert_eq!(
            parse_mqtt_response(&response, mac).unwrap(),
            sample_config()
        );
    }

    #[test]
    fn mqtt_fields_reject_values_larger_than_observed_slots() {
        let mac = [0x00, 0x90, 0xe2, 0xd7, 0x20, 0x60];
        let mut config = sample_config();
        config.subscribe_topic = "x".repeat(MQTT_FIELD_SLOTS[2]);
        assert!(encode_mqtt_save(mac, &config).is_err());
    }

    #[test]
    fn mqtt_set_requires_plaintext_credentials_as_arguments() {
        let common = [
            "corxnet-config",
            "mqtt-set",
            "00:90:E2:D7:20:60",
            "--subscribe-topic",
            "control",
            "--publish-topic",
            "event",
            "--device-id",
            "device-01",
        ];
        let mut direct = common.to_vec();
        direct.extend(["--username", "user", "--password", "pass"]);
        assert!(Cli::try_parse_from(direct).is_ok());

        let mut incomplete = common.to_vec();
        incomplete.extend(["--username", "user"]);
        assert!(Cli::try_parse_from(incomplete).is_err());
    }

    #[test]
    fn cli_mac_arguments_require_full_addresses_and_scan_displays_full_mac() {
        assert_eq!(
            parse_full_device_mac("00:90:E2:D7:20:60").unwrap(),
            [0x00, 0x90, 0xe2, 0xd7, 0x20, 0x60]
        );
        assert!(parse_full_device_mac("90:E2:D7:20:60").is_err());
        assert_eq!(
            format_scanned_mac(&[0x90, 0xe2, 0xd7, 0x20, 0x60]),
            "00:90:E2:D7:20:60"
        );
    }

    #[test]
    #[ignore = "向指定控制器重发当前 MQTT 配置；若读回不同会恢复原始帧"]
    fn mqtt_live_save_keeps_or_restores_original_config() {
        let mac = [0x00, 0x90, 0xe2, 0xd7, 0x20, 0x60];
        let socket = socket(Some("br0")).unwrap();
        let (original, _) = receive_mqtt_config(&socket, mac).unwrap();
        let original_response = {
            broadcast(&socket, &encode_mqtt_read(mac)).unwrap();
            let mut buf = [0u8; 2048];
            loop {
                let (len, _) = socket.recv_from(&mut buf).unwrap();
                if let Ok(config) = parse_mqtt_response(&buf[..len], mac) {
                    assert_eq!(config, original, "设备读回配置前后发生变化；未发送写入帧");
                    break buf[..len].to_vec();
                }
            }
        };
        let restore_packet = mqtt_save_from_response(&original_response).unwrap();
        assert_eq!(
            encode_mqtt_save(mac, &original).unwrap(),
            restore_packet,
            "本机编码与设备当前帧布局不一致；未发送写入帧"
        );

        broadcast(&socket, &restore_packet).unwrap();
        std::thread::sleep(Duration::from_millis(250));
        let after_write = receive_mqtt_config(&socket, mac);
        if !matches!(after_write, Ok((ref value, _)) if value == &original) {
            broadcast(&socket, &restore_packet).unwrap();
            std::thread::sleep(Duration::from_millis(250));
            let restored = receive_mqtt_config(&socket, mac)
                .expect("已发送原始 MQTT 配置恢复帧，但无法读取设备确认恢复结果");
            assert_eq!(restored.0, original, "设备 MQTT 配置恢复核验失败");
            panic!("MQTT 写入测试导致配置变化；原始配置已恢复并核验");
        }
    }
}
