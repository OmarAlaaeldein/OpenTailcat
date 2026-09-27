package engine

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"time"
)

// spkiPin is a SHA-256 of a certificate's SubjectPublicKeyInfo (base64).
type spkiPin struct {
	sha256   string
	name     string
	notAfter time.Time // of the pinned certificate; checked by a test
}

func pinDate(s string) time.Time {
	t, err := time.Parse("2006-01-02", s)
	if err != nil {
		panic(err)
	}
	return t
}

// cloudflareSPKIPins covers 1.1.1.1 / one.one.one.one / cloudflare-dns.com and
// speed.cloudflare.com. Leaf keys rotate with every certificate, so only CA
// keys are pinned: the intermediates in use (2026-09) and the roots of every
// CA family Cloudflare issues edge certificates from, so a CA switch keeps
// working. Keep in sync with app/.../core/tls/SpkiPins.kt (a unit test
// compares them).
var cloudflareSPKIPins = []spkiPin{
	{"zGgA4OU4DjJdvpRYUqbi5Vh2g9W5Oc/PgKihy9mkLsE=", "SSL.com SSL Intermediate CA ECC R2", pinDate("2034-03-03")},
	{"kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=", "WE1 (Google Trust Services)", pinDate("2029-02-20")},
	{"oyD01TTXvpfBro3QSZc1vIlcMjrdLTiL/M9mLCPX+Zo=", "SSL.com Root Certification Authority ECC", pinDate("2041-02-12")},
	{"0cRTd+vc1hjNFlHcLgLCHXUeWqn80bNDH/bs9qMTSPo=", "SSL.com Root Certification Authority RSA", pinDate("2041-02-12")},
	{"G/ANXI8TwJTdF+AFBM8IiIUPEv0Gf6H5LA/b9guG4yE=", "SSL.com TLS ECC Root CA 2022", pinDate("2046-08-19")},
	{"K89VOmb1cJAN3TK6bf4ezAbJGC1mLcG2Dh97dnwr3VQ=", "SSL.com TLS RSA Root CA 2022", pinDate("2046-08-19")},
	{"hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", "GTS Root R1", pinDate("2036-06-22")},
	{"Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78=", "GTS Root R2", pinDate("2036-06-22")},
	{"QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=", "GTS Root R3", pinDate("2036-06-22")},
	{"mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=", "GTS Root R4", pinDate("2036-06-22")},
	{"C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", "ISRG Root X1", pinDate("2035-06-04")},
	{"diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=", "ISRG Root X2", pinDate("2040-09-17")},
	{"r/mIkG3eEpVdm+u/ko/cwxzOMo1bk4TyHIlByibiA5E=", "DigiCert Global Root CA", pinDate("2031-11-10")},
	{"i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=", "DigiCert Global Root G2", pinDate("2038-01-15")},
}

func pinnedTLSConfig(sni string) *tls.Config {
	return &tls.Config{
		MinVersion:       tls.VersionTLS12,
		ServerName:       sni,
		VerifyConnection: verifyPinnedConnection,
	}
}

// verifyPinnedConnection runs after normal certificate verification and
// requires a pinned key in a verified chain. The server-supplied
// PeerCertificates are not enough: anyone with a trusted certificate could
// append the public pinned certificate to their chain.
func verifyPinnedConnection(cs tls.ConnectionState) error {
	return verifiedChainsMatchPins(cs.VerifiedChains, cloudflareSPKIPins)
}

func verifiedChainsMatchPins(chains [][]*x509.Certificate, pins []spkiPin) error {
	if len(chains) == 0 {
		return errors.New("tls: no verified certificate chain")
	}
	for _, chain := range chains {
		for _, cert := range chain {
			if certificateMatchesPin(cert, pins) {
				return nil
			}
		}
	}
	return errors.New("tls: no pinned key in the verified chain")
}

func certificateMatchesPin(cert *x509.Certificate, pins []spkiPin) bool {
	if cert == nil {
		return false
	}
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	got := base64.StdEncoding.EncodeToString(sum[:])
	for _, pin := range pins {
		if got == pin.sha256 {
			return true
		}
	}
	return false
}
