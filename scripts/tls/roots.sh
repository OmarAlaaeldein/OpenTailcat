#!/usr/bin/env bash
# Print base64 SPKI SHA-256 pins for the backup CA roots OpenTailcat pins,
# read from the macOS system root store. Use with scripts/tls/pins when
# updating core-engine/tls_pin.go and core/tls/SpkiPins.kt. macOS only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$ROOT/build/tls-roots"
KC=/System/Library/Keychains/SystemRootCertificates.keychain
mkdir -p "$WORK"
cd "$WORK"

for name in "SSL.com Root Certification Authority ECC" "SSL.com Root Certification Authority RSA" \
  "SSL.com TLS ECC Root CA 2022" "SSL.com TLS RSA Root CA 2022" "GTS Root R1" "GTS Root R2" \
  "GTS Root R3" "GTS Root R4" "ISRG Root X1" "ISRG Root X2" "DigiCert Global Root CA" \
  "DigiCert Global Root G2"; do
  security find-certificate -a -c "$name" -p "$KC" >root.pem 2>/dev/null || true
  if [ ! -s root.pem ]; then
    echo "MISSING | $name"
    continue
  fi
  # A name can match several certificates; check each one.
  rm -f rootpart-*
  awk '/BEGIN CERTIFICATE/{n++; f=("rootpart-" n); inc=1} inc{print > f} /END CERTIFICATE/{inc=0; close(f)}' root.pem
  for p in rootpart-*; do
    cn=$(openssl x509 -in "$p" -noout -subject -nameopt multiline 2>/dev/null | sed -n 's/^ *commonName *= //p')
    [ "$cn" = "$name" ] || continue
    end=$(openssl x509 -in "$p" -noout -enddate | sed 's/^notAfter=//')
    pin=$(openssl x509 -in "$p" -noout -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64)
    echo "$pin | $cn | until $end"
  done
done
rm -f root.pem rootpart-*
