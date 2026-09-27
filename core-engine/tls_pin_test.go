package engine

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"math/big"
	"testing"
	"time"
)

func testCert(t *testing.T, cn string, parent *x509.Certificate, parentKey *ecdsa.PrivateKey) (*x509.Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: cn},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  parent == nil,
		BasicConstraintsValid: true,
	}
	signer, signerKey := tmpl, key
	if parent != nil {
		signer, signerKey = parent, parentKey
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, signer, &key.PublicKey, signerKey)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert, key
}

func pinFor(c *x509.Certificate) spkiPin {
	sum := sha256.Sum256(c.RawSubjectPublicKeyInfo)
	return spkiPin{sha256: base64.StdEncoding.EncodeToString(sum[:]), name: c.Subject.CommonName}
}

// The pin must be found in a verified chain. A server that holds any
// trusted certificate could otherwise append the public pinned certificate
// to what it sends and pass.
func TestPinMustBeInVerifiedChain(t *testing.T) {
	pinnedRoot, _ := testCert(t, "pinned root", nil, nil)
	otherRoot, otherKey := testCert(t, "other root", nil, nil)
	leaf, _ := testCert(t, "leaf", otherRoot, otherKey)
	pins := []spkiPin{pinFor(pinnedRoot)}

	if err := verifiedChainsMatchPins([][]*x509.Certificate{{leaf, otherRoot}}, pins); err == nil {
		t.Fatal("a chain verified to an unpinned root must fail")
	}
	if err := verifiedChainsMatchPins(nil, pins); err == nil {
		t.Fatal("no verified chain must fail")
	}
	if err := verifiedChainsMatchPins([][]*x509.Certificate{{leaf, otherRoot}, {leaf, pinnedRoot}}, pins); err != nil {
		t.Fatalf("a verified chain ending at a pinned root must pass: %v", err)
	}
}

// Fails well before a pinned CA certificate expires, so the pin set is
// refreshed in time (a lapsed pin breaks the egress audit, the IPv6 egress
// probe and the tunnel speed test).
func TestPinnedCertificatesOutliveNextReleases(t *testing.T) {
	if len(cloudflareSPKIPins) < 4 {
		t.Fatalf("only %d pins; keep backup CA keys", len(cloudflareSPKIPins))
	}
	soon := time.Now().Add(180 * 24 * time.Hour)
	for _, p := range cloudflareSPKIPins {
		if p.notAfter.Before(soon) {
			t.Errorf("pin %s (%s) expires %s: replace it", p.sha256, p.name, p.notAfter.Format("2006-01-02"))
		}
		if raw, err := base64.StdEncoding.DecodeString(p.sha256); err != nil || len(raw) != sha256.Size {
			t.Errorf("pin %q is not a base64 SHA-256", p.sha256)
		}
	}
}
