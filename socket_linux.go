//go:build linux

package main

import (
	"golang.org/x/sys/unix"
	"net"
	"syscall"
)

func localIP(name string) (net.IP, error) { return net.IPv4zero, nil }
func socketControl(name string) func(string, string, syscall.RawConn) error {
	return func(_, _ string, raw syscall.RawConn) error {
		var err error
		e := raw.Control(func(fd uintptr) {
			err = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_BROADCAST, 1)
			if err == nil && name != "" {
				err = unix.SetsockoptString(int(fd), unix.SOL_SOCKET, unix.SO_BINDTODEVICE, name)
			}
		})
		if e != nil {
			return e
		}
		return err
	}
}
