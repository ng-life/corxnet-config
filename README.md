# corxnet-config

用于科星控制器的 Rust UDP 网络配置命令行工具。设备扫描和读取不会更改参数；`set` 每次下发一项配置，并按协议发送保存命令。

## 构建和安装

需要 Rust 工具链。在项目目录构建：

```sh
cargo build --release
./target/release/corxnet-config --help
```

也可以安装到 Cargo 的可执行文件目录：

```sh
cargo install --path .
```

## 命令

```text
corxnet-config [-i|--interface <网卡>] scan
corxnet-config [-i|--interface <网卡>] read <MAC或后5字节> [--raw]
corxnet-config [-i|--interface <网卡>] set <MAC或后5字节> <参数> <值>
corxnet-config [-i|--interface <网卡>] mqtt-read <完整MAC> [--show-secrets]
corxnet-config [-i|--interface <网卡>] mqtt-set <完整MAC> (--credentials-stdin | --username <用户名> --password <密码>) --subscribe-topic <主题> --publish-topic <主题> --device-id <ID>
corxnet-config completions <bash|zsh|fish>
```

MQTT 配置的协议推测、读写命令和字段限制见 [docs/MQTT.md](docs/MQTT.md)。`mqtt-set` 会整体保存五个 MQTT 字段；测试和修改前应先读取并保留现有配置，以便必要时恢复。

`-i/--interface` 可放在命令前或后。Linux 可用 `ip link`、macOS 可用 `networksetup -listallhardwareports`、Windows PowerShell 可用 `Get-NetAdapter` 查看网卡名。未指定时使用系统路由选择的出口网卡。

程序支持 Linux x86_64、Windows x86_64 和 macOS Apple Silicon（arm64）。Linux 上 `-i` 按网卡名绑定 socket；Windows 和 macOS 上会查找该网卡的 IPv4 地址并绑定 socket。示例：

```powershell
.\corxnet-config.exe -i "以太网" scan
```

```sh
./corxnet-config -i en0 scan
```

## 扫描设备

```sh
corxnet-config -i br0 scan
```

工具向 `255.255.255.255:60000` 广播五个 `00` 字节，并在 UDP `60001` 接收响应。扫描结果包含设备 IP 和响应中的 MAC 后五字节。

## 读取配置

```sh
corxnet-config -i br0 read 90:E2:D7:20:60
corxnet-config read 00:90:E2:D7:20:60 --interface br0 --raw
```

`read` 使用 `0A + MAC后5字节 + 0A FF FF FF FF` 请求设备返回 256 字节配置。目标可填写完整 MAC，也可填写扫描结果中的后五字节。`--raw` 会在解析结果后打印原始响应的十六进制和 ASCII 数据。

## 设置参数

```sh
corxnet-config -i br0 set 90:E2:D7:20:60 ip 192.168.0.18
corxnet-config set 90:E2:D7:20:60 mode 0
corxnet-config set 90:E2:D7:20:60 port 50000
corxnet-config set 90:E2:D7:20:60 dhcp 0
corxnet-config set 90:E2:D7:20:60 id sensor-01
corxnet-config set 90:E2:D7:20:60 hostname broker.example.net
```

每条 `set` 只设置一个参数。字符串值含空格时用引号括起，例如 `id "sensor west"`。工具发送配置后尝试接收设备响应，再发送 `FF + MAC后5字节 + FF FF FF FF` 保存。更改 IP、DHCP 或 MAC 后设备地址可能改变；更改 MAC 后，后续命令应使用新 MAC。

支持的参数和协议命令：

| 参数 | 值 | 命令字 |
| --- | --- | --- |
| `ip` | IPv4 地址 | `01` |
| `target` | 目标 IPv4 地址 | `02` |
| `gateway` | 网关 IPv4 地址 | `03` |
| `mode` | `0`–`9` | `04` |
| `port` | `1`–`65535` | `05` |
| `netmask` | 子网掩码 | `07` |
| `mac` | 完整 MAC 地址 | `F8` |
| `dhcp` | `0` 静态，`1` DHCP | `09` |
| `id` | 最多 12 字节 | `12` |
| `dns` | DNS IPv4 地址 | `19` |
| `hostname` | DNS 网址，最多 56 字节 | `20` |
| `heartbeat` | `0` 关闭，`1` 开启 | `0C` |

模式编号：

| 值 | 模式 |
| --- | --- |
| `0` | TCP 单连接服务器 |
| `1` | TCP 客户端 |
| `2` | UDP 服务器 |
| `3` | MODBUS 服务器 |
| `4` | UDP 客户端 |
| `5` | MODBUS 客户端 |
| `6` | TCP 多连接服务器 |
| `7` | 自建 MQTT 服务器 |
| `8` | 蚂蚁云开平台 |
| `9` | 第三方云平台 |

端口字段低字节在前，例如 50000 编码为 `50 C3`。UDP 客户端模式的目标端口和本机端口须相同。服务器模式的端口是本地端口；客户端模式的端口是远程服务器端口。启用 DHCP 后无需单独配置 DNS。

## Shell 补全

补全脚本由 `clap_complete` 根据命令定义生成。以下命令假设已安装 `corxnet-config` 并且它位于 `PATH` 中；修改 CLI 后重新运行命令即可更新脚本。

### Bash

临时加载：

```sh
source <(corxnet-config completions bash)
```

持久安装（需要启用 `bash-completion`）：

```sh
mkdir -p ~/.local/share/bash-completion/completions
corxnet-config completions bash > ~/.local/share/bash-completion/completions/corxnet-config
```

### Zsh

```sh
mkdir -p ~/.zfunc
corxnet-config completions zsh > ~/.zfunc/_corxnet-config
```

在 `~/.zshrc` 加入以下内容，然后重新打开终端或执行 `source ~/.zshrc`：

```zsh
fpath=(~/.zfunc $fpath)
autoload -Uz compinit && compinit
```

### Fish

```fish
mkdir -p ~/.config/fish/completions
corxnet-config completions fish > ~/.config/fish/completions/corxnet-config.fish
```

## 网络与协议

程序使用 UDP 广播地址 `255.255.255.255`、设备端口 `60000` 和本机接收端口 `60001`。Linux 上 `-i/--interface` 将 socket 绑定到指定网卡；Windows 和 macOS 则绑定指定网卡上的 IPv4 地址。各系统防火墙需允许 UDP `60001` 入站。

扫描请求为五个 `00` 字节。网络配置请求格式为 `命令字 + MAC后5字节 + 配置指令`；MQTT 帧格式见 [docs/MQTT.md](docs/MQTT.md)。网络参数变更需发送保存命令；MQTT 写入使用其独立的 `44 AA ... AA 44` 保存帧。
