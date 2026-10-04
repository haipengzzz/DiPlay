# Bounded media LPCM backlog and idle USB request recovery

The 2026-10-04 19:39 report shows media audio queues reaching 192 packets,
queue-full drops of 51/81 packets, and some windows with 10/11 underruns.
Dropping only the newest packets retains stale audio in front of the queue.

For already-playing media LPCM only, when the oldest application packet is
over max(500 ms, the configured media buffer), discard old packets until a
150 ms fresh tail remains. This intentionally skips a short section of music
to catch up rather than preserving multi-second lag. Do not apply to calls,
navigation, startup prebuffer, AAC or Opus. Preserve AudioTrack PCM and apply
the existing fade-in to the next fresh packet. Report queue age, catch-up
count and intentional latency-drop count separately from decoder drops.

Check actual hardware-buffer exhaustion before feeding the next packet.
For media LPCM, rebuffer after a real underrun and zero queued hardware bytes
even if incoming packets are waiting; the previous incoming-queue-empty gate
could hide an underrun. Do not flush already queued PCM or change the chosen
media-buffer duration. This does not resolve all OEM scheduling stalls.

The report also ends four wired sessions with USB request queue rejection.
It does not establish whether the user unplugged the device. On queue(false),
when no read is pending, close that idle request and attempt one fresh request
on the same connection. Limit this replacement to once per session. Never
cancel/requeue a pending timeout, retry a partial transfer, or bypass a failed
connection. A second rejection/initialization failure still closes resources
and uses the existing full reconnect path. Logs distinguish attempted request
replacement from success; actual unplugging cannot be repaired in software.

Local regression tests cover audio policy and buffer accounting, as well as
fresh USB request recovery, a finite replacement budget, failed replacement
initialization and preservation of the pending-request timeout lifecycle.
Android CI and head-unit retests remain required.
