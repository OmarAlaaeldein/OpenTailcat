package engine

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// Safety invariant 2 (AGENTS.md): application flows never use an ordinary OS
// socket; they go TUN -> gVisor -> Client.DialTCP/DialUDP. This test is the
// mechanical check: it parses every non-test source file of the engine
// package and fails on any reference to an API that opens an OS socket or
// resolves a name outside the tunnel, unless the use is listed in
// osSocketAllowlist with the reason it is transport, not an application flow.
//
// Scope and limits: it sees direct references in this package only, by
// import path, including aliased and dot imports. It does not see sockets
// opened inside dependencies (upstream Tailcat/Magicsock/DERP own those, and
// they are protected with VpnService.protect), or reflection.

var forbiddenOSSocketAPIs = map[string]map[string]bool{
	"net": set(
		"Dial", "DialTimeout", "DialTCP", "DialUDP", "DialIP", "DialUnix",
		"Listen", "ListenTCP", "ListenUDP", "ListenIP", "ListenPacket",
		"ListenMulticastUDP", "ListenUnix", "ListenUnixgram",
		"Dialer", "ListenConfig", "FileConn", "FilePacketConn", "FileListener",
		"Resolver", "DefaultResolver", "LookupHost", "LookupIP", "LookupAddr",
		"LookupCNAME", "LookupMX", "LookupNS", "LookupPort", "LookupSRV",
		"LookupTXT", "ResolveIPAddr", "ResolveTCPAddr", "ResolveUDPAddr",
		"ResolveUnixAddr",
	),
	"syscall":               set("Socket", "Socketpair", "Connect", "Bind", "Sendto", "Sendmsg", "SendmsgN"),
	"golang.org/x/sys/unix": set("Socket", "Socketpair", "Connect", "Bind", "Sendto", "Sendmsg", "SendmsgN"),
	"net/http": set(
		"Get", "Head", "Post", "PostForm", "Client", "DefaultClient",
		"DefaultTransport", "Transport",
	),
	"crypto/tls": set("Dial", "DialWithDialer", "Dialer"),
	"net/netip":  nil,
}

// osSocketAllowlist maps "file:pkg.Name" to why that use is not an
// application flow. Every entry must still be in use (stale entries fail).
var osSocketAllowlist = map[string]string{
	// Go's resolver for upstream transport hostnames (DERP/STUN), dialed on
	// sockets passed through VpnService.protect to Android's own DNS servers.
	"main.go:net.DefaultResolver": "transport hostname resolution on protected sockets",
	"main.go:net.Resolver":        "transport hostname resolution on protected sockets",
	"main.go:net.Dialer":          "transport hostname resolution on protected sockets",
	// Wraps upstream's DERP map fetch to strip unsafe nodes; carries no
	// application traffic.
	"main.go:http.DefaultTransport":        "DERP map filter wraps upstream transport",
	"derp_filter.go:http.DefaultTransport": "DERP map filter wraps upstream transport",
}

func set(names ...string) map[string]bool {
	m := make(map[string]bool, len(names))
	for _, n := range names {
		m[n] = true
	}
	return m
}

func TestNoApplicationOSSockets(t *testing.T) {
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	used := map[string]bool{}
	var violations []string

	for _, name := range files {
		if strings.HasSuffix(name, "_test.go") {
			continue
		}
		src, err := os.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		f, err := parser.ParseFile(fset, name, src, parser.SkipObjectResolution)
		if err != nil {
			t.Fatalf("parse %s: %v", name, err)
		}

		// Local name -> import path, for the packages we police.
		aliases := map[string]string{}
		for _, imp := range f.Imports {
			path, _ := strconv.Unquote(imp.Path.Value)
			if _, policed := forbiddenOSSocketAPIs[path]; !policed {
				continue
			}
			local := path[strings.LastIndex(path, "/")+1:]
			if imp.Name != nil {
				local = imp.Name.Name
			}
			if local == "." {
				violations = append(violations, name+": dot import of "+path+" hides socket APIs; import it by name")
				continue
			}
			aliases[local] = path
		}

		ast.Inspect(f, func(n ast.Node) bool {
			sel, ok := n.(*ast.SelectorExpr)
			if !ok {
				return true
			}
			pkg, ok := sel.X.(*ast.Ident)
			if !ok {
				return true
			}
			path, ok := aliases[pkg.Name]
			if !ok || !forbiddenOSSocketAPIs[path][sel.Sel.Name] {
				return true
			}
			short := path[strings.LastIndex(path, "/")+1:]
			key := name + ":" + short + "." + sel.Sel.Name
			if _, allowed := osSocketAllowlist[key]; allowed {
				used[key] = true
				return true
			}
			violations = append(violations, fset.Position(sel.Pos()).String()+": "+short+"."+sel.Sel.Name)
			return true
		})
	}

	for key := range osSocketAllowlist {
		if !used[key] {
			violations = append(violations, "stale allowlist entry (no longer used): "+key)
		}
	}
	if len(violations) > 0 {
		sort.Strings(violations)
		t.Fatalf("OS socket / out-of-tunnel resolver APIs in the engine (invariant 2); "+
			"route flows through Client.DialTCP/DialUDP or justify in osSocketAllowlist:\n  %s",
			strings.Join(violations, "\n  "))
	}
}
