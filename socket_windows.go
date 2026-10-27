package main

import (
	"golang.org/x/sys/windows"
	"syscall"
)

func socketControl(_ string) func(string, string, syscall.RawConn) error {
	return func(_, _ string, raw syscall.RawConn) error {
		var err error
		e := raw.Control(func(fd uintptr) {
			err = windows.SetsockoptInt(windows.Handle(fd), windows.SOL_SOCKET, windows.SO_BROADCAST, 1)
		})
		if e != nil {
			return e
		}
		return err
	}
}
