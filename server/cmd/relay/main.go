package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/string1225/ble-talkie/server/relay"
)

func main() {
	cfg, err := relay.ConfigFromEnv()
	if err != nil {
		log.Fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err = run(ctx, cfg, func(addr string) { log.Printf("BLE Talkie relay ready on %s", addr) }); err != nil {
		log.Fatal(err)
	}
}

func run(ctx context.Context, cfg relay.Config, ready func(string)) error {
	handler := relay.New(cfg)
	defer handler.Close()
	if err := handler.Err(); err != nil {
		return err
	}
	addr := cfg.ListenAddr
	if addr == "" {
		addr = "127.0.0.1:18110"
	}
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	defer listener.Close()
	srv := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	done := make(chan error, 1)
	go func() { done <- srv.Serve(listener) }()
	if ready != nil {
		ready(listener.Addr().String())
	}
	select {
	case err := <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		_ = handler.Close()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdown); err != nil {
			_ = srv.Close()
			return err
		}
		err := <-done
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}
