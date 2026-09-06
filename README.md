# CellTracker v0.4.7

Focus: gesture paging, exit confirmation, faster live cellular refresh, SINR compatibility.

Changes:
- Main SIM pages use Compose HorizontalPager for finger-following swipe transitions.
- Recording Detail Summary / Map / Samples use HorizontalPager for finger-following paging.
- Root back gesture now requires a second back within 2 seconds to exit.
- Cellular refresh no longer waits indefinitely for vendor requestCellInfoUpdate callbacks; after 900 ms it falls back to allCellInfo.
- Dual-SIM cellular reads run concurrently instead of sequentially.
- UI refresh interval is treated as a target cadence (request time is subtracted from the delay).
- Live current-point inspector timestamp refreshes with the live cellular state.
- NR SINR falls back from SS-SINR to CSI-SINR when SS-SINR is unavailable.

Note: LTE SINR can still be unavailable on some phones because Android/vendor radio APIs may return UNAVAILABLE. The app does not fabricate an estimated SINR value.
