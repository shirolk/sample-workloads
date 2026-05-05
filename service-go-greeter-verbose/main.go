// Copyright 2025 The OpenChoreo Authors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/rand"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

var requestCount int64

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelDebug,
	}))
	slog.SetDefault(logger)

	slog.Info("service starting", "version", "1.0.0", "port", 9090)

	mux := http.NewServeMux()
	mux.HandleFunc("/greeter/greet", withLogging(greetHandler))
	mux.HandleFunc("/greeter/random", withLogging(randomHandler))
	mux.HandleFunc("/health", withLogging(healthHandler))

	server := &http.Server{
		Addr:    ":9090",
		Handler: mux,
	}

	go backgroundStats()

	go func() {
		slog.Info("HTTP server listening", "addr", server.Addr)
		if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server failed", "error", err)
			os.Exit(1)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	slog.Info("shutdown signal received, draining connections")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(ctx); err != nil {
		slog.Error("graceful shutdown failed", "error", err)
	}
	slog.Info("shutdown complete")
}

// withLogging wraps a handler to log every request with timing and level escalation.
func withLogging(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		requestCount++
		slog.Debug("request received",
			"method", r.Method,
			"path", r.URL.Path,
			"remote", r.RemoteAddr,
			"request_id", requestCount,
		)

		rw := &responseWriter{ResponseWriter: w, status: http.StatusOK}
		next(rw, r)

		elapsed := time.Since(start)
		fields := []any{
			"method", r.Method,
			"path", r.URL.Path,
			"status", rw.status,
			"duration_ms", elapsed.Milliseconds(),
			"request_id", requestCount,
		}

		switch {
		case elapsed > 200*time.Millisecond:
			slog.Warn("slow request", fields...)
		case rw.status >= 500:
			slog.Error("request failed", fields...)
		case rw.status >= 400:
			slog.Warn("client error", fields...)
		default:
			slog.Info("request completed", fields...)
		}
	}
}

func greetHandler(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")

	if name == "" {
		slog.Warn("greet called without a name parameter, using default")
		name = "Stranger"
	} else {
		slog.Debug("greet called", "name", name)
	}

	if len(name) > 64 {
		slog.Warn("name parameter truncated", "original_length", len(name))
		name = name[:64]
	}

	lang := r.URL.Query().Get("lang")
	greeting, err := localizedGreeting(name, lang)
	if err != nil {
		slog.Error("unsupported language requested", "lang", lang, "error", err)
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	slog.Info("greeting generated", "name", name, "lang", lang)
	fmt.Fprintln(w, greeting)
}

func randomHandler(w http.ResponseWriter, r *http.Request) {
	greetings := []string{
		"Hello!", "Hi there!", "Hey!", "Howdy!", "Greetings!", "Good day!",
	}
	chosen := greetings[rand.Intn(len(greetings))]
	slog.Debug("random greeting selected", "greeting", chosen, "pool_size", len(greetings))

	// Simulate occasional slow processing.
	if rand.Intn(10) == 0 {
		slog.Warn("simulating slow processing for demo purposes")
		time.Sleep(250 * time.Millisecond)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"greeting": chosen})
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	slog.Debug("health check", "total_requests_served", requestCount)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"status":   "ok",
		"requests": requestCount,
	})
}

func localizedGreeting(name, lang string) (string, error) {
	templates := map[string]string{
		"":   "Hello, %s!",
		"en": "Hello, %s!",
		"es": "¡Hola, %s!",
		"fr": "Bonjour, %s!",
		"de": "Hallo, %s!",
		"jp": "こんにちは, %s!",
	}
	tmpl, ok := templates[lang]
	if !ok {
		return "", fmt.Errorf("unsupported language: %q (supported: en, es, fr, de, jp)", lang)
	}
	return fmt.Sprintf(tmpl, name), nil
}

func backgroundStats() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		slog.Info("periodic stats", "total_requests", requestCount, "uptime_check", "ok")
	}
}

type responseWriter struct {
	http.ResponseWriter
	status int
}

func (rw *responseWriter) WriteHeader(code int) {
	rw.status = code
	rw.ResponseWriter.WriteHeader(code)
}
