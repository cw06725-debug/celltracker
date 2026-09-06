# CellTracker v0.9.0.9

Based on the field-tested v0.9.0.8 project.

## Changes
- Settings root starts at the top on each fresh entry; Back to the home screen still restores the previous home position.
- Floating overlay adds an `×` close button with confirmation. Closing disables/hides only the overlay; an active RecordingService continues in the background.
- Call Setup hold phase now continuously monitors local/peer call state and Device Link instead of using a blind delay.
- Unexpected release before the configured Hold Time is classified as `CALL_DROP`, recorded as an AUTO issue/event, and includes the actual hold duration in the detail.
- Long-call progress is shown during hold.
- Call Setup HTML/XLSX reports include Dropped Calls and Drop Rate; HTML attempts include Hold duration.
- Existing issue lists are augmented with long-call manual audio markers: No Audio, One-way Audio, Noise, Audio Interruption, Poor Voice Quality.
- Automatic voice-quality analysis is intentionally not claimed: public Android APIs do not reliably expose cellular call PCM audio to an ordinary app. Manual audio issue marking remains the supported v0.9.0.9 path.

Version: 0.9.0.9 (versionCode 52)
