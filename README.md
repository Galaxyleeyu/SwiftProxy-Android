# SwiftProxy Android

A lightweight Android proxy client built with Kotlin, Jetpack Compose and sing-box libbox.

## Implemented features

- VLESS, VMess, Shadowsocks and Trojan share-link parsing.
- Add nodes manually, import an HTTPS subscription, or scan a QR code.
- Android VPN permission flow and foreground `VpnService`.
- sing-box 1.14.3 core integration through `CommandServer`.
- sing-box configuration validation before starting the core.
- Optional URLTest group for periodic latency checks and automatic node selection.
- Per-node TCP endpoint latency probe.
- Automatic subscription refresh every 12 hours while network access is available.
- AES-GCM encryption for saved node links and subscription URL using Android Keystore.
- Live app-UID network byte counters for the current connection session.

## Security behavior

- Subscription import accepts HTTPS only.
- Redirects are followed only while the target remains HTTPS.
- Node share links and the subscription URL are encrypted at rest with an Android Keystore AES-GCM key.
- Android app backup is disabled to avoid restoring encrypted data without its device-bound key.
- The TCP latency probe protects its socket from the active VPN to avoid routing the test through itself.

## Build

GitHub Actions runs:

1. `gradle testDebugUnitTest`
2. `gradle assembleDebug`
3. Uploads `SwiftProxy-debug` as a workflow artifact.

Open the repository's Actions tab and select the latest successful run to access the artifact.

## Important validation note

A successful CI build confirms compilation and unit tests. It does not, by itself, prove real-world connectivity with every provider's node formats. Final validation still needs an Android device and a real subscription to verify VPN authorization, libbox runtime startup, DNS, IPv4/IPv6 routing, and actual traffic. The displayed TCP probe measures endpoint TCP connect time; sing-box URLTest performs the automatic proxy-node selection checks.
