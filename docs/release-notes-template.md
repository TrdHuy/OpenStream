# OpenStream V1.0.1

OpenStream V1.0.1 turns a supported Android phone into an H.264/AAC camera source for OBS Studio, with reusable stream profiles, capability-aware presets, SRT connectivity, and a Windows x64 OBS plugin package.

## What's New

| Area | Change |
|---|---|
| Daily configuration | Settings are grouped around Camera/Encoding, Audio, and SRT Connection so ordinary setup does not require CLI commands. |
| Profiles | Save, update, reuse, and delete named profiles containing video/audio settings, lens selection, and OBS endpoint details. |
| Presets | Adds `1080p30`, `1080p60`, `4K30`, and `4K60` starting points. Each preset is checked against the phone's real Camera2 + hardware H.264 capability before use. |
| Capability feedback | Unsupported presets remain visible with a clear unsupported state instead of silently advertising a mode the device cannot encode. |
| Validation | Host, port, latency, encoder settings, audio settings, and profile names are validated before settings are persisted or a connection is started. |
| OBS compatibility | Windows plugin releases target OBS Studio 32.2.1 x64 and its FFmpeg 62/62/60/9 ABI. |
| Release verification | Android release artifacts are signed and verified; the OBS package and installer are smoke-tested before publication. |

## Downloads

| File | Use |
|---|---|
| `openstream-android.apk` | Install this on your Android phone. |
| `openstream-android.apk.sha256` | Verify the downloaded APK before installation. |
| `openstream-obs-plugin-installer-windows-x64.exe` | Recommended Windows installer for the OBS plugin. |
| `openstream-obs-windows-x64.zip` | Manual plugin package with DLL and install scripts. |

> [!NOTE]
> The public Android APK is release-signed and accompanied by a SHA-256 checksum. Public releases fail instead of publishing a debug-signed fallback when production signing inputs are unavailable.

## Install and connect

1. Install `openstream-android.apk` on the Android phone and grant Camera/Microphone permissions.
2. Run `openstream-obs-plugin-installer-windows-x64.exe` on the Windows OBS PC, then restart OBS Studio.
3. Add an OpenStream source in OBS.
4. In the Android app, open **Cấu hình phát**, select a supported camera/preset, configure audio, then set the OBS host/IP, port, and SRT latency.
5. Tap **Lưu và kết nối**. Save a named profile when you want to reuse the same camera/encoding/audio/endpoint setup later.
6. When same-LAN discovery/pairing is available in the deployment environment, it may be used instead of manual endpoint entry. Manual SRT unicast remains the explicit fallback path.

For the current no-CLI setup, profile, preset, verification, and troubleshooting flow, read [`docs/phase7-user-guide.md`](phase7-user-guide.md).

## Capability and network notes

- `4K60` is shown as usable only when the selected Camera2 lens and hardware H.264 encoder confirm support for the requested mode/bitrate.
- A supported encode mode does not by itself guarantee production network throughput. Validate high-bitrate 4K operation on the real LAN/Wi-Fi environment.
- Tailscale functional evidence is not used as production LAN discovery, roaming, or throughput acceptance.
- Windows x64 is the supported OBS plugin release target for this phase.
