# Wired network diagnostics after authentication

The AUTUS MT2712 report from 2026-10-04 reached iAP2 authentication-succeeded
at 13:28:01 and sent StartCarPlaySession. It did not record an accepted AirPlay
TCP connection. At 13:28:35 USBMUX could no longer queue a USB read; earlier
attempts rejected invalid frame lengths. These observations do not establish
whether the network path, USB framing, or device disconnection is the root cause.

The screen and exported report now include:

- Lockdown request name, begin/completion/failure, duration and error code.
- Explicit pending-trust and accepted-pairing messages.
- AirPlay listener readiness and actual port.
- NCM inbound Ethernet/IPv6 counts, TUN packet count, outbound attempt count and
  whether the peer MAC is known, once every ten seconds including idle periods.
- Bounded IPv6 packet metadata: protocol, TCP/UDP ports or ICMPv6 type. No
  payload, MAC/IP address, pairing keys or certificates are emitted by these new logs.

Outbound attempts count calls to the USB sender, not confirmed deliveries.
If inboundFrames stays zero after StartCarPlaySession, check NCM/USB readiness.
If inboundIpv6 grows but no AirPlay TCP is accepted, inspect neighbor discovery
(ICMPv6 135/136), destination port and listener readiness. A packet with an IPv6
extension header reports that header number; the diagnostic does not infer
transport ports through extension headers.

This change adds observations; it does not claim to repair the reported frame
corruption or prove that the test APK works on the head unit.
