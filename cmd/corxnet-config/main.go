package main

import (
	"context"
	"os"
	"os/signal"

	"corxnet-config/internal/configtool"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	code := configtool.Run(ctx, os.Args[1:], os.Stdin, os.Stdout, os.Stderr)
	stop()
	os.Exit(code)
}
