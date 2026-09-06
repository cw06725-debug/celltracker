# CellTracker v0.6.2.1

Changes:
- Restyled 1-minute signal trend controls to compact Cellular-Z-like metric tiles; removed arrow glyphs and added chart grid.
- Replaced map Start/End/Current/Issue icons with small color-coded circle markers.
- Fixed single-SIM recording filenames to include the actual slot (SIM1/SIM2) instead of generic SIM.
- Recording history display-name parser updated for SIM1/SIM2 filenames.

Marker colors:
- Start: green
- End: red
- Current location: blue
- Issue marker: orange


## v0.6.2.1 hotfix
- Prevent accidental SIM switching while horizontally panning the Live Map.
- Live Map SIM switching is now done through the SIM tabs; tab animation is preserved.
- Recording Detail Map also reserves horizontal gestures for map panning, while Summary/Samples remain swipeable.
