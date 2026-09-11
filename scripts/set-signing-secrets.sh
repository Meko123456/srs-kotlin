#!/bin/bash
# Uploads the Maven Central signing key to a GitHub repository, correctly.
#
#   ./scripts/set-signing-secrets.sh Meko123456/srs-kotlin [more/repos ...]
#
# Why this exists: `gh secret set` reading from a terminal paste truncates long multi-line values,
# and a truncated PGP key fails only once Gradle tries to sign — four minutes into a publish, with
# the unhelpful message "Could not read PGP secret key". This exports from the keyring to a file,
# verifies the file is a real secret key ring *before* uploading, and uploads from that file.
set -euo pipefail

KEY_FPR="${SIGNING_KEY_FPR:-FC27A8931FA48D8612257D3C77A0CAF08F72CBFA}"   # Merab / Sonatype
OUT="$(mktemp -t signing-key)"
trap 'rm -f "$OUT"' EXIT

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <owner/repo> [owner/repo ...]" >&2
  exit 2
fi

echo ">> Exporting $KEY_FPR. Enter the passphrase (the same one stored as SIGNING_PASSWORD)."
export GPG_TTY="$(tty)"
gpg --pinentry-mode loopback --armor --export-secret-keys "$KEY_FPR" > "$OUT"

BYTES=$(wc -c < "$OUT" | tr -d ' ')
echo ">> Exported $BYTES bytes."

# The check the old script did not do. A wrong passphrase, or a keyring holding only a public key,
# produces a file that looks plausible by size but is not a secret key ring — which is exactly the
# state markdown-blocks' secret was left in.
if ! gpg --list-packets "$OUT" 2>/dev/null | grep -q "secret key packet"; then
  echo "!! The export contains no secret key packet — most likely a wrong passphrase, or only the"
  echo "!! public key is in this keyring. NOTHING was uploaded."
  exit 1
fi
if [ "$BYTES" -lt 1000 ] || [ "$(head -1 "$OUT")" != "-----BEGIN PGP PRIVATE KEY BLOCK-----" ]; then
  echo "!! Export looks wrong (too small, or bad header). NOTHING was uploaded."
  exit 1
fi
echo ">> Verified: a complete secret key ring."

for repo in "$@"; do
  echo ">> Uploading SIGNING_KEY to $repo (from a file, so it cannot truncate)..."
  gh secret set SIGNING_KEY -R "$repo" < "$OUT"
done

echo ">> Done. Re-run the publish workflow; its preflight step will confirm the key in seconds."
