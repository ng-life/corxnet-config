use std::{
    ffi::CString,
    net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket},
    os::fd::AsRawFd,
    process,
    time::Duration,
};

use clap::{CommandFactory, Parser, Subcommand, ValueEnum};
use clap_complete::{generate, Shell};

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
        #[arg(
            value_name = "MAC或后5字节",
            help = "设备完整 MAC 或协议返回的 MAC 后五字节"
        )]
        mac: String,
        #[arg(long, help = "打印完整 256 字节响应")]
        raw: bool,
    },
    #[command(about = "设置一项设备参数并保存")]
    Set {
        #[arg(value_name = "MAC或后5字节")]
        mac: String,
        #[arg(value_enum, value_name = "参数")]
        setting: Setting,
        #[arg(required = true, num_args = 1.., value_name = "值")]
        value: Vec<String>,
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
            read_config(parse_device_mac(&mac)?, cli.interface.as_deref(), raw)
        }
        Commands::Set {
            mac,
            setting,
            value,
        } => configure(
            parse_device_mac(&mac)?,
            make_setting(setting, &value)?,
            cli.interface.as_deref(),
        ),
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

fn socket(interface: Option<&str>) -> Result<UdpSocket, String> {
    let s = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, LOCAL_PORT))
        .map_err(|e| format!("绑定 UDP 本地端口 {LOCAL_PORT} 失败：{e}"))?;
    if let Some(name) = interface {
        bind_interface(&s, name)?;
    }
    s.set_broadcast(true).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(TIMEOUT))
        .map_err(|e| e.to_string())?;
    Ok(s)
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
    let name = CString::new(name).map_err(|_| "网卡名称不能包含 NUL 字符")?;
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

#[cfg(not(target_os = "linux"))]
fn bind_interface(_socket: &UdpSocket, _name: &str) -> Result<(), String> {
    Err("指定网卡目前仅支持 Linux".into())
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
                    let mac = format_mac(&buf[2..7]);
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
fn parse_device_mac(s: &str) -> Result<[u8; 6], String> {
    let parts: Vec<_> = s.split(|c| c == ':' || c == '-').collect();
    if parts.len() == 5 {
        let mut mac = [0u8; 6];
        for (i, part) in parts.iter().enumerate() {
            mac[i + 1] = u8::from_str_radix(part, 16).map_err(|_| "MAC 地址后五字节包含无效值")?;
        }
        Ok(mac)
    } else {
        parse_mac(s)
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
fn hex(b: &[u8]) -> String {
    b.iter()
        .map(|x| format!("{x:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}
