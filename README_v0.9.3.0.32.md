# v0.9.3.0.32
- Fix compile error caused by duplicated @OptIn annotation in v0.9.3.0.31.
- Keep independent MediaProjection Visual AI Collector.
- Add manual ground-truth labels: T0, PLAY OK, RECS OK.
- labels.csv records each label timestamp and offset from T0.
- Saved frame filenames include attempt, phase, frame number and T0-relative time.
