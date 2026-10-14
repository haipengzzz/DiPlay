# Start using the last successful connection

Record wired/wireless transport only when an AirPlay session becomes active,
after checking the current controller generation. Read the actual controller
transport instead of a tentative settings switch. Failed attempts and cancelled
settings do not replace the successful mode. Manual connection buttons still
use the explicitly selected mode. A first launch with no success record falls
back to the existing saved selection.

An enabled BOOT_COMPLETED receiver launches DiPlay with a one-shot auto-connect
request. The activity consumes it only while the boot setting is enabled,
checks setup readiness and an existing background session, then starts the
remembered transport even if ordinary connect-on-open is disabled. Existing
activity delivery rearms the initial check; restored activity state does not
automatically repeat it. The existing permission/setup gates still apply.

For USB, the projection screen starts immediately and the existing discovery
loop waits for the phone if no USB device is attached. Wireless uses the saved
phone and hotspot settings. Actual boot delivery/display remains controlled
by Android and the head unit; the startup diagnostic report includes the
remembered successful mode alongside boot evidence.

Remove bootstrap's unconditional debug-log preference reset: the existing
default is already false, and previously saved log visibility must survive
new processes and reboots.

Install the update and successfully connect once to create a success record;
then enable Open after the car starts. Tests cover transport persistence,
legacy fallback, tentative mode changes and one-shot boot intent behavior.
