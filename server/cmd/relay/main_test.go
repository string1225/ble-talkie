package main

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/string1225/ble-talkie/server/relay"
)

func TestRunBindsServesHealthAndStops(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ready := make(chan string, 1)
	done := make(chan error, 1)
	go func() { done <- run(ctx, relay.Config{ListenAddr: "127.0.0.1:0"}, func(addr string) { ready <- addr }) }()
	var addr string
	select {
	case addr = <-ready:
	case err := <-done:
		t.Fatalf("server exited before ready: %v", err)
	case <-time.After(3 * time.Second):
		t.Fatal("no readiness")
	}
	res, err := http.Get("http://" + addr + "/ble-talkie/healthz")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !strings.Contains(string(body), "ready") {
		t.Fatal("health not ready")
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("shutdown did not finish")
	}
}
