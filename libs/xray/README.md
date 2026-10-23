# libs/xray — Xray-core bridge

`gomobile bind` wrapper around the official
[XTLS/Xray-core](https://github.com/XTLS/Xray-core) module. The app talks to it
through the generated `xraybridge` Java package: parse a share link, start a
core with a loopback SOCKS5 inbound, hand the port and credentials to the
existing Telegram proxy chain.

Only Shadowsocks (AEAD + 2022) and VMess (tcp/ws, optional TLS) are exposed.
There is no TUN and no global VPN: the core listens on `127.0.0.1` only, with
UDP disabled and per-session random SOCKS credentials.

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

The loopback tests start a local Xray server (Shadowsocks / VMess-WebSocket) and
an echo server in-process, then push bytes through the session's SOCKS inbound.
No external account or network access is needed.

## License

Xray-core is MPL-2.0. The full license text of the pinned version is at
`libs/xray/licenses/xray-core-LICENSE.txt` and is copied into the app assets as
`xray-core-LICENSE.txt`. Obtain the authoritative text for any other version
from `https://github.com/XTLS/Xray-core/blob/<tag>/LICENSE`.
