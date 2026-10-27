//go:build !linux && !windows

package main

import (
	"golang.org/x/sys/unix"
	"syscall"
)

func socketControl(_ string) func(string, string, syscall.RawConn) error {
	return func(_, _ string, raw syscall.RawConn) error {
		var err error
		e := raw.Control(func(fd uintptr) { err = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_BROADCAST, 1) })
		if e != nil {
			return e
		}
		return err
	}
}
