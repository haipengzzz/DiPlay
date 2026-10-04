# Wireless service discovery and video startup recovery

The 2026-10-04 19:39 report contains two wired media sessions and a later
wireless startup. Wireless fails before iAP2 authentication: four RFCOMM
attempts show IOException caused by NullPointerException, with no cached iAP2
service, no accepted TCP connection, and no active AirPlay session. Cached
UUID absence alone does not prove the iPhone does not support the service.

When the selected bonded device lacks the cached iAP2 UUID, request SDP with
the public Android API, listen only for that device, and wait at most eight
seconds. Always unregister on completion, timeout, cancellation, or failure.
Keep the normal secure UUID-based RFCOMM lookup on SDP timeout/OEM failure.
Do not scan, modify bonds, guess channels, or downgrade to insecure sockets.
Logs contain SDP outcome and the nested failure's top stack-frame location,
not private identities or payloads. An SDP result can still be cached by Android.

The wired sessions repeatedly rebuild the MTK AVC decoder with zero output
after a 250 ms age check. One session falls back to Google's decoder and
renders; another eventually renders with MTK and reaches 43–59 fps. This
supports a startup/recovery compatibility problem, not proof of bad Wi-Fi.

Allow frames up to 1500 ms old until the first decoder output, retaining the
250 ms steady-state threshold and existing queue byte/frame limits. After
two startup recoveries skip tuned vendor parameters; after two more prefer
software when available. Preserve the selected tier through same-CSD rebuilds.
For steady-state backlog/overflow, discard queued frames and wait for a new
keyframe without releasing a functioning decoder. Actual input stalls and
errors still rebuild. On API 28, detect Android software decoder names without
calling the API-29 isSoftwareOnly getter.

Validation requires API 28/33 SDP tests, policy tests, existing media queue
tests, full Android CI and a head-unit retest. This cannot guarantee an OEM
Bluetooth stack will expose RFCOMM or that software decoding is fast enough.
