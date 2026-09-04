package engine

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"errors"
)

var cloudflareSPKIPins = []string{
	"ltQ6aXy3tqpNZKJdnevMD7oR+IsI5rNWbOssFDrl+Ew=",
	"+b007mFjejRgBPvNGi8dBoql9OZGiCe4woYnC0Lt61I=",
}

func pinnedTLSConfig(sni string) *tls.Config {
	return &tls.Config{
		MinVersion:       tls.VersionTLS12,
		ServerName:       sni,
		VerifyConnection: verifyPinnedConnection,
	}
}

func verifyPinnedConnection(cs tls.ConnectionState) error {
	if len(cs.PeerCertificates) == 0 {
		return errors.New("tls: no peer certificates")
	}
	for _, cert := range cs.PeerCertificates {
		if certificateMatchesPin(cert) {
			return nil
		}
	}
	return errors.New("tls: peer certificate pin mismatch")
}

func certificateMatchesPin(cert *x509.Certificate) bool {
	if cert == nil {
		return false
	}
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	got := base64.StdEncoding.EncodeToString(sum[:])
	for _, pin := range cloudflareSPKIPins {
		if got == pin {
			return true
		}
	}
	return false
}
