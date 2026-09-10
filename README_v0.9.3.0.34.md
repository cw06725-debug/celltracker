# CellTracker v0.9.3.0.34

Visual AI Collector reliability + floating labels.

## Fixes
- Replaces ImageReader listener collection with a robust IO polling loop (~2 fps).
- Fixes the `CAPTURING` but `Frames: 0` failure seen on device.
- Adds a top floating collector bar over YouTube:
  - live frame count / attempt / phase
  - T0
  - PLAY
  - RECS
  - STOP
- START COLLECTION now checks Android "Display over other apps" permission first.
- Floating bar is constrained to the top ~52dp; PLAYER ROI begins at 7% screen height so the collector controls are not part of the player training crop.
- Keeps labels.csv and T0-relative metadata in image names.

## Test
1. Open Visual AI Collector.
2. START COLLECTION.
3. Grant Display over other apps if requested, return and START again.
4. Grant screen sharing.
5. YouTube opens and the top collector bar should appear.
6. Verify F count rises.
7. For each sample: T0 -> tap video -> PLAY -> RECS.
