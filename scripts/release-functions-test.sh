#!/usr/bin/env bash
# Table tests for the shell functions of the sign job in .github/workflows/release-please.yml (release-pipeline,
# task 2.3). The functions are cut out of the workflow between their "# >>> name" and "# <<< name" lines, so the test
# runs exactly what the workflow runs.
#
# Needs the Android SDK (ANDROID_HOME, or sdk.dir in local.properties), keytool and the unsigned release APK:
#   ./gradlew :android:assembleRelease && scripts/release-functions-test.sh
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
workflow="$root/.github/workflows/release-please.yml"

load() {
  local body
  body=$(sed -n "/# >>> $1 /,/# <<< $1\$/p" "$workflow" | sed 's/^          //')
  [ -n "$body" ] || { echo "Function $1 not found in $workflow" >&2; exit 1; }
  eval "$body"
}
load latest_flag
load check_certificate

failures=0
expect() {
  local name=$1 expected=$2 actual=$3
  if [ "$expected" = "$actual" ]; then
    echo "ok    $name"
  else
    echo "FAIL  $name: expected [$expected], got [$actual]"
    failures=$((failures + 1))
  fi
}

# Spec app-release › Latest release is the highest version.
expect "0.3.0 after 0.3.1 is not the latest" --latest=false "$(latest_flag 0.3.0 v0.3.1)"
expect "0.10.0 after 0.9.0 is the latest" --latest "$(latest_flag 0.10.0 v0.9.0)"
expect "the first release is the latest" --latest "$(latest_flag 0.2.0)"
expect "an empty list from gh counts as none" --latest "$(latest_flag 0.2.0 "")"
expect "0.4.0 after 0.4.1 and 0.3.0 is not the latest" --latest=false "$(latest_flag 0.4.0 v0.3.0 v0.4.1)"
expect "1.0.0 after 0.99.99 is the latest" --latest "$(latest_flag 1.0.0 v0.2.0 v0.99.99)"
expect "a version already published is not the latest again" --latest=false "$(latest_flag 0.4.1 v0.4.1)"

# Spec app-release › Signing apart from the build › Wrong key.
sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$root/local.properties" 2>/dev/null)}
tools=$(printf '%s\n' "$sdk"/build-tools/* | sort -V | tail -1)
unsigned="$root/android/build/outputs/apk/release/android-release-unsigned.apk"
[ -f "$unsigned" ] || { echo "Missing $unsigned, run ./gradlew :android:assembleRelease first" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for key in a b; do
  keytool -genkeypair -keystore "$work/$key.keystore" -storepass android -keypass android -alias test \
    -keyalg RSA -keysize 2048 -validity 1 -dname "CN=release-functions-test $key" >/dev/null 2>&1
done
fingerprint() {
  keytool -exportcert -keystore "$work/$1.keystore" -storepass android -alias test 2>/dev/null |
    sha256sum | cut -d' ' -f1
}
a=$(fingerprint a)
b=$(fingerprint b)
"$tools/zipalign" -f 4 "$unsigned" "$work/aligned.apk"
"$tools/apksigner" sign --ks "$work/a.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$work/signed.apk" "$work/aligned.apk"

run_check() {
  local status=0
  check_certificate "$tools/apksigner" "$1" "$2" >"$work/out" 2>"$work/err" || status=$?
  echo "$status"
}

expect "the pinned key passes" 0 "$(run_check "$work/signed.apk" "$a")"
expect "another key stops" 1 "$(run_check "$work/signed.apk" "$b")"
expect "the error names both fingerprints" \
  "Signed with certificate SHA-256 $a, expected $b" "$(cat "$work/err")"
expect "an unsigned APK stops" 1 "$(run_check "$unsigned" "$a")"

# The signer lines as build tools 36 and 37 print them, played back by a stand-in for apksigner.
fake() {
  printf '#!/bin/sh\ncat <<"OUT"\n%s\nOUT\n' "$1" >"$work/apksigner"
  chmod +x "$work/apksigner"
}
fake_check() {
  local status=0
  check_certificate "$work/apksigner" unused.apk "$1" >"$work/out" 2>"$work/err" || status=$?
  echo "$status"
}
v1="Verified using v1 scheme (JAR signing): true"
fake "$v1
Number of signers: 1
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
Signer #1 certificate SHA-256 digest: $a
Signer #1 public key SHA-256 digest: $b"
expect "the format of build tools 36 passes" 0 "$(fake_check "$a")"
fake "$v1
Number of signers: 1
V3.0 Signer: certificate DN: CN=Android Debug, O=Android, C=US
V3.0 Signer: certificate SHA-256 digest: $a
V3.0 Signer: public key SHA-256 digest: $b"
expect "the format of build tools 37 passes" 0 "$(fake_check "$a")"
expect "a public key digest is not taken for the certificate" 1 "$(fake_check "$b")"
fake "$v1
Number of signers: 1
V2 Signer: certificate SHA-256 digest: $a
V3.0 Signer: certificate SHA-256 digest: $b"
expect "two different certificates stop" 1 "$(fake_check "$a")"
expect "the error names both certificates" \
  "Signed with certificate SHA-256 $(printf '%s\n' "$a" "$b" | sort | paste -sd ' '), expected $a" "$(cat "$work/err")"

if [ "$failures" -gt 0 ]; then
  echo "$failures failed" >&2
  exit 1
fi
echo "All passed"
