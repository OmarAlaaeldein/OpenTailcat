package exitnode

import (
	"encoding/json"
	"sort"
	"time"

	"tailscale.com/types/key"
)

// StatsSchema is bumped when the JSON shape changes incompatibly.
const StatsSchema = 1

// Stats is a point-in-time view of the node. Every number is counted from real
// flows; none is estimated or synthesized.
type Stats struct {
	Schema          int    `json:"schema"`
	State           State  `json:"state"`
	Admission       string `json:"admission"`
	StartedUnixSec  int64  `json:"startedUnixSec"`
	ExpiresUnixSec  int64  `json:"expiresUnixSec"`
	ActiveTCP       int64  `json:"activeTcp"`
	ActiveUDP       int64  `json:"activeUdp"`
	TotalTCP        uint64 `json:"totalTcp"`
	TotalUDP        uint64 `json:"totalUdp"`
	BytesFromGuests uint64 `json:"bytesFromGuests"`
	BytesToGuests   uint64 `json:"bytesToGuests"`
	// PolicyRejections are flows refused by the destination policy (host
	// network, multicast, ...). CapRejections hit MaxTCP/MaxUDP. DialFailures
	// reached the Internet side and failed.
	PolicyRejections uint64 `json:"policyRejections"`
	CapRejections    uint64 `json:"capRejections"`
	DialFailures     uint64 `json:"dialFailures"`
	UDPOversizeDrops uint64 `json:"udpOversizeDrops"`

	Pending []PendingStat `json:"pending"`
	Guests  []GuestStat   `json:"guests"`
}

// PendingStat is a device waiting for the host's decision.
type PendingStat struct {
	Key          string `json:"key"`
	FirstSeenSec int64  `json:"firstSeenUnixSec"`
	LastSeenSec  int64  `json:"lastSeenUnixSec"`
}

// GuestStat is an admitted device.
type GuestStat struct {
	Key           string `json:"key"`
	Approved      bool   `json:"approved"`
	FirstSeenSec  int64  `json:"firstSeenUnixSec"`
	LastActiveSec int64  `json:"lastActiveUnixSec"`
	ActiveTCP     int64  `json:"activeTcp"`
	ActiveUDP     int64  `json:"activeUdp"`
	Flows         uint64 `json:"flows"`
	BytesFrom     uint64 `json:"bytesFrom"`
	BytesTo       uint64 `json:"bytesTo"`
}

// Stats returns the current view. Pending requests that have gone quiet are
// pruned first.
func (n *Node) Stats() Stats {
	n.mu.Lock()
	defer n.mu.Unlock()
	now := n.now()
	n.prunePendingLocked(now)

	adm := "approval"
	if n.cfg.Admission == AdmitOpen {
		adm = "open"
	}
	s := Stats{
		Schema:           StatsSchema,
		State:            n.state,
		Admission:        adm,
		StartedUnixSec:   n.started.Unix(),
		ExpiresUnixSec:   n.expires.Unix(),
		ActiveTCP:        n.activeTCP.Load(),
		ActiveUDP:        n.activeUDP.Load(),
		TotalTCP:         n.totalTCP.Load(),
		TotalUDP:         n.totalUDP.Load(),
		BytesFromGuests:  n.bytesFromPeers.Load(),
		BytesToGuests:    n.bytesToPeer.Load(),
		PolicyRejections: n.rejPolicy.Load(),
		CapRejections:    n.rejCap.Load(),
		DialFailures:     n.rejDial.Load(),
		UDPOversizeDrops: n.udpOversizeDrop.Load(),
		Pending:          []PendingStat{},
		Guests:           []GuestStat{},
	}
	for k, p := range n.pending {
		s.Pending = append(s.Pending, PendingStat{
			Key:          k.String(),
			FirstSeenSec: p.firstSeen.Unix(),
			LastSeenSec:  p.lastSeen.Unix(),
		})
	}
	sort.Slice(s.Pending, func(i, j int) bool { return s.Pending[i].FirstSeenSec < s.Pending[j].FirstSeenSec })

	for k, p := range n.peers {
		if k.IsZero() {
			continue // flows whose device could not be resolved stay in the totals only
		}
		_, ok := n.allowed[k]
		s.Guests = append(s.Guests, guestStat(k, p, ok))
	}
	// A device admitted but not yet active still deserves a row.
	for k := range n.allowed {
		if _, ok := n.peers[k]; !ok {
			s.Guests = append(s.Guests, GuestStat{Key: k.String(), Approved: true})
		}
	}
	sort.Slice(s.Guests, func(i, j int) bool { return s.Guests[i].Key < s.Guests[j].Key })
	return s
}

func guestStat(k key.NodePublic, p *peerStats, approved bool) GuestStat {
	return GuestStat{
		Key:           k.String(),
		Approved:      approved,
		FirstSeenSec:  p.firstSeen.Unix(),
		LastActiveSec: p.lastActive.Load(),
		ActiveTCP:     p.activeTCP.Load(),
		ActiveUDP:     p.activeUDP.Load(),
		Flows:         p.flows.Load(),
		BytesFrom:     p.fromPeer.Load(),
		BytesTo:       p.toPeer.Load(),
	}
}

// StatsJSON is Stats encoded for the bridge.
func (n *Node) StatsJSON() (string, error) {
	b, err := json.Marshal(n.Stats())
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// TimeRemaining is how long until expiry, never negative.
func (n *Node) TimeRemaining() time.Duration {
	d := n.expires.Sub(n.now())
	if d < 0 {
		return 0
	}
	return d
}
