package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHandlerReturnsExactPrivateTransportResponse(t *testing.T) {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "http://100.83.246.85/", nil)

	handler(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusOK)
	}
	if got, want := recorder.Body.String(), "IWS PRIVATE TRANSPORT POC OK"; got != want {
		t.Fatalf("body = %q, want %q", got, want)
	}
	if got, want := recorder.Header().Get("Content-Type"), "text/plain; charset=utf-8"; got != want {
		t.Fatalf("Content-Type = %q, want %q", got, want)
	}
}
