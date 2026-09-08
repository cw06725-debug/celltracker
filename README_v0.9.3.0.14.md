# CellTracker v0.9.3.0.14

VersionCode: 90

## Semi-auto manual START fix

- Manual START is authoritative: after ARMED, the next tap-like gesture inside the YouTube media/content area is recorded as T0.
- Removed mini-player rejection from the armed tap path. The old detector could walk a large parent container and see mini-player labels from another child, falsely classifying an ordinary video card as the mini-player.
- Reduced START -> ARMED settle time from 320 ms to 120 ms so repeated samples respond faster.
- Scroll, long-press, multi-touch, and navigation-area taps still do not create T0.
- LOADED remains the only manual T2 endpoint.
