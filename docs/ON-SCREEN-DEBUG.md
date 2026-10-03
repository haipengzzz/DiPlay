# On-screen connection diagnostics

The host connection screen now has a bottom-right **Debug logs** button.
It toggles the existing persisted debug-log preference without reconnecting.
The same preference remains available under Settings → Diagnostics.
When enabled, a scrollable monospace overlay appears in the top third of the
screen. Disable it for normal driving and interaction with CarPlay.

Previously, the log views were never built, the screen history was never
populated, updateDebugOverlays always hid it, and CONNECTION_DIAGNOSTIC
messages bypassed the screen entirely. Those paths are now connected.

## Evidence shown

- Current attempt/run/phase, status transitions, permissions and reconnects.
- USB configuration selected/reused, claimed interface, endpoint addresses,
  endpoint type and packet size.
- USBMUX version handshake start, frame metadata, version acceptance,
  setup/reader startup and TCP destination-port connection results.
- First USB write size/result, first completed read size, read initialization,
  bounded I/O counters, timeouts, failures and maximum read/write duration.
- Terminal USB errors, cleanup and up to four exception cause classes.

No packet bodies, certificates, keys or pairing records are added. Screen
messages use the existing redactor: credentials/private phone captures are
excluded and IP/MAC identifiers are masked. Protocol TRACE remains file-only.

## Bounds and history

The overlay retains up to 200 lines for five minutes, even while hidden, and
coalesces rendering to at most four updates per second. Existing I/O summaries
are throttled to ten-second intervals plus first/failure/final events, rather
than logging every USB packet. Scrolling upward pauses automatic tail-follow
until the view is near the bottom again. Logs continue collecting while the
settings screen is open. Old-controller teardown remains in the exported
report but cannot contaminate the current on-screen attempt.

Export the diagnostic report as well as taking a screenshot: screen history
is deliberately shorter than the file history.

## Validation limits

44 JVM tests pass with Android 9 API classes at runtime, covering USB lifecycle,
configuration selection, frame parsing, I/O statistics, redaction and bounded
screen history. Modified transport source and the buffer compile against
Android 14 API classes. An additional Robolectric activity-listener regression
test is included but has not run here. Full application compilation, lint,
UI rendering and physical head-unit validation remain outstanding because
the full Android build toolchain is unavailable in this environment.

The combined patch includes the preceding wired USBMUX lifecycle fix and
applies to DiPlay main revision 81a0767. It excludes the separate wireless
SDP patch. See BUILD.md for standalone APK authentication provisioning;
no authentication material is included in the patch.
