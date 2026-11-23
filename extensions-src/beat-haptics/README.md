# Beat Haptics for DanyFy (`io.danyfy.beathaptics`)

Feel bass drops and vocal hooks in true musical sync with zero random vibration spam.

## Features
- **Latency-Synchronized Playback (`AudioSyncClock` + `BeatSyncSink`)**: Compensates Media3's `0.5–2.0 s` `AudioTrack` output buffer so every vibration peaks when the sound actually leaves the speaker, and cancels queued pulses immediately on pause, seek, or track skip.
- **Multi-Band Bass & Center-Vocal DSP (`liborpheon_rust.so`)**:
  - **Sub-Bass & Kick Drum (`30–145 Hz`)**: DC-blocked sub/kick isolation with perceptual log-flux peak picking.
  - **Snare & Rhythm (`145–360 Hz`)**: Punchy mid-low percussion transients.
  - **Center-Weighted Vocals (`320–2600 Hz`)**: Separates mono/center voice phrasing from stereo synths/reverb (`M/S` suppression) and fires lighter, crisper pulses distinct from bass kicks.
- **Real-Time Tempo & Phase Tracker**: Circular autocorrelation (`63–182 BPM`) aligns kicks to the groove, rejects shaky jitter, and uses priority collision gating (`Bass > Snare > Vocal`) with **Strict Rhythm Lock (Anti-Spam)**.
- **6 Detection Modes**:
  1. **Bass + Vocals (`rhythm_hybrid`)** *(default)* — Heavy thumps on kicks, lighter crisp taps on exposed vocal phrases.
  2. **Bass & Kick Drum (`bass_kick`)** — Strictly `30–145 Hz` kicks and drops.
  3. **Vocals Only (`vocals`)** — Strictly `320–2600 Hz` center vocal phrases.
  4. **Full Rhythm & Snare (`full_beat`)** — Kicks and snares (`30–360 Hz`).
  5. **Deep 808 Sub-Bass (`sub_thump`)** — Deep `<85 Hz` sub-bass drops.
  6. **Steady Tempo Metronome (`pulse_bpm`)** — Explicit steady BPM grid.
- **ERM Budget Phone + LRA Flagship Calibration & OEM Channels**: Supports `Auto / Budget ERM Boost / Crisp LRA` tuning and `USAGE_ALARM`, `Touch`, or `Media` vibration attributes.
