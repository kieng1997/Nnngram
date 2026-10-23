# libs/xray — Xray-core bridge

`gomobile bind` wrapper around the official
[XTLS/Xray-core](https://github.com/XTLS/Xray-core) module. The app talks to it
through the generated `xraybridge` Java package: parse a share link, start a
core with a loopback SOCKS5 inbound, hand the port and credentials to the
existing Telegram proxy chain.

Only Shadowsocks (AEAD + 2022), VMess (tcp/ws, optional TLS) and VLESS
(tcp/ws, TLS, REALITY) are exposed. There is no TUN and no global VPN: the
core listens on `127.0.0.1` only, with UDP disabled and per-session random
SOCKS credentials.

## Support matrix

| | Shadowsocks | VMess | VLESS |
|---|---|---|---|
| link | `ss://` (SIP002 + legacy base64) | `vmess://` (base64 JSON) | `vless://uuid@host:port?...` |
| transports | tcp | tcp / ws | tcp (incl. `type=raw` alias) / ws |
| security | core-managed (AEAD/2022) | none / TLS | none / TLS / REALITY (tcp only) |
| flow | — | — | `xtls-rprx-vision` (+`-udp443`) over TLS/REALITY tcp |
| rejected | plugins, UoT, stream ciphers | REALITY, fp, aid≠0, insecure | encryption≠none, other transports/flows, insecure, duplicate/conflicting query params |

VLESS notes (pinned core `v26.10.21`):

- REALITY client fields: `sni`/`serverName` (aliases, both set = reject),
  `pbk` (required 32-byte X25519 base64url), `sid` (hex, ≤16 chars, empty ok),
  `spx` (must start with `/`), `fp` (utls preset, default `chrome`;
  `unsafe`/`hellogolang` are rejected like the core does).
- The pinned core's REALITY client takes its ALPN from the utls fingerprint
  only (no ALPN field in `reality.Config`), so `alpn` together with
  `security=reality` is rejected instead of silently ignored. Plain TLS
  accepts and emits `fp` and `alpn`.
- REALITY over ws is rejected (core builds REALITY over RAW only).
- Vision flow requires tcp + TLS/REALITY; ws or security=none with flow is
  rejected. `xtls` (legacy) security is rejected.
- `Profile.security()` returns `reality` / `tls` / `none` so the UI can show
  REALITY distinctly from TLS.

## Pinned dependency

| what | value |
|---|---|
| module | `github.com/xtls/xray-core` |
| version | `v1.260327.1-0.20261021115930-1b1647cd9ddf` (`go.mod`) |
| upstream release | `v26.10.21` |
| commit | `1b1647cd9ddf0abc34b981d4611fc03bcac1ca74` |
| gomobile | `golang.org/x/mobile v0.0.0-20261011034528-197770444867` |

The Go module version is the pseudo-version of the `v26.10.21` release: Xray
tags releases as `v26.x`, which Go's module proxy rejects, so upstream publishes
the same commit under a `v1.…` pseudo-version. Source for both is
`https://github.com/XTLS/Xray-core`. `go.sum` is committed; do not upgrade
without re-running the tests.

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
libs/xray/build.sh          # -> libs/xray/build/xraybridge.aar
```

`ANDROID_API` (default 27) and `ABI` (default `arm64`) are overridable.
The Gradle `:TMessagesProj:buildXrayBridge` task runs the equivalent `go run`
invocation and the app depends on the AAR through `builtBy`; `build/` and the
generated AAR are not committed.

## Tests

```bash
cd libs/xray && go test ./...
```

The loopback tests start a local Xray server (Shadowsocks / VMess-WebSocket /
VLESS tcp & ws / VLESS-REALITY with a self-signed TLS 1.3 camouflage target)
and an echo server in-process, then push bytes through the session's SOCKS
inbound — real data exchange, not just core startup. The REALITY suite also
covers the negative case: a wrong `pbk` must fail closed (probe forwarded to
the camouflage target, no echo). No external account or network access is
needed.

Focused runs:

```bash
cd libs/xray
go test -run VLESS ./...        # parsing + config + loopback integration
go test -v -run TestSessionVLESSREALITYVision .   # REALITY + vision echo
```

## License

Xray-core is MPL-2.0. The full license text of the pinned version is at
`libs/xray/licenses/xray-core-LICENSE.txt` and is copied into the app assets as
`xray-core-LICENSE.txt`. Obtain the authoritative text for any other version
from `https://github.com/XTLS/Xray-core/blob/<tag>/LICENSE`.

## App-side manual verification (stage 3 entry points)

App wiring lives in `TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/`
(`XraySessionController`, `XrayProxyHelper`). Before touching a device, run the
deterministic JVM checks (no Android, no JUnit):

```bash
# lifecycle / race behaviour of the session controller (13 scenarios)
javac -d /tmp/xc \
  TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/XrayEndpoint.java \
  TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/XraySessionController.java \
  TMessagesProj/src/test/java/xyz/nextalone/nnngram/helpers/XraySessionControllerTest.java
java -cp /tmp/xc xyz.nextalone.nnngram.helpers.XraySessionControllerTest

# static wiring invariants (persistence, fail-closed, routing, i18n parity)
javac -d /tmp/xw TMessagesProj/src/test/java/xyz/nextalone/nnngram/utils/XrayProxyWiringTest.java
java -cp /tmp/xw xyz.nextalone.nnngram.utils.XrayProxyWiringTest .
```

On-device smoke: add an SS node, connect, disable, switch back to SOCKS5 and
confirm the loopback port disappears (`adb shell cat /proc/net/tcp`), force-stop
and cold-start to confirm recovery, then point the node at a dead server and
confirm the app stays on "connecting via proxy" (traffic must never fall back
to direct).

Manual REALITY entry points (needs a real server — the pinned core's REALITY
client also negotiates X25519MLKEM768, which offline tests exercise only via
the in-process loopback):

1. Paste a `vless://...security=reality...` link into nnn 设置 → 代理 → add
   Xray proxy. The parsed summary must show protocol `VLESS` and transport
   `tcp + REALITY` (never `+ TLS`).
2. Connect against a REALITY server with `flow=xtls-rprx-vision` and confirm
   messages load; a wrong/rotated `pbk` must show "connecting via proxy"
   (fail-closed), never direct traffic.
3. A `security=reality` link carrying `alpn=` must be rejected with the
   standard "not a supported link" error, not silently accepted.
