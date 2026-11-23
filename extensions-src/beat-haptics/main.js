// Beat Haptics Extension for DanyFy (io.danyfy.beathaptics)
// Drives the native Rust multi-band bass/vocal detector & latency-synced Android Haptic Engine.

var INTENSITY_LABELS = {
  light: 'Light',
  medium: 'Medium',
  strong: 'Heavy',
  max: 'MAX'
};

var MODE_LABELS = {
  rhythm_hybrid: 'Bass+Vocals',
  bass_kick: 'Bass',
  vocals: 'Vocals',
  full_beat: 'Full Beat',
  sub_thump: '808 Sub',
  pulse_bpm: 'BPM Grid'
};

var lastTrackKey = '';

function parseToggle(val, fallback) {
  if (val === undefined || val === null) return fallback;
  if (val === false || val === 'false' || val === '0' || val === 0) return false;
  if (val === true || val === 'true' || val === '1' || val === 1) return true;
  return Boolean(val);
}

function readConfig() {
  var enabled = parseToggle(myspoti.settings.get('enabled'), true);
  var mode = myspoti.settings.get('mode') || 'rhythm_hybrid';
  var rhythmLock = parseToggle(myspoti.settings.get('rhythm_lock'), true);
  var intensity = myspoti.settings.get('intensity') || 'medium';
  var sensitivity = myspoti.settings.get('sensitivity') || 'normal';
  var motor = myspoti.settings.get('motor') || 'auto';
  var channel = myspoti.settings.get('channel') || 'alarm';
  return {
    enabled: enabled,
    mode: String(mode),
    rhythmLock: rhythmLock,
    intensity: String(intensity),
    sensitivity: String(sensitivity),
    motor: String(motor),
    channel: String(channel)
  };
}

function readRealBpm(track) {
  if (track && typeof track.bpm === 'number' && track.bpm >= 50 && track.bpm <= 220) {
    return track.bpm;
  }
  return undefined;
}

function safeCurrentTrack() {
  try {
    return myspoti.player.current();
  } catch (e) {
    return null;
  }
}

function trackKeyOf(track) {
  if (!track) return '';
  return String(track.id || '') + ':' + String(track.title || '') + ':' + String(track.artist || '');
}

function syncEngine(track) {
  if (typeof myspoti.haptics !== 'object') {
    myspoti.log('myspoti.haptics API unavailable — install updated DanyFy APK');
    return null;
  }
  var cfg = readConfig();
  var resolvedTrack = track || safeCurrentTrack();
  lastTrackKey = trackKeyOf(resolvedTrack);
  var payload = {
    enabled: cfg.enabled,
    mode: cfg.mode,
    rhythmLock: cfg.rhythmLock,
    intensity: cfg.intensity,
    sensitivity: cfg.sensitivity,
    motor: cfg.motor,
    channel: cfg.channel
  };
  var bpm = readRealBpm(resolvedTrack);
  if (bpm !== undefined) {
    payload.bpm = bpm;
  }
  return myspoti.haptics.configure(payload);
}

// Initialize immediately on extension load (without spurious boot vibration)
var bootState = syncEngine(null);
if (bootState && bootState.channel) {
  myspoti.log(
    'init v1.1.0: mode=' + bootState.mode +
    ' lock=' + bootState.rhythmLock +
    ' vibr=' + bootState.hasVibrator +
    ' amp=' + bootState.hasAmplitudeControl +
    ' ch=' + bootState.channel +
    ' motor=' + bootState.motor
  );
}

// React to track changes cleanly without random pulse spam
myspoti.on('track', function (ev) {
  var t = ev && ev.track ? ev.track : ev;
  syncEngine(t);
});

// React to playback transitions only (skip periodic 'progress' events so timers stay steady)
myspoti.on('playback', function (ev) {
  if (!ev) return;
  var t = ev.track || null;
  var key = trackKeyOf(t);
  if (ev.type === 'progress' && key === lastTrackKey) return;
  if (ev.type === 'start' || ev.type === 'resume' || ev.type === 'seek' || key !== lastTrackKey) {
    syncEngine(t);
  }
});

// React to settings changes & explicit test button only
myspoti.on('setting', function (ev) {
  if (!ev) return;
  syncEngine(null);
  if (ev.key === 'test_pattern' && ev.pressed) {
    if (typeof myspoti.haptics !== 'object') {
      myspoti.notify('Update DanyFy APK to enable Beat Haptics');
      return;
    }
    var st = myspoti.haptics.test();
    if (st && st.hasVibrator === false) {
      myspoti.notify('No vibration motor detected on this device');
    } else {
      myspoti.notify('Playing Bass + Vocal test pattern...');
    }
  }
});

myspoti.provide('status', function () {
  var cfg = readConfig();
  var st = (typeof myspoti.haptics === 'object') ? myspoti.haptics.state() : null;
  if (st && st.hasVibrator === false) {
    return { live: false, detail: 'No vibrator hardware detected' };
  }
  var modeName = MODE_LABELS[cfg.mode] || cfg.mode;
  var bpmTag = (st && st.detectedBpm > 0) ? (' · ~' + Math.round(st.detectedBpm) + ' BPM') : '';
  var breakdown = '';
  if (st) {
    if (cfg.mode === 'rhythm_hybrid') {
      breakdown = ' · ' + (st.bassBeats || 0) + ' bass / ' + (st.vocalBeats || 0) + ' vocal';
    } else if (cfg.mode === 'vocals') {
      breakdown = ' · ' + (st.vocalBeats || 0) + ' vocals';
    } else {
      breakdown = ' · ' + (st.pulsesFired || 0) + ' beats';
    }
  }
  var lockTag = cfg.rhythmLock ? 'Locked' : 'Free';
  return {
    live: true,
    detail: cfg.enabled
      ? (modeName + bpmTag + ' · ' + (INTENSITY_LABELS[cfg.intensity] || cfg.intensity) + ' · ' + lockTag + breakdown)
      : 'Paused'
  };
});
