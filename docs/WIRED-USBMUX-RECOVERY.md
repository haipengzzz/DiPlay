# Wired USBMUX lifecycle recovery

Base: `81a0767`, DiPlay main. This patch is independent of the earlier wireless
SDP patch. It targets the Android 9 MT2712 report from 2026-10-03, where wired
bring-up fails before or during Lockdown pairing with
`Android returned no USBMUX read request`, followed in one attempt by a USBMUX
interface claim failure. These logs do not establish a single hardware cause.

## Changes

- Use standard GET_CONFIGURATION before SET_CONFIGURATION. Reuse the active
  CarPlay configuration instead of needlessly resetting the device. If the
  query cannot identify the current configuration, keep the original selection
  behavior and its claim attempt.
- Keep a single queued USBMUX request and direct buffer across read timeouts.
  Reap a late reply on the next read, rather than cancelling/draining it and
  silently discarding any returned bytes. Reuse the request after completion.
- Publish initialization and queueing under the close-state lock, so shutdown
  cannot miss a request being created concurrently.
- Treat null/unexpected completions as fatal, not timeouts. Close the failed
  session immediately and explicitly release its claimed interface. Preserve
  endpoint and requested timeout information in failure diagnostics.
- Cancel on close and close the USB connection before freeing the request;
  serialize request destruction with the reader. Cleanup is idempotent and
  still closes the connection if explicit interface release fails.
- Report the actual interface ID on a failed claim rather than hardcoding 1.

Android documents requestWait returning null as an error and throwing
TimeoutException for a timeout:
https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#requestWait(long)

## Validation

The real session implementation, USB host/configuration source and new helper
compile with Kotlin 2.2.10 against Android 14 API classes. The USB hardware
boundary alone is mocked in the new lifecycle/configuration tests.

All 38 tests pass, including 12 new lifecycle/configuration tests and 26
existing USBMUX frame-buffer regression tests. The same suite also passes
with Android 9 API classes at runtime. This is JVM testing, not an Android
emulator, real USB driver, iPhone or car test.

Whitespace and public credential checks pass. Full Gradle tests, lint and APK
packaging have not been completed: the previous wrapper download failed with
`Network is unreachable`, and the environment lacks JDK 25, Android SDK 37
and the required NDK. This patch does not contain authentication material.

## Apply and retest

Apply the independent patch to the stated main revision with `git am`, then
use the toolchain and explicit standalone authentication provisioning in
`BUILD.md`. Run `:shared:testDebugUnitTest`, `:mobile:lintDebug`, and
`:mobile:assembleStandaloneDebug`. An ordinary identity-free debug APK cannot
be used as a standalone CarPlay test package.

On the car, run only one receiver app, use a known data cable/data port, unlock
the iPhone and confirm any trust prompt. Export the report after one wired
attempt. Look for progression through `wired USBMUX host opened`, Lockdown
pairing and `wired com.apple.carkit.service stream opened`. If null completion
still occurs, inspect the new endpoint/timeout details; do not assume the
timeout-race change resolved an OEM driver, cable or detach fault.
