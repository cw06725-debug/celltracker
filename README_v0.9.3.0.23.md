# CellTracker v0.9.3.0.23

Performance-only optimization for floating timing controls.

- YouTube and WhatsApp clocks refresh every 50 ms instead of 20 ms to reduce UI-thread pressure.
- First START creates the report file on Dispatchers.IO instead of the Accessibility/UI thread.
- LOADED/SENT lock T1 immediately before any snapshot or file work.
- Network snapshot and CSV append run off the UI thread.
- STOP metadata finalization also runs in the background.
- Timing state machine, T0/T1 rules, reports and overlay layout are otherwise unchanged.
