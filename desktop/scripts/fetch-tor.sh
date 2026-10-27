#!/usr/bin/env bash
# Downloads the official Tor Expert Bundle for Windows, checks its signature against the
# Tor Browser Developers key, and unpacks tor.exe and the bridge transports into desktop/tor.
set -euo pipefail
VERSION="${TOR_BUNDLE_VERSION:-15.0.26}"
KEY_FPR="EF6E286DDA85EA2A4BA7DE684E2C6E8793298290"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
BASE="https://dist.torproject.org/torbrowser/$VERSION"
FILE="tor-expert-bundle-windows-x86_64-$VERSION.tar.gz"

curl -fsSL -o "$WORK/$FILE" "$BASE/$FILE"
curl -fsSL -o "$WORK/$FILE.asc" "$BASE/$FILE.asc"
curl -fsSL -o "$WORK/key.asc" "https://keys.openpgp.org/vks/v1/by-fingerprint/$KEY_FPR"

export GNUPGHOME="$WORK/gnupg"
mkdir -p "$GNUPGHOME" && chmod 700 "$GNUPGHOME"
gpg --batch --quiet --import "$WORK/key.asc"
# --status-fd lets us check the primary key fingerprint, not just "a good signature".
gpg --batch --status-fd 1 --verify "$WORK/$FILE.asc" "$WORK/$FILE" > "$WORK/status" 2>/dev/null
grep -q "VALIDSIG .* $KEY_FPR" "$WORK/status" || { echo "Tor bundle signature check FAILED"; exit 1; }
echo "Tor bundle $VERSION: signature OK (Tor Browser Developers)"

rm -rf "$HERE/tor" && mkdir -p "$HERE/tor"
tar -xzf "$WORK/$FILE" -C "$WORK"
cp "$WORK/tor/tor.exe" "$HERE/tor/"
cp "$WORK/tor/pluggable_transports/lyrebird.exe" "$WORK/tor/pluggable_transports/pt_config.json" "$HERE/tor/"
cp "$WORK/data/geoip" "$WORK/data/geoip6" "$HERE/tor/"
cp "$WORK/docs/tor.txt" "$WORK/docs/lyrebird.txt" "$HERE/tor/" 2>/dev/null || true
sha256sum "$HERE"/tor/*.exe
rm -rf "$WORK"
