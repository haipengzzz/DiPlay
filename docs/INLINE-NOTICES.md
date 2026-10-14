# Non-blocking information

Connection setup issues (hotspot off, Bluetooth off or not paired), app permission
explanations and report export results now use an inline panel, not modal dialogs.
Existing informational Toast calls in DiPlayActivity use the same panel. A notice
updates only its panel, so it does not reconstruct input forms or start a new
connection. Page renders retain the latest notice and actions; replacing a notice
removes its previous action buttons. Report sharing, choosing a save location and
opening permission/Bluetooth/hotspot settings remain explicit button actions.

Keep phone selection, editable settings, manual help/setup and destructive Wi-Fi
reset confirmation. Android/iPhone authorization prompts remain system-controlled.
Playback/canvas errors in the projection component retain their short, non-modal
error Toasts; they are not routine informational dialogs.

Notice evidence uses the existing bounded asynchronous redacted writer and a
separate ui-notices.log archive. Both notice files are included in diagnostic
exports. Notice rotation never overwrites previous CarPlay session logs.

Tests verify inline-only output, working actions, replacement/dismissal, restoring
the panel, credential redaction and isolated log rotation on Android 9/API 28.
