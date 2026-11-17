package exitnode

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"
)

// fixturePath is read by the Kotlin suite too (ExitNodeStatsTest), so a change
// to the stats JSON is a contract change that fails on whichever side was not
// updated. Regenerate with OTC_UPDATE_FIXTURES=1 go test -run TestStatsContract.
const fixturePath = "../testdata/exitnode_stats.json"

func contractSample() Stats {
	return Stats{
		Schema:           StatsSchema,
		State:            StateRunning,
		Admission:        "approval",
		StartedUnixSec:   1800000000,
		ExpiresUnixSec:   1800003600,
		ActiveTCP:        2,
		ActiveUDP:        1,
		TotalTCP:         41,
		TotalUDP:         9,
		BytesFromGuests:  120345,
		BytesToGuests:    9876543,
		PolicyRejections: 3,
		CapRejections:    0,
		DialFailures:     5,
		UDPOversizeDrops: 1,
		Pending: []PendingStat{{
			Key:          "nodekey:00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
			FirstSeenSec: 1800000100,
			LastSeenSec:  1800000160,
		}},
		Guests: []GuestStat{{
			Key:           "nodekey:ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100",
			Approved:      true,
			FirstSeenSec:  1800000020,
			LastActiveSec: 1800000170,
			ActiveTCP:     2,
			ActiveUDP:     1,
			Flows:         50,
			BytesFrom:     120345,
			BytesTo:       9876543,
		}},
	}
}

func TestStatsContract(t *testing.T) {
	got, err := json.MarshalIndent(contractSample(), "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	got = append(got, '\n')
	if os.Getenv("OTC_UPDATE_FIXTURES") == "1" {
		if err := os.WriteFile(fixturePath, got, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("stats JSON no longer matches %s; this is a contract change:\n%s", fixturePath, got)
	}
}
