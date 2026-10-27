//go:build !linux

package main

import (
	"fmt"
	"net"
	"strings"
)

func localIP(name string) (net.IP, error) {
	if name == "" {
		return net.IPv4zero, nil
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	for _, i := range interfaces {
		if !strings.EqualFold(i.Name, name) {
			continue
		}
		addresses, e := i.Addrs()
		if e != nil {
			return nil, e
		}
		for _, a := range addresses {
			ip, _, e := net.ParseCIDR(a.String())
			if e == nil && ip.To4() != nil && !ip.IsUnspecified() {
				return ip.To4(), nil
			}
		}
		return nil, fmt.Errorf("网卡“%s”没有可用的 IPv4 地址", name)
	}
	return nil, fmt.Errorf("找不到网卡“%s”；请先查看本机网卡名称", name)
}
