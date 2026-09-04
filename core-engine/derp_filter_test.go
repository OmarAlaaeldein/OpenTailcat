package engine

import (
	"encoding/json"
	"testing"

	"tailscale.com/tailcfg"
)

func TestFilterFetchedDERPMapStripsUnsafeNodes(t *testing.T) {
	raw, err := json.Marshal(&tailcfg.DERPMap{
		Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{
			1: {
				RegionID: 1,
				Nodes: []*tailcfg.DERPNode{
					{Name: "bad", HostName: "127.0.0.1.nip.io", IPv4: "1.2.3.4"},
					{Name: "ok", HostName: "derp.example", IPv4: "203.0.113.10"},
					{Name: "insecure", HostName: "derp.example", InsecureForTests: true},
				},
			},
			2: {
				RegionID: 2,
				Nodes: []*tailcfg.DERPNode{
					{Name: "loop", HostName: "localhost"},
				},
			},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	filtered, err := filterFetchedDERPMapJSON(raw)
	if err != nil {
		t.Fatalf("filter: %v", err)
	}
	var dm tailcfg.DERPMap
	if err := json.Unmarshal(filtered, &dm); err != nil {
		t.Fatal(err)
	}
	if _, ok := dm.Regions[2]; ok {
		t.Fatal("expected region 2 with only unsafe nodes to be removed")
	}
	reg := dm.Regions[1]
	if reg == nil || len(reg.Nodes) != 1 || reg.Nodes[0].Name != "ok" {
		t.Fatalf("expected only safe node kept, got %+v", reg)
	}
}

func TestFilterFetchedDERPMapRejectsEmpty(t *testing.T) {
	raw, err := json.Marshal(&tailcfg.DERPMap{
		Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{
			1: {RegionID: 1, Nodes: []*tailcfg.DERPNode{{HostName: "127.0.0.1"}}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := filterFetchedDERPMapJSON(raw); err == nil {
		t.Fatal("expected empty unsafe map to fail")
	}
}