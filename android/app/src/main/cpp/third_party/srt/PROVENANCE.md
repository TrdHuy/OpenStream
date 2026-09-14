# Bundled SRT provenance

- Upstream: Haivision/srt
- Commit: bab403744b4d02005b03dac12a79988bc4590038
- Release line: v1.5.5-rc.0a
- Android NDK: 27.0.12077973
- Android API: 29
- Static library: enabled
- Shared library: disabled
- Encryption: disabled (matches the current OpenStream Phase 3 transport scope)
- stdc++ synchronization: enabled

This revision includes upstream PR #3109, which removes the packet-filter
static-initialization-order failure observed when loading the previous SRT 1.5.4
archive on Android.
