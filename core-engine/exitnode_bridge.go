package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/types/key"

	"com.tailcat.vpn/engine/exitnode"
)

// Exit-node bridge: the phone as a gateway. The node itself lives in package
// exitnode (the only place allowed to open OS sockets for forwarded traffic);
// this file is only the gomobile surface and the lifecycle glue.
//
// Mutual exclusion with the client tunnel is enforced here, natively, and not
// only in the UI: with the client's default route installed the node's own
// outbound dials would be captured and loop back through the phone.
// Prepare refuses while the node runs, and StartExitNode refuses while the
// client engine is anywhere past STOPPED/FAILED. Both decisions are taken
// under globalCore.mu, so they cannot interleave.

const (
	maxExitNodeConfigBytes = 1024
	exitNodeStartTimeout   = 30 * time.Second
)

// exitNodeClaim is true from the moment StartExitNode wins the engine until
// the node has ended. It is read and written only with globalCore.mu held.
var exitNodeClaim atomic.Bool

// exitNodeConfigHook lets tests substitute fields the bridge does not expose
// (for example a local DERP region). Nil in production.
var exitNodeConfigHook func(*exitnode.Config)

var exitHost struct {
	mu sync.Mutex
	// node is kept after it ends, so stats can still report EXPIRED or STOPPED
	// instead of an error. The next start replaces it.
	node *exitnode.Node
	// swapped records the protector installed for the node so it can be
	// undone, and what it replaced.
	swapped  bool
	previous SocketProtector
	ours     SocketProtector
}

type exitNodeConfigJSON struct {
	TTLSeconds int64  `json:"ttlSeconds"`
	Admission  string `json:"admission"`
}

// allowAllProtector accepts every socket. The client tunnel is not running
// while the node is (see above), so there is no VPN to route around and
// nothing for VpnService.protect to do; the hook only needs to not refuse.
type allowAllProtector struct{}

func (allowAllProtector) Protect(int) bool { return true }

func parseExitNodeConfig(raw string) (exitnode.Config, error) {
	if len(raw) > maxExitNodeConfigBytes {
		return exitnode.Config{}, errors.New("exit node config is too large")
	}
	var in exitNodeConfigJSON
	dec := json.NewDecoder(bytes.NewReader([]byte(raw)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&in); err != nil {
		return exitnode.Config{}, errors.New("exit node config is not valid")
	}
	if dec.More() {
		return exitnode.Config{}, errors.New("exit node config has trailing data")
	}
	// Range-check the integer first: converting an enormous value to a
	// Duration would overflow and wrap past the bound below.
	maxSec := int64(exitnode.MaxTTL / time.Second)
	if in.TTLSeconds <= 0 || in.TTLSeconds > maxSec {
		return exitnode.Config{}, fmt.Errorf("exit node ttlSeconds must be between 1 and %d", maxSec)
	}
	cfg := exitnode.Config{TTL: time.Duration(in.TTLSeconds) * time.Second}
	switch in.Admission {
	case "approval":
		cfg.Admission = exitnode.AdmitApproval
	case "open":
		cfg.Admission = exitnode.AdmitOpen
	default:
		return exitnode.Config{}, errors.New("exit node admission must be \"approval\" or \"open\"")
	}
	return cfg, nil
}

// StartExitNode starts sharing this device's connection and returns the
// session's token. The token is a credential: the caller must not log it.
// Exported to Java as Engine.startExitNode(String).
func StartExitNode(configJSON string) (token string, err error) {
	defer recoverExported("StartExitNode", &err)
	cfg, err := parseExitNodeConfig(configJSON)
	if err != nil {
		return "", err
	}
	if exitNodeConfigHook != nil {
		exitNodeConfigHook(&cfg)
	}

	exitHost.mu.Lock()
	defer exitHost.mu.Unlock()
	if exitHost.node != nil && exitHost.node.State() == exitnode.StateRunning {
		return "", errors.New("exit node is already running")
	}

	globalCore.mu.Lock()
	idle := (globalCore.state == StateStopped || globalCore.state == StateFailed) && !globalCore.stopping
	if idle && !exitNodeClaim.Load() {
		exitNodeClaim.Store(true)
	} else {
		idle = false
	}
	globalCore.mu.Unlock()
	if !idle {
		return "", errors.New("the VPN engine is active; disconnect before sharing")
	}

	installExitNodeProtectorLocked()

	ctx, cancel := context.WithTimeout(context.Background(), exitNodeStartTimeout)
	defer cancel()
	node, err := exitnode.Start(ctx, cfg)
	if err != nil {
		releaseExitNodeLocked()
		return "", err
	}
	token, err = node.Token()
	if err != nil {
		_ = node.Close()
		releaseExitNodeLocked()
		return "", err
	}
	exitHost.node = node

	// The node can end by itself (expiry). Free the engine when it does.
	go func() {
		<-node.Done()
		exitHost.mu.Lock()
		defer exitHost.mu.Unlock()
		if exitHost.node == node {
			releaseExitNodeLocked()
		}
	}()
	return token, nil
}

// installExitNodeProtectorLocked makes socket creation succeed on Android
// while the node runs. exitHost.mu must be held.
func installExitNodeProtectorLocked() {
	protectorMu.Lock()
	exitHost.previous = currentProtector
	exitHost.ours = allowAllProtector{}
	currentProtector = exitHost.ours
	exitHost.swapped = true
	protectorMu.Unlock()
	hookAndroidProtect()
}

// releaseExitNodeLocked gives the engine back. It is idempotent and must be
// called with exitHost.mu held.
func releaseExitNodeLocked() {
	if exitHost.swapped {
		protectorMu.Lock()
		// Restore only if nothing else replaced ours in the meantime.
		if currentProtector == exitHost.ours {
			currentProtector = exitHost.previous
		}
		protectorMu.Unlock()
		exitHost.swapped = false
		exitHost.previous, exitHost.ours = nil, nil
	}
	globalCore.mu.Lock()
	exitNodeClaim.Store(false)
	globalCore.mu.Unlock()
}

// StopExitNode ends sharing and discards the session keys, which revokes every
// token it issued. Idempotent. Exported as Engine.stopExitNode().
func StopExitNode() (err error) {
	defer recoverExported("StopExitNode", &err)
	exitHost.mu.Lock()
	defer exitHost.mu.Unlock()
	if exitHost.node != nil {
		err = exitHost.node.Close()
	}
	releaseExitNodeLocked()
	return err
}

// ExitNodeStatsJSON returns the node's current view (schema in
// testdata/exitnode_stats.json). After the node ends it keeps reporting its
// final state. Exported as Engine.exitNodeStatsJSON().
func ExitNodeStatsJSON() (out string, err error) {
	defer recoverExported("ExitNodeStatsJSON", &err)
	node, err := currentExitNode()
	if err != nil {
		return "", err
	}
	return node.StatsJSON()
}

// ExitNodeApprove admits a pending device. Exported as Engine.exitNodeApprove(String).
func ExitNodeApprove(deviceKey string) (err error) {
	defer recoverExported("ExitNodeApprove", &err)
	node, k, err := runningNodeAndKey(deviceKey)
	if err != nil {
		return err
	}
	return node.Approve(k)
}

// ExitNodeDeny refuses a pending device for the rest of the session.
func ExitNodeDeny(deviceKey string) (err error) {
	defer recoverExported("ExitNodeDeny", &err)
	node, k, err := runningNodeAndKey(deviceKey)
	if err != nil {
		return err
	}
	node.Deny(k)
	return nil
}

// ExitNodeRevoke withdraws a device's access and drops its connection.
func ExitNodeRevoke(deviceKey string) (revoked bool, err error) {
	defer recoverExported("ExitNodeRevoke", &err)
	node, k, err := runningNodeAndKey(deviceKey)
	if err != nil {
		return false, err
	}
	return node.Revoke(k), nil
}

func currentExitNode() (*exitnode.Node, error) {
	exitHost.mu.Lock()
	defer exitHost.mu.Unlock()
	if exitHost.node == nil {
		return nil, errors.New("exit node was never started")
	}
	return exitHost.node, nil
}

func runningNodeAndKey(deviceKey string) (*exitnode.Node, key.NodePublic, error) {
	node, err := currentExitNode()
	if err != nil {
		return nil, key.NodePublic{}, err
	}
	if node.State() != exitnode.StateRunning {
		return nil, key.NodePublic{}, errors.New("exit node is not running")
	}
	k, err := exitnode.ParseDeviceKey(deviceKey)
	if err != nil {
		return nil, key.NodePublic{}, err
	}
	return node, k, nil
}
