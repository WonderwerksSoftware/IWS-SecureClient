package main

import (
	"log"
	"net/http"
	"time"
)

const (
	listenAddress = "100.83.246.85:443"
	responseBody  = "IWS PRIVATE TRANSPORT POC OK"
)

func handler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(responseBody))
}

func main() {
	server := &http.Server{
		Addr:              listenAddress,
		Handler:           http.HandlerFunc(handler),
		ReadHeaderTimeout: 5 * time.Second,
		WriteTimeout:      5 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}
