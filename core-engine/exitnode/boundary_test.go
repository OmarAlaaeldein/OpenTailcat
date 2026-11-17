package exitnode

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// The package exists so the client engine can keep forbidding OS sockets
// outright (see the package comment). This test makes the inverse promise: the
// exit node's OS-socket surface is one file, so a reviewer can audit it by
// reading dial.go. Any other file that dials or listens fails here.
func TestOSSocketsConfinedToDial(t *testing.T) {
	forbidden := map[string]map[string]bool{
		"net": {
			"Dial": true, "DialTimeout": true, "DialTCP": true, "DialUDP": true, "DialIP": true,
			"DialUnix": true, "Listen": true, "ListenTCP": true, "ListenUDP": true,
			"ListenPacket": true, "ListenIP": true, "ListenMulticastUDP": true,
			"Dialer": true, "ListenConfig": true, "DefaultResolver": true, "Resolver": true,
			"LookupHost": true, "LookupIP": true,
		},
		"net/http": {"Get": true, "Post": true, "Head": true, "Client": true, "DefaultClient": true, "Transport": true},
		"syscall":  {"Socket": true, "Connect": true, "Bind": true},
	}
	files, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	for _, name := range files {
		if strings.HasSuffix(name, "_test.go") || name == "dial.go" {
			continue
		}
		src, err := os.ReadFile(name)
		if err != nil {
			t.Fatal(err)
		}
		f, err := parser.ParseFile(fset, name, src, parser.SkipObjectResolution)
		if err != nil {
			t.Fatal(err)
		}
		aliases := map[string]string{}
		for _, imp := range f.Imports {
			p, _ := strconv.Unquote(imp.Path.Value)
			if _, ok := forbidden[p]; !ok {
				continue
			}
			local := p[strings.LastIndex(p, "/")+1:]
			if imp.Name != nil {
				local = imp.Name.Name
			}
			aliases[local] = p
		}
		ast.Inspect(f, func(n ast.Node) bool {
			sel, ok := n.(*ast.SelectorExpr)
			if !ok {
				return true
			}
			id, ok := sel.X.(*ast.Ident)
			if !ok {
				return true
			}
			if p, ok := aliases[id.Name]; ok && forbidden[p][sel.Sel.Name] {
				t.Errorf("%s: %s.%s opens an OS socket outside dial.go", name, id.Name, sel.Sel.Name)
			}
			return true
		})
	}
}
