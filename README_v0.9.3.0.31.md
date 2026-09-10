# CellTracker v0.9.3.0.31 — Independent Visual AI Collector

- Adds a dedicated Visual AI Collector entry inside YouTube Video Loading.
- Collector no longer depends on the old START/PLAY/RECS/LOADED attempt logic.
- START COLLECTION requests Android screen-sharing permission and opens YouTube.
- MediaProjection + ImageReader continuously samples the real screen at ~2 fps.
- Each frame saves PLAYER and RECS ROI images.
- Collector screen visibly shows CAPTURING, frame counts, save failures, and the exact saved path.
- STOP COLLECTION ends frame collection.
- During collection, operate YouTube naturally; CellTracker START/LOADED is not needed.
