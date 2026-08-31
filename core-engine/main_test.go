package engine

import (
	"encoding/json"
	"testing"
)

func TestScaffoldAdvertisesNoDataPlane(t *testing.T) {
	var capabilities struct {
		APIVersion int  `json:"apiVersion"`
		DataPlane  bool `json:"dataPlane"`
		WireGuard  bool `json:"wireGuard"`
		Magicsock  bool `json:"magicsock"`
	}
	if err := json.Unmarshal([]byte(GetCapabilitiesJSON()), &capabilities); err != nil {
		t.Fatalf("invalid capabilities JSON: %v", err)
	}
	if capabilities.APIVersion != 1 {
		t.Fatalf("unexpected API version: %d", capabilities.APIVersion)
	}
	if capabilities.DataPlane || capabilities.WireGuard || capabilities.Magicsock {
		t.Fatal("scaffold must not advertise unimplemented tunnel capabilities")
	}
}

func TestScaffoldCannotConnect(t *testing.T) {
	if err := Prepare("tc-placeholder"); err == nil {
		t.Fatal("scaffold unexpectedly reported a successful connection")
	}
	if err := AttachTun(42); err == nil {
		t.Fatal("scaffold unexpectedly attached a TUN")
	}
}

func TestDisconnectedStats(t *testing.T) {
	var stats EngineStats
	if err := json.Unmarshal([]byte(GetStatsJSON()), &stats); err != nil {
		t.Fatalf("invalid stats JSON: %v", err)
	}
	if stats.Transport != "DISCONNECTED" {
		t.Fatalf("unexpected transport: %s", stats.Transport)
	}
}
