# WG Tunnel (monorepo)

An alternative FOSS Android client for [WireGuard](https://www.wireguard.com/) and [AmneziaWG](https://docs.amnezia.org/documentation/amnezia-wg/) with auto-tunneling, lockdown mode, proxying, and ADB-over-VPN.

## Repository Structure

```
wg-android/
├── app/                  # Android application (Kotlin/Compose)
├── amneziawg-go/         # AmneziaWG Go userspace implementation (forked)
├── networkmonitor/       # Network state monitoring library
├── logcatter/            # Logcat reader module
├── gradle/               # Version catalog & wrapper
└── buildSrc/             # Build constants & extensions
```

The `amneziawg-go/` directory contains the Go tunnel implementation consumed by [amneziawg-android](https://github.com/xnet-admin-1/amneziawg-android) (the JNI bridge published as `com.zaneschepke:amneziawg-android`).

## Building

### Prerequisites

- JDK 21+
- Android SDK (compile SDK 35, NDK 28.x)
- Go 1.24+ (for native tunnel library)
- CMake 3.22+

### Quick build (uses published Maven artifact)

```sh
git clone https://github.com/xnet-admin-1/wg-android
cd wg-android
./gradlew assembleGoogleRelease
```

### Full build from source (including amneziawg-go)

Build the native tunnel library first:

```sh
# Clone amneziawg-android (JNI wrapper)
git clone --recurse-submodules https://github.com/xnet-admin-1/amneziawg-android /tmp/amneziawg-android

# Point its go.mod replace to this repo's amneziawg-go
sed -i "s|replace github.com/amnezia-vpn/amneziawg-go => .*|replace github.com/amnezia-vpn/amneziawg-go => $(pwd)/amneziawg-go|" \
  /tmp/amneziawg-android/tunnel/tools/libwg-go/go.mod

# Publish to mavenLocal
cd /tmp/amneziawg-android
./gradlew :tunnel:publishToMavenLocal -x signReleasePublication
```

Then build the app (mavenLocal is already configured in `settings.gradle.kts`):

```sh
cd wg-android
./gradlew assembleGoogleRelease
```

### Signing

Set environment variables or `local.properties`:

```
KEY_STORE_PATH=/path/to/upload.keystore
SIGNING_STORE_PASSWORD=...
SIGNING_KEY_ALIAS=...
SIGNING_KEY_PASSWORD=...
```

## Features

- **Auto-Tunneling** — activate tunnels based on Wi-Fi SSID, Ethernet, or mobile data
- **Split Tunneling** — per-app or per-route VPN routing
- **AmneziaWG** — censorship-resistant protocol (userspace)
- **WireGuard Kernel Mode** — direct kernel integration for performance
- **Lockdown Mode** — custom kill switch for leak prevention
- **Proxy Mode** — built-in HTTP/SOCKS5 forwarding
- **ADB over VPN** — forward wireless debugging over the tunnel on port 5555
- **Embedded Terminal** — Alpine Linux shell with on-device ADB pairing
- **Android TV** — full TV remote support
- **Monitoring** — ping monitor, local logging, handshake status

## ADB over VPN

Access your device remotely through the WireGuard tunnel:

1. Enable Developer Options → Wireless Debugging
2. Settings → Android Integrations → Terminal & ADB → toggle **ADB over VPN**
3. From your PC: `adb connect <device-wg-ip>:5555`

First-time pairing can be done from the in-app terminal or over the tunnel:
```sh
adb pair <device-wg-ip>:<pairing-port> <code>
adb connect <device-wg-ip>:5555
```

## Acknowledgements

- [WireGuard](https://www.wireguard.com/) — Jason A. Donenfeld
- [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go) — Amnezia Team
- [WG Tunnel](https://github.com/zaneschepke/wgtunnel) — Zane Schepke (upstream)
