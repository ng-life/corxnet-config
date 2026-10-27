package main

import (
	"context"
	"fmt"
	"io"
	"net/netip"
	"os"
	"os/signal"
	"strings"

	"github.com/spf13/cobra"
)

const version = "0.6.0"

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	code := Run(ctx, os.Args[1:], os.Stdin, os.Stdout, os.Stderr)
	stop()
	os.Exit(code)
}
func Run(ctx context.Context, args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	root := newCommand(nil)
	root.SetIn(stdin)
	root.SetOut(stdout)
	root.SetErr(stderr)
	root.SetArgs(args)
	if e := root.ExecuteContext(ctx); e != nil {
		fmt.Fprintf(stderr, "错误：%s\n", e)
		return 2
	}
	return 0
}
func newCommand(client deviceClient) *cobra.Command {
	var iface string
	root := &cobra.Command{Use: "corxnet-config", Short: "UDP 控制器网络配置工具", Version: version, SilenceErrors: true, SilenceUsage: true, Args: cobra.NoArgs}
	root.RunE = func(cmd *cobra.Command, _ []string) error { _ = cmd.Help(); return fmt.Errorf("请指定子命令") }
	root.CompletionOptions.DisableDefaultCmd = true
	root.Flags().BoolP("version", "V", false, "显示版本")
	root.PersistentFlags().StringVarP(&iface, "interface", "i", "", "指定收发广播的网卡")
	device := func() deviceClient {
		if client != nil {
			return client
		}
		return udpClient{iface: iface}
	}
	root.AddCommand(&cobra.Command{Use: "scan", Short: "扫描局域网设备", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, _ []string) error {
		ds, e := device().Discover(cmd.Context())
		if e != nil {
			return e
		}
		for _, d := range ds {
			fmt.Fprintf(cmd.OutOrStdout(), "设备：%s  MAC：%s\n", d.Address, d.MAC)
		}
		if len(ds) == 0 {
			fmt.Fprintln(cmd.OutOrStdout(), "未发现设备。请检查网卡、广播及防火墙设置。")
		}
		return nil
	}})
	var raw bool
	read := &cobra.Command{Use: "read <完整MAC>", Short: "读取设备网络配置", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, a []string) error {
		m, e := parseMAC(a[0])
		if e != nil {
			return e
		}
		b, address, e := device().Fetch(cmd.Context(), m)
		if e != nil {
			return e
		}
		printConfig(cmd.OutOrStdout(), parseNetworkConfig(b))
		if raw {
			printRaw(cmd.OutOrStdout(), b)
		}
		fmt.Fprintln(cmd.OutOrStdout(), "设备地址："+address)
		return nil
	}}
	read.Flags().BoolVar(&raw, "raw", false, "打印完整 256 字节响应")
	root.AddCommand(read)
	set := &cobra.Command{Use: "set <完整MAC> <参数> <值...>", Short: "设置一项设备参数并保存", Args: cobra.MinimumNArgs(3), RunE: func(cmd *cobra.Command, a []string) error {
		m, e := parseMAC(a[0])
		if e != nil {
			return e
		}
		p, e := makeSetting(a[1], a[2:])
		if e != nil {
			return e
		}
		message, e := device().Apply(cmd.Context(), m, p)
		if e == nil {
			fmt.Fprintln(cmd.OutOrStdout(), message)
		}
		return e
	}}
	set.ValidArgsFunction = func(_ *cobra.Command, a []string, p string) ([]string, cobra.ShellCompDirective) {
		if len(a) == 1 {
			return settings, cobra.ShellCompDirectiveNoFileComp
		}
		if len(a) == 2 {
			switch a[1] {
			case "mode":
				return []string{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"}, cobra.ShellCompDirectiveNoFileComp
			case "dhcp", "heartbeat":
				return []string{"0", "1"}, cobra.ShellCompDirectiveNoFileComp
			}
		}
		return nil, cobra.ShellCompDirectiveNoFileComp
	}
	root.AddCommand(set)
	root.AddCommand(&cobra.Command{Use: "mqtt-read <完整MAC>", Short: "读取设备 MQTT 配置", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, a []string) error {
		m, e := parseMAC(a[0])
		if e != nil {
			return e
		}
		c, address, e := device().ReadMQTT(cmd.Context(), m)
		if e != nil {
			return e
		}
		fmt.Fprintf(cmd.OutOrStdout(), "MQTT 配置（设备 %s）\n  用户名：%s\n  密码：%s\n  订阅主题：%s\n  发布主题：%s\n  设备 ID：%s\n", address, terminalText(c.Username), terminalText(c.Password), terminalText(c.SubscribeTopic), terminalText(c.PublishTopic), terminalText(c.DeviceID))
		return nil
	}})
	var config MQTTConfig
	mqttSet := &cobra.Command{Use: "mqtt-set <完整MAC>", Short: "保存设备 MQTT 配置并复读核验", Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, a []string) error {
		m, e := parseMAC(a[0])
		if e != nil {
			return e
		}
		if _, e = encodeMQTT(m, config); e != nil {
			return e
		}
		_, e = device().WriteMQTT(cmd.Context(), m, config)
		if e == nil {
			fmt.Fprintln(cmd.OutOrStdout(), "保存完成，复读核验一致。")
		}
		return e
	}}
	for _, f := range []struct {
		name, help string
		target     *string
	}{{"username", "用户名", &config.Username}, {"password", "密码", &config.Password}, {"subscribe-topic", "订阅主题", &config.SubscribeTopic}, {"publish-topic", "发布主题", &config.PublishTopic}, {"device-id", "设备 ID", &config.DeviceID}} {
		mqttSet.Flags().StringVar(f.target, f.name, "", f.help)
		_ = mqttSet.MarkFlagRequired(f.name)
	}
	root.AddCommand(mqttSet)
	var listen string
	serve := &cobra.Command{Use: "serve", Short: "启动内嵌网页和 HTTP 配置服务", Args: cobra.NoArgs, RunE: func(cmd *cobra.Command, _ []string) error {
		if _, e := netip.ParseAddrPort(listen); e != nil {
			return fmt.Errorf("HTTP 监听地址须为 IP:端口：%w", e)
		}
		return serveHTTP(cmd.Context(), listen, iface, device(), cmd.ErrOrStderr())
	}}
	serve.Flags().StringVar(&listen, "listen", "127.0.0.1:8080", "HTTP 监听地址")
	root.AddCommand(serve)
	root.AddCommand(&cobra.Command{Use: "completions <bash|zsh|fish>", Short: "生成 shell 补全脚本", ValidArgs: []string{"bash", "zsh", "fish"}, Args: cobra.ExactValidArgs(1), RunE: func(cmd *cobra.Command, a []string) error {
		switch a[0] {
		case "bash":
			return root.GenBashCompletionV2(cmd.OutOrStdout(), true)
		case "zsh":
			return root.GenZshCompletion(cmd.OutOrStdout())
		case "fish":
			return root.GenFishCompletion(cmd.OutOrStdout(), true)
		}
		return nil
	}})
	return root
}
func printConfig(w io.Writer, c NetworkConfig) {
	modes := []string{"TCP 单连接服务器", "TCP 客户端", "UDP 服务器", "MODBUS 服务器", "UDP 客户端", "MODBUS 客户端", "TCP 多连接服务器", "自建 MQTT 服务器", "蚂蚁云开平台", "第三方云平台"}
	mode := "未知"
	if int(c.Mode) < len(modes) {
		mode = modes[c.Mode]
	}
	switchText := func(b byte) string {
		if b == 1 {
			return "开启"
		}
		return "关闭"
	}
	fmt.Fprintf(w, "设备配置（读取响应 256 字节）\n  工作模式：%d（%s）\n  端口：%d\n  本地 IP：%s\n  目标 IP：%s\n  网关：%s\n  子网掩码：%s\n  MAC：%s\n  DHCP：%s\n  心跳：%s\n  DNS 网址：%s\n  唯一 ID：%s\n", c.Mode, mode, c.Port, c.IP, c.Target, c.Gateway, c.Netmask, c.MAC, switchText(c.DHCP), switchText(c.Heartbeat), terminalText(c.Hostname), terminalText(c.ID))
}
func terminalText(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r < 32 || r == 127 {
			fmt.Fprintf(&b, "\\u%04x", r)
		} else {
			b.WriteRune(r)
		}
	}
	return b.String()
}
func printRaw(w io.Writer, b []byte) {
	fmt.Fprintln(w, "原始数据（偏移 / 十六进制 / ASCII）：")
	for offset := 0; offset < len(b); offset += 16 {
		chunk := b[offset:min(offset+16, len(b))]
		a := make([]byte, len(chunk))
		for i, v := range chunk {
			a[i] = '.'
			if v >= 32 && v <= 126 {
				a[i] = v
			}
		}
		fmt.Fprintf(w, "%03X: %-47s %s\n", offset, hexText(chunk), a)
	}
}
