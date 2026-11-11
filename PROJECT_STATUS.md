# SwiftProxy Android

## Current implementation

- Kotlin + Jetpack Compose / Material 3 interface.
- VLESS, VMess, Shadowsocks and Trojan share-link parsing.
- Manual node entry, HTTPS subscription import and QR-code import.
- Per-node TCP endpoint latency checks (clearly distinguished from full proxy-handshake latency).
- Subscription URL and saved node share links encrypted with Android Keystore (AES-GCM); Android backup is disabled for local credentials.
- WorkManager refreshes the saved subscription every 12 hours when a network is available.
- sing-box libbox 1.14.3 integration through CommandServer and PlatformInterface.
- Android VpnService foreground lifecycle, system VPN permission flow and TUN establishment.
- Android network interface discovery, default-network monitoring and connection-owner lookup.
- sing-box URLTest group can periodically test and automatically select among imported nodes.
- Per-connection application UID network byte deltas are displayed in the connection panel.
- JUnit parser tests run in GitHub Actions before APK assembly.

## Validation status

- CI compilation and parser unit tests are used as the build gate.
- A successful CI build proves the APK compiles; it does not prove that a real purchased node can connect.
- Runtime validation on a physical Android device is still required for VPN authorization, libbox runtime compatibility, DNS, IPv4/IPv6 routing, and real outbound traffic.
- Traffic counters currently represent the app UID's network I/O, not an independently measured per-tunnel byte count.

## Remaining work before final delivery

1. Verify startup and shutdown against the actual libbox 1.14.3 runtime on Android.
2. Test real VLESS / VMess / SS / Trojan nodes, including TLS, Reality, WebSocket and gRPC variants.
3. Verify DNS and IPv4/IPv6 routing, network changes, sleep/resume and Android 14/15 foreground-service behavior.
4. Add clear per-node latency results and connection diagnostics.
5. Complete final regression testing and produce one final APK for installation.

## Delivery policy

Do not ask the user to install interim builds. Only announce an installation when the final feature set has passed the available checks and the remaining device-side validation requirements are explicit.
