// Orpheon Native Rust Audio & DSP Engine (GPL-3.0-or-later)
// Provides zero-allocation, LTO + auto-vectorized native implementations of:
// 1. 10-band Trapezoidal State-Variable Filter (SVF) Equalizer + Lookahead/Hold Peak Limiter
// 2. Mid/Side Spatial Stereo Widener + Makeup Gain + Peak Limiter
// 3. Zero-copy MP4 Widevine PSSH box extractor
// 4. Kernel CFS Real-Time Audio Thread priority booster (ANDROID_PRIORITY_URGENT_AUDIO)

#![allow(non_snake_case, non_camel_case_types, clippy::too_many_arguments)]

use std::f32::consts::PI;
use std::ffi::c_void;
use std::ptr;

// --- Minimal Zero-Dependency JNI C ABI Definitions ---
pub type jboolean = u8;
pub type jbyte = i8;
pub type jint = i32;
pub type jlong = i64;
pub type jfloat = f32;
pub type jsize = jint;
pub type jobject = *mut c_void;
pub type jclass = jobject;
pub type jstring = jobject;
pub type jarray = jobject;
pub type jbyteArray = jarray;
pub type jintArray = jarray;
pub type jlongArray = jarray;
pub type jfloatArray = jarray;
pub type JNIEnv = *mut *const *const c_void;

const JNI_TRUE: jboolean = 1;
const JNI_FALSE: jboolean = 0;

// Indices into JNINativeInterface_ (binary-stable across all JVMs and Android ART)
const IDX_NEW_STRING_UTF: usize = 167;
const IDX_GET_ARRAY_LENGTH: usize = 171;
const IDX_NEW_BYTE_ARRAY: usize = 176;
const IDX_NEW_INT_ARRAY: usize = 179;
const IDX_GET_BYTE_ARRAY_REGION: usize = 200;
const IDX_GET_INT_ARRAY_REGION: usize = 203;
const IDX_GET_FLOAT_ARRAY_REGION: usize = 205;
const IDX_SET_BYTE_ARRAY_REGION: usize = 208;
const IDX_SET_INT_ARRAY_REGION: usize = 211;
const IDX_SET_LONG_ARRAY_REGION: usize = 212;
const IDX_GET_DIRECT_BUFFER_ADDRESS: usize = 230;

unsafe fn jni_new_string_utf(env: JNIEnv, utf: *const u8) -> jstring {
    let f: unsafe extern "C" fn(JNIEnv, *const u8) -> jstring =
        std::mem::transmute(*(*env).add(IDX_NEW_STRING_UTF));
    f(env, utf)
}

unsafe fn jni_get_array_length(env: JNIEnv, array: jarray) -> jsize {
    let f: unsafe extern "C" fn(JNIEnv, jarray) -> jsize =
        std::mem::transmute(*(*env).add(IDX_GET_ARRAY_LENGTH));
    f(env, array)
}

unsafe fn jni_new_byte_array(env: JNIEnv, len: jsize) -> jbyteArray {
    let f: unsafe extern "C" fn(JNIEnv, jsize) -> jbyteArray =
        std::mem::transmute(*(*env).add(IDX_NEW_BYTE_ARRAY));
    f(env, len)
}

unsafe fn jni_new_int_array(env: JNIEnv, len: jsize) -> jintArray {
    let f: unsafe extern "C" fn(JNIEnv, jsize) -> jintArray =
        std::mem::transmute(*(*env).add(IDX_NEW_INT_ARRAY));
    f(env, len)
}

unsafe fn jni_get_byte_array_region(env: JNIEnv, array: jbyteArray, start: jsize, len: jsize, buf: *mut jbyte) {
    let f: unsafe extern "C" fn(JNIEnv, jbyteArray, jsize, jsize, *mut jbyte) =
        std::mem::transmute(*(*env).add(IDX_GET_BYTE_ARRAY_REGION));
    f(env, array, start, len, buf);
}

unsafe fn jni_get_int_array_region(env: JNIEnv, array: jintArray, start: jsize, len: jsize, buf: *mut jint) {
    let f: unsafe extern "C" fn(JNIEnv, jintArray, jsize, jsize, *mut jint) =
        std::mem::transmute(*(*env).add(IDX_GET_INT_ARRAY_REGION));
    f(env, array, start, len, buf);
}

unsafe fn jni_set_byte_array_region(env: JNIEnv, array: jbyteArray, start: jsize, len: jsize, buf: *const jbyte) {
    let f: unsafe extern "C" fn(JNIEnv, jbyteArray, jsize, jsize, *const jbyte) =
        std::mem::transmute(*(*env).add(IDX_SET_BYTE_ARRAY_REGION));
    f(env, array, start, len, buf);
}

unsafe fn jni_set_int_array_region(env: JNIEnv, array: jintArray, start: jsize, len: jsize, buf: *const jint) {
    let f: unsafe extern "C" fn(JNIEnv, jintArray, jsize, jsize, *const jint) =
        std::mem::transmute(*(*env).add(IDX_SET_INT_ARRAY_REGION));
    f(env, array, start, len, buf);
}

unsafe fn jni_set_long_array_region(env: JNIEnv, array: jlongArray, start: jsize, len: jsize, buf: *const jlong) {
    let f: unsafe extern "C" fn(JNIEnv, jlongArray, jsize, jsize, *const jlong) =
        std::mem::transmute(*(*env).add(IDX_SET_LONG_ARRAY_REGION));
    f(env, array, start, len, buf);
}

unsafe fn jni_get_float_array_region(env: JNIEnv, array: jfloatArray, start: jsize, len: jsize, buf: *mut jfloat) {
    let f: unsafe extern "C" fn(JNIEnv, jfloatArray, jsize, jsize, *mut jfloat) =
        std::mem::transmute(*(*env).add(IDX_GET_FLOAT_ARRAY_REGION));
    f(env, array, start, len, buf);
}

unsafe fn jni_get_direct_buffer_address(env: JNIEnv, buf: jobject) -> *mut u8 {
    if buf.is_null() {
        return ptr::null_mut();
    }
    let f: unsafe extern "C" fn(JNIEnv, jobject) -> *mut u8 =
        std::mem::transmute(*(*env).add(IDX_GET_DIRECT_BUFFER_ADDRESS));
    f(env, buf)
}

extern "C" {
    fn setpriority(which: i32, who: u32, prio: i32) -> i32;
}

const PRIO_PROCESS: i32 = 0;
const ANDROID_PRIORITY_URGENT_AUDIO: i32 = -19;

#[inline(always)]
fn java_round_to_i16(x: f32) -> i16 {
    ((x + 0.5).floor() as i32).clamp(-32768, 32767) as i16
}

// ============================================================================
// 1. 10-Band Trapezoidal State-Variable Filter (SVF) Equalizer + Limiter
// ============================================================================

const EQ_SLOTS: usize = 10;
const EQ_FREQUENCIES: [f32; EQ_SLOTS] = [
    31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0,
];
const SETTLED_DB: f32 = 0.01;
const GLIDE_FRAMES: usize = 64;
const GLIDE_RATE: f32 = 0.08;
const BELL_Q: f32 = 1.41;
const SHELF_Q: f32 = 0.707;
const CEILING: f32 = 0.9886;
const HOLD_NEAR: f32 = 0.944;

use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};

static BEAT_ENABLED: AtomicBool = AtomicBool::new(false);
static BEAT_MODE: AtomicI32 = AtomicI32::new(4); // 0=bass_kick, 1=full_beat, 2=sub_thump, 3=pulse_bpm, 4=rhythm_hybrid, 5=vocals
static BEAT_SENSITIVITY: AtomicI32 = AtomicI32::new(1);
static BEAT_STRICT_RHYTHM: AtomicBool = AtomicBool::new(true);
static BEAT_CONFIG_GEN: AtomicI32 = AtomicI32::new(1);
static LAST_DETECTED_BEAT: AtomicI32 = AtomicI32::new(0);

const BEAT_KIND_BASS: i32 = 0;
const BEAT_KIND_SNARE: i32 = 1;
const BEAT_KIND_VOCAL: i32 = 2;

const TEMPO_HIST_LEN: usize = 400; // ~4.0 seconds at 100 Hz analysis rate
const TEMPO_MIN_LAG: usize = 33;   // ~181.8 BPM
const TEMPO_MAX_LAG: usize = 95;   // ~63.2 BPM
const MAX_PENDING_BEATS: usize = 16;

#[inline(always)]
fn band_alpha(hz: f32, sr: f32) -> f32 {
    let w = 2.0 * PI * hz;
    (w / (sr + w)).clamp(0.004, 0.78)
}

#[inline(always)]
fn pack_beat_event(frame_offset: usize, strength: i32, kind: i32, bpm_x10: i32) -> i64 {
    let off = (frame_offset as u64) & 0xFF_FFFF;
    let str_u = ((strength.clamp(1, 255)) as u64) & 0xFF;
    let kind_u = ((kind & 0xF) as u64) & 0xF;
    let bpm_u = ((bpm_x10.clamp(0, 4095)) as u64) & 0xFFF;
    (off | (str_u << 24) | (kind_u << 32) | (bpm_u << 36)) as i64
}

pub struct BeatDetector {
    config_gen: i32,
    sample_rate: i32,
    eff_sr: f32,
    frame_steps: usize,
    step_in_frame: usize,
    chunk_frame_cursor: usize,

    // Multi-band filter states (downsampled mono & stereo side)
    dc_lp: f32,
    sub_lp1: f32,
    sub_lp2: f32,
    kick_lp1: f32,
    kick_lp2: f32,
    snare_lp1: f32,
    snare_lp2: f32,
    vm_hp1: f32,
    vm_hp2: f32,
    vm_lp1: f32,
    vm_lp2: f32,
    vs_hp1: f32,
    vs_hp2: f32,
    vs_lp1: f32,
    vs_lp2: f32,

    // Per-10ms-frame energy & peak-offset accumulators
    sub_sum_sq: f32,
    kick_sum_sq: f32,
    snare_sum_sq: f32,
    vm_sum_sq: f32,
    vs_sum_sq: f32,
    bass_peak_val: f32,
    bass_peak_off: usize,
    vox_peak_val: f32,
    vox_peak_off: usize,

    // Perceptual log-flux envelopes & baselines
    r_fast: f32,
    r_base: f32,
    r_flux_avg: f32,
    r_flux_dev: f32,
    r_w0: f32,
    r_w1: f32,
    r_w2: f32,
    r_off0: usize,
    r_off1: usize,
    r_kind0: i32,
    r_kind1: i32,
    r_snare0: f32,
    r_snare1: f32,

    v_fast: f32,
    v_base: f32,
    v_flux_avg: f32,
    v_flux_dev: f32,
    v_w0: f32,
    v_w1: f32,
    v_w2: f32,
    v_off0: usize,
    v_off1: usize,

    // 100 Hz onset history for real-time tempo & phase tracking
    frame_index: u64,
    last_bass_frame: u64,
    last_vocal_frame: u64,
    rhythm_hist: [f32; TEMPO_HIST_LEN],
    hist_pos: usize,
    hist_filled: usize,
    tempo_eval_countdown: usize,
    est_period_frames: f32,
    tempo_conf: f32,

    // Per-instance detected events for the current audio buffer
    pending: [i64; MAX_PENDING_BEATS],
    pending_len: usize,
}

impl BeatDetector {
    pub fn new() -> Self {
        Self {
            config_gen: 0,
            sample_rate: 0,
            eff_sr: 0.0,
            frame_steps: 110,
            step_in_frame: 0,
            chunk_frame_cursor: 0,
            dc_lp: 0.0,
            sub_lp1: 0.0,
            sub_lp2: 0.0,
            kick_lp1: 0.0,
            kick_lp2: 0.0,
            snare_lp1: 0.0,
            snare_lp2: 0.0,
            vm_hp1: 0.0,
            vm_hp2: 0.0,
            vm_lp1: 0.0,
            vm_lp2: 0.0,
            vs_hp1: 0.0,
            vs_hp2: 0.0,
            vs_lp1: 0.0,
            vs_lp2: 0.0,
            sub_sum_sq: 0.0,
            kick_sum_sq: 0.0,
            snare_sum_sq: 0.0,
            vm_sum_sq: 0.0,
            vs_sum_sq: 0.0,
            bass_peak_val: 0.0,
            bass_peak_off: 0,
            vox_peak_val: 0.0,
            vox_peak_off: 0,
            r_fast: 0.0,
            r_base: 0.0,
            r_flux_avg: 0.0,
            r_flux_dev: 0.0,
            r_w0: 0.0,
            r_w1: 0.0,
            r_w2: 0.0,
            r_off0: 0,
            r_off1: 0,
            r_kind0: BEAT_KIND_BASS,
            r_kind1: BEAT_KIND_BASS,
            r_snare0: 0.0,
            r_snare1: 0.0,
            v_fast: 0.0,
            v_base: 0.0,
            v_flux_avg: 0.0,
            v_flux_dev: 0.0,
            v_w0: 0.0,
            v_w1: 0.0,
            v_w2: 0.0,
            v_off0: 0,
            v_off1: 0,
            frame_index: 1000,
            last_bass_frame: 0,
            last_vocal_frame: 0,
            rhythm_hist: [0.0; TEMPO_HIST_LEN],
            hist_pos: 0,
            hist_filled: 0,
            tempo_eval_countdown: 25,
            est_period_frames: 0.0,
            tempo_conf: 0.0,
            pending: [0; MAX_PENDING_BEATS],
            pending_len: 0,
        }
    }

    pub fn configure(&mut self, sample_rate: i32) {
        if sample_rate <= 0 {
            return;
        }
        self.sample_rate = sample_rate;
        self.eff_sr = (sample_rate as f32 / 4.0).max(2000.0);
        self.frame_steps = (self.eff_sr / 100.0).round().clamp(16.0, 480.0) as usize;
    }

    pub fn flush(&mut self) {
        self.step_in_frame = 0;
        self.chunk_frame_cursor = 0;
        self.dc_lp = 0.0;
        self.sub_lp1 = 0.0;
        self.sub_lp2 = 0.0;
        self.kick_lp1 = 0.0;
        self.kick_lp2 = 0.0;
        self.snare_lp1 = 0.0;
        self.snare_lp2 = 0.0;
        self.vm_hp1 = 0.0;
        self.vm_hp2 = 0.0;
        self.vm_lp1 = 0.0;
        self.vm_lp2 = 0.0;
        self.vs_hp1 = 0.0;
        self.vs_hp2 = 0.0;
        self.vs_lp1 = 0.0;
        self.vs_lp2 = 0.0;
        self.sub_sum_sq = 0.0;
        self.kick_sum_sq = 0.0;
        self.snare_sum_sq = 0.0;
        self.vm_sum_sq = 0.0;
        self.vs_sum_sq = 0.0;
        self.bass_peak_val = 0.0;
        self.bass_peak_off = 0;
        self.vox_peak_val = 0.0;
        self.vox_peak_off = 0;
        self.r_fast = 0.0;
        self.r_base = 0.0;
        self.r_flux_avg = 0.0;
        self.r_flux_dev = 0.0;
        self.r_w0 = 0.0;
        self.r_w1 = 0.0;
        self.r_w2 = 0.0;
        self.v_fast = 0.0;
        self.v_base = 0.0;
        self.v_flux_avg = 0.0;
        self.v_flux_dev = 0.0;
        self.v_w0 = 0.0;
        self.v_w1 = 0.0;
        self.v_w2 = 0.0;
        self.frame_index = 1000;
        self.last_bass_frame = 0;
        self.last_vocal_frame = 0;
        self.rhythm_hist.fill(0.0);
        self.hist_pos = 0;
        self.hist_filled = 0;
        self.tempo_eval_countdown = 25;
        self.est_period_frames = 0.0;
        self.tempo_conf = 0.0;
        self.pending_len = 0;
    }

    pub fn drain_into(&mut self, out: &mut [i64]) -> usize {
        let count = self.pending_len.min(out.len());
        out[..count].copy_from_slice(&self.pending[..count]);
        self.pending_len = 0;
        count
    }

    #[inline(always)]
    fn push_event(&mut self, frame_offset: usize, strength: i32, kind: i32) {
        if self.pending_len < MAX_PENDING_BEATS {
            let bpm_x10 = if self.tempo_conf > 1.35 && self.est_period_frames >= TEMPO_MIN_LAG as f32 {
                ((6000.0 / self.est_period_frames) * 10.0).round() as i32
            } else {
                0
            };
            self.pending[self.pending_len] = pack_beat_event(frame_offset, strength, kind, bpm_x10);
            self.pending_len += 1;
        }
    }

    #[inline(never)]
    fn update_tempo(&mut self) {
        let filled = self.hist_filled;
        if filled < TEMPO_MAX_LAG + 25 {
            return;
        }
        let win = filled.min(TEMPO_HIST_LEN);
        // Quick silence guard so quiet breaks don't erode a valid tempo lock with noise
        let mut energy_sum = 0.0f32;
        for i in 0..win {
            let idx = (self.hist_pos + TEMPO_HIST_LEN - win + i) % TEMPO_HIST_LEN;
            energy_sum += self.rhythm_hist[idx];
        }
        if energy_sum / (win as f32) < 0.004 {
            self.tempo_conf *= 0.85;
            return;
        }

        let mut scores = [0.0f32; TEMPO_MAX_LAG + 1];
        let mut best_lag = TEMPO_MIN_LAG;
        let mut best_raw_score = -1.0f32;
        let mut score_sum = 0.0f32;
        let mut lag_count = 0usize;

        for lag in TEMPO_MIN_LAG..=TEMPO_MAX_LAG {
            let pairs = win.saturating_sub(lag);
            if pairs < 30 {
                continue;
            }
            let mut acc = 0.0f32;
            for i in 0..pairs {
                let a = (self.hist_pos + TEMPO_HIST_LEN - win + i) % TEMPO_HIST_LEN;
                let b = (a + lag) % TEMPO_HIST_LEN;
                acc += self.rhythm_hist[a] * self.rhythm_hist[b];
            }
            let acf1 = acc / (pairs as f32);

            let lag2 = lag * 2;
            let acf2 = if lag2 < win - 20 {
                let p2 = win - lag2;
                let mut acc2 = 0.0f32;
                for i in 0..p2 {
                    let a = (self.hist_pos + TEMPO_HIST_LEN - win + i) % TEMPO_HIST_LEN;
                    let b = (a + lag2) % TEMPO_HIST_LEN;
                    acc2 += self.rhythm_hist[a] * self.rhythm_hist[b];
                }
                acc2 / (p2 as f32)
            } else {
                0.0
            };

            let bpm = 6000.0 / (lag as f32);
            let oct = (bpm / 118.0).ln();
            let prior = (-0.5 * oct * oct / (0.62 * 0.62)).exp();
            let s = (acf1 + 0.45 * acf2) * (0.72 + 0.28 * prior);
            scores[lag] = s;
            score_sum += s;
            lag_count += 1;
            if s > best_raw_score {
                best_raw_score = s;
                best_lag = lag;
            }
        }

        if lag_count == 0 || best_raw_score <= 1e-8 {
            self.tempo_conf *= 0.85;
            return;
        }
        let avg_score = score_sum / (lag_count as f32);
        let conf = best_raw_score / (avg_score + 1e-9);
        self.tempo_conf = if self.tempo_conf < 0.5 { conf } else { self.tempo_conf * 0.65 + conf * 0.35 };

        // Parabolic interpolation around best_lag for sub-frame BPM accuracy
        let refined = if best_lag > TEMPO_MIN_LAG && best_lag < TEMPO_MAX_LAG {
            let y0 = scores[best_lag - 1];
            let y1 = scores[best_lag];
            let y2 = scores[best_lag + 1];
            let denom = y0 - 2.0 * y1 + y2;
            if denom.abs() > 1e-9 {
                (best_lag as f32) + (0.5 * (y0 - y2) / denom).clamp(-0.5, 0.5)
            } else {
                best_lag as f32
            }
        } else {
            best_lag as f32
        };

        if conf > 1.32 {
            if self.est_period_frames <= 0.0 || (refined - self.est_period_frames).abs() > self.est_period_frames * 0.14 {
                if conf > 1.60 || self.est_period_frames <= 0.0 {
                    self.est_period_frames = refined;
                }
            } else {
                self.est_period_frames = self.est_period_frames * 0.72 + refined * 0.28;
            }
        }
    }

    #[inline(always)]
    fn finish_analysis_frame(&mut self, mode: i32, sens: i32, strict_rhythm: bool, total_chunk_frames: usize) -> i32 {
        let inv_n = 1.0 / (self.step_in_frame.max(1) as f32);
        let sub_rms = (self.sub_sum_sq * inv_n).sqrt();
        let kick_rms = (self.kick_sum_sq * inv_n).sqrt();
        let snare_rms = (self.snare_sum_sq * inv_n).sqrt();
        let vm_rms = (self.vm_sum_sq * inv_n).sqrt();
        let vs_rms = (self.vs_sum_sq * inv_n).sqrt();

        self.sub_sum_sq = 0.0;
        self.kick_sum_sq = 0.0;
        self.snare_sum_sq = 0.0;
        self.vm_sum_sq = 0.0;
        self.vs_sum_sq = 0.0;

        // Center-focused vocal isolation (Mid - 0.80 * Side), rejecting wide stereo pads/reverb and low bass bleed
        let vox_center = (vm_rms - 0.80 * vs_rms).max(0.0);
        let vox_clarity = vm_rms / (vm_rms + 1.35 * kick_rms + 0.002);
        let vox_level = vox_center * vox_clarity;

        let sub_log = (1.0 + 48.0 * sub_rms).ln();
        let kick_log = (1.0 + 48.0 * kick_rms).ln();
        let snare_log = (1.0 + 40.0 * snare_rms).ln();
        let vox_log = (1.0 + 55.0 * vox_level).ln();

        let (r_band, allow_bass, allow_vocals) = match mode {
            1 => ((kick_log * 0.88 + snare_log * 0.62), true, false),  // full_beat: kick + snare
            2 => (sub_log, true, false),                                // sub_thump: deep 808 sub
            3 => (0.0, false, false),                                   // pulse_bpm: manual metronome
            5 => (0.0, false, true),                                    // vocals: lead vocals & melody
            4 => ((kick_log * 0.92 + snare_log * 0.34), true, true),    // rhythm_hybrid: bass/rhythm + vocals
            _ => (kick_log, true, false),                               // bass_kick: kick & bass rhythm
        };

        // Spectral onset flux for rhythm channel
        self.r_fast += 0.68 * (r_band - self.r_fast);
        let r_base_rate = if r_band > self.r_base { 0.11 } else { 0.24 };
        self.r_base += r_base_rate * (r_band - self.r_base);
        let r_flux = (self.r_fast - self.r_base).max(0.0);
        self.r_flux_avg += 0.015 * (r_flux - self.r_flux_avg);
        self.r_flux_dev += 0.015 * ((r_flux - self.r_flux_avg).abs() - self.r_flux_dev);

        // Spectral onset flux for vocal phrasing channel (slightly slower to reject vibrato buzz)
        self.v_fast += 0.45 * (vox_log - self.v_fast);
        let v_base_rate = if vox_log > self.v_base { 0.075 } else { 0.16 };
        self.v_base += v_base_rate * (vox_log - self.v_base);
        let v_flux = (self.v_fast - self.v_base).max(0.0);
        self.v_flux_avg += 0.012 * (v_flux - self.v_flux_avg);
        self.v_flux_dev += 0.012 * ((v_flux - self.v_flux_avg).abs() - self.v_flux_dev);

        // Feed online autocorrelation tempo tracker with rhythm & gentle vocal energy
        let tempo_signal = if allow_bass {
            r_flux
        } else {
            v_flux * 0.8
        };
        self.rhythm_hist[self.hist_pos] = tempo_signal;
        self.hist_pos = (self.hist_pos + 1) % TEMPO_HIST_LEN;
        if self.hist_filled < TEMPO_HIST_LEN {
            self.hist_filled += 1;
        }
        if self.tempo_eval_countdown > 0 {
            self.tempo_eval_countdown -= 1;
        } else {
            self.tempo_eval_countdown = 20;
            self.update_tempo();
        }

        // Shift 3-frame peak-picking windows
        self.r_w2 = self.r_w1;
        self.r_w1 = self.r_w0;
        self.r_w0 = r_flux;
        self.r_off1 = self.r_off0;
        self.r_off0 = self.bass_peak_off;
        self.r_kind1 = self.r_kind0;
        let is_snare_dom = (mode == 1 || mode == 4) && snare_rms > kick_rms * 1.18;
        self.r_kind0 = if is_snare_dom { BEAT_KIND_SNARE } else { BEAT_KIND_BASS };
        self.r_snare1 = self.r_snare0;
        self.r_snare0 = snare_rms / (kick_rms + 0.005);

        self.v_w2 = self.v_w1;
        self.v_w1 = self.v_w0;
        self.v_w0 = v_flux;
        self.v_off1 = self.v_off0;
        self.v_off0 = self.vox_peak_off;

        self.bass_peak_val = 0.0;
        self.vox_peak_val = 0.0;
        self.step_in_frame = 0;

        let (k_r, min_r_flux, min_bass_gap, k_v, min_v_flux, min_vox_gap, v_min_rms): (f32, f32, u64, f32, f32, u64, f32) = match sens {
            0 => (1.85, 0.048, 30, 2.00, 0.044, 42, 0.020), // low: downbeats & key accents only
            2 => (0.95, 0.018, 17, 1.15, 0.020, 25, 0.010), // high: detailed kicks & vocal phrases
            _ => (1.35, 0.028, 22, 1.50, 0.030, 32, 0.014), // normal: tight musical rhythm sync
        };

        let mut max_strength = 0i32;
        let mut bass_fired_this_frame = false;

        // 1. Evaluate middle frame r_w1 as strict 3-point local peak
        if allow_bass && self.r_w1 >= self.r_w2 && self.r_w1 > self.r_w0 {
            let mut thresh = (self.r_flux_avg + k_r * self.r_flux_dev).max(min_r_flux);
            let gap = self.frame_index.saturating_sub(self.last_bass_frame);
            let mut gap_ok = gap >= min_bass_gap;

            if strict_rhythm && self.tempo_conf > 1.38 && self.est_period_frames >= TEMPO_MIN_LAG as f32 && self.last_bass_frame > 0 {
                let phase = (gap as f32) / self.est_period_frames;
                let near_beat = (phase - phase.round()).abs() <= 0.21 && phase >= 0.78;
                let near_half = (phase - 0.5).abs() <= 0.14;
                if phase < 0.42 {
                    // Too early for rhythm: require huge unmistakable drop accent
                    gap_ok = gap >= min_bass_gap && self.r_w1 > thresh * 2.35;
                } else if near_beat {
                    // On-beat groove lock
                    thresh *= 0.88;
                } else if near_half && (mode == 1 || mode == 4 || sens == 2) {
                    // Half-beat (snare / syncopation) only if prominent enough
                    let half_mult = if sens == 0 { 1.85 } else if self.r_snare1 > 1.1 { 1.15 } else { 1.30 };
                    thresh *= half_mult;
                    gap_ok = gap >= (self.est_period_frames * 0.42).round() as u64 && gap >= min_bass_gap;
                } else if phase < 1.35 {
                    // Off-grid wobble: suppress weak transients
                    thresh *= 1.85;
                }
            }

            if gap_ok && self.r_w1 > thresh {
                let ratio = self.r_w1 / (thresh + 1e-5);
                let norm = ((ratio - 1.0) / 2.2).clamp(0.32, 1.0);
                let strength = (norm * 255.0).round() as i32;
                let off = self.r_off1.min(total_chunk_frames.saturating_sub(1));
                self.push_event(off, strength, self.r_kind1);
                if strength > max_strength {
                    max_strength = strength;
                }
                self.last_bass_frame = self.frame_index;
                bass_fired_this_frame = true;
            }
        }

        // 2. Evaluate center-vocal frame v_w1 as strict 3-point local peak
        if allow_vocals
            && !bass_fired_this_frame
            && vm_rms > v_min_rms
            && vox_clarity > 0.34
            && self.v_w1 >= self.v_w2
            && self.v_w1 > self.v_w0
        {
            let thresh = (self.v_flux_avg + k_v * self.v_flux_dev).max(min_v_flux);
            let v_gap = self.frame_index.saturating_sub(self.last_vocal_frame);
            let b_gap = self.frame_index.saturating_sub(self.last_bass_frame);
            // In hybrid mode, let bass kicks lead the rhythm and bring vocals in cleanly between kicks or on breaks
            let clear_of_bass = mode == 5 || self.last_bass_frame == 0 || b_gap >= 19;

            if clear_of_bass && v_gap >= min_vox_gap && self.v_w1 > thresh {
                let ratio = self.v_w1 / (thresh + 1e-5);
                let cap = if mode == 4 { 188.0 } else { 225.0 };
                let floor = if mode == 4 { 82.0 } else { 96.0 };
                let norm = ((ratio - 1.0) / 2.0).clamp(0.0, 1.0);
                let strength = (floor + norm * (cap - floor)).round() as i32;
                let off = self.v_off1.min(total_chunk_frames.saturating_sub(1));
                self.push_event(off, strength, BEAT_KIND_VOCAL);
                if strength > max_strength {
                    max_strength = strength;
                }
                self.last_vocal_frame = self.frame_index;
            }
        }

        self.frame_index += 1;
        max_strength
    }

    #[inline(always)]
    fn step_sample(
        &mut self,
        mono: f32,
        side: f32,
        sample_offset: usize,
        a_dc: f32,
        a_sub: f32,
        a_kick: f32,
        a_snare: f32,
        a_vox_lo: f32,
        a_vox_hi: f32,
        mode: i32,
        sens: i32,
        strict_rhythm: bool,
        total_chunk_frames: usize,
        max_strength: &mut i32,
    ) {
        self.dc_lp += a_dc * (mono - self.dc_lp);
        let hp = mono - self.dc_lp;

        self.sub_lp1 += a_sub * (hp - self.sub_lp1);
        self.sub_lp2 += a_sub * (self.sub_lp1 - self.sub_lp2);
        let sub_sq = self.sub_lp2 * self.sub_lp2;
        self.sub_sum_sq += sub_sq;

        self.kick_lp1 += a_kick * (hp - self.kick_lp1);
        self.kick_lp2 += a_kick * (self.kick_lp1 - self.kick_lp2);
        let kick_sq = self.kick_lp2 * self.kick_lp2;
        self.kick_sum_sq += kick_sq;
        if kick_sq > self.bass_peak_val {
            self.bass_peak_val = kick_sq;
            self.bass_peak_off = sample_offset;
        }

        self.snare_lp1 += a_snare * (hp - self.snare_lp1);
        self.snare_lp2 += a_snare * (self.snare_lp1 - self.snare_lp2);
        let snare_bp = self.snare_lp2 - self.kick_lp2;
        self.snare_sum_sq += snare_bp * snare_bp;

        // Mid 320-2600 Hz vocal passband
        self.vm_hp1 += a_vox_lo * (mono - self.vm_hp1);
        let vm_h1 = mono - self.vm_hp1;
        self.vm_hp2 += a_vox_lo * (vm_h1 - self.vm_hp2);
        let vm_h2 = vm_h1 - self.vm_hp2;
        self.vm_lp1 += a_vox_hi * (vm_h2 - self.vm_lp1);
        self.vm_lp2 += a_vox_hi * (self.vm_lp1 - self.vm_lp2);
        let vm_sq = self.vm_lp2 * self.vm_lp2;
        self.vm_sum_sq += vm_sq;
        if vm_sq > self.vox_peak_val {
            self.vox_peak_val = vm_sq;
            self.vox_peak_off = sample_offset;
        }

        // Side 320-2600 Hz stereo rejection passband
        self.vs_hp1 += a_vox_lo * (side - self.vs_hp1);
        let vs_h1 = side - self.vs_hp1;
        self.vs_hp2 += a_vox_lo * (vs_h1 - self.vs_hp2);
        let vs_h2 = vs_h1 - self.vs_hp2;
        self.vs_lp1 += a_vox_hi * (vs_h2 - self.vs_lp1);
        self.vs_lp2 += a_vox_hi * (self.vs_lp1 - self.vs_lp2);
        self.vs_sum_sq += self.vs_lp2 * self.vs_lp2;

        self.step_in_frame += 1;
        if self.step_in_frame >= self.frame_steps {
            let hit = self.finish_analysis_frame(mode, sens, strict_rhythm, total_chunk_frames);
            if hit > *max_strength {
                *max_strength = hit;
            }
        }
    }

    pub unsafe fn detect_with(
        &mut self,
        pcm_ptr: *const u8,
        frames: usize,
        channels: usize,
        sample_rate: i32,
        is_float: bool,
        mode: i32,
        sens: i32,
        strict_rhythm: bool,
    ) -> i32 {
        self.pending_len = 0;
        if sample_rate <= 0 || channels == 0 || frames == 0 || mode == 3 {
            return 0;
        }
        let gen = BEAT_CONFIG_GEN.load(Ordering::Relaxed);
        if self.config_gen != gen {
            self.config_gen = gen;
            self.last_bass_frame = 0;
            self.last_vocal_frame = 0;
        }
        if self.sample_rate != sample_rate {
            self.configure(sample_rate);
        }

        let eff_sr = self.eff_sr;
        let a_dc = band_alpha(30.0, eff_sr);
        let a_sub = band_alpha(85.0, eff_sr);
        let a_kick = band_alpha(145.0, eff_sr);
        let a_snare = band_alpha(360.0, eff_sr);
        let a_vox_lo = band_alpha(320.0, eff_sr);
        let a_vox_hi = band_alpha(2600.0, eff_sr);

        const STRIDE: usize = 4;
        let mut f = 0usize;
        let mut max_strength = 0i32;

        if is_float {
            let ptr_f32 = pcm_ptr as *const f32;
            while f + STRIDE <= frames {
                let mut sum_m = 0.0f32;
                let mut sum_s = 0.0f32;
                for s in 0..STRIDE {
                    let idx = (f + s) * channels;
                    let l = ptr::read_unaligned(ptr_f32.add(idx));
                    if channels > 1 {
                        let r = ptr::read_unaligned(ptr_f32.add(idx + 1));
                        sum_m += (l + r) * 0.5;
                        sum_s += (l - r) * 0.5;
                    } else {
                        sum_m += l;
                    }
                }
                self.step_sample(
                    sum_m * 0.25,
                    sum_s * 0.25,
                    f,
                    a_dc, a_sub, a_kick, a_snare, a_vox_lo, a_vox_hi,
                    mode, sens, strict_rhythm, frames, &mut max_strength,
                );
                f += STRIDE;
            }
            if f < frames {
                let rem = frames - f;
                let inv_r = 1.0 / (rem as f32);
                let mut sum_m = 0.0f32;
                let mut sum_s = 0.0f32;
                for s in 0..rem {
                    let idx = (f + s) * channels;
                    let l = ptr::read_unaligned(ptr_f32.add(idx));
                    if channels > 1 {
                        let r = ptr::read_unaligned(ptr_f32.add(idx + 1));
                        sum_m += (l + r) * 0.5;
                        sum_s += (l - r) * 0.5;
                    } else {
                        sum_m += l;
                    }
                }
                self.step_sample(
                    sum_m * inv_r,
                    sum_s * inv_r,
                    f,
                    a_dc, a_sub, a_kick, a_snare, a_vox_lo, a_vox_hi,
                    mode, sens, strict_rhythm, frames, &mut max_strength,
                );
            }
        } else {
            let ptr_i16 = pcm_ptr as *const i16;
            const INV_32768: f32 = 1.0 / 32768.0;
            while f + STRIDE <= frames {
                let mut sum_m = 0.0f32;
                let mut sum_s = 0.0f32;
                for s in 0..STRIDE {
                    let idx = (f + s) * channels;
                    let l = (ptr::read_unaligned(ptr_i16.add(idx)) as f32) * INV_32768;
                    if channels > 1 {
                        let r = (ptr::read_unaligned(ptr_i16.add(idx + 1)) as f32) * INV_32768;
                        sum_m += (l + r) * 0.5;
                        sum_s += (l - r) * 0.5;
                    } else {
                        sum_m += l;
                    }
                }
                self.step_sample(
                    sum_m * 0.25,
                    sum_s * 0.25,
                    f,
                    a_dc, a_sub, a_kick, a_snare, a_vox_lo, a_vox_hi,
                    mode, sens, strict_rhythm, frames, &mut max_strength,
                );
                f += STRIDE;
            }
            if f < frames {
                let rem = frames - f;
                let inv_r = 1.0 / (rem as f32);
                let mut sum_m = 0.0f32;
                let mut sum_s = 0.0f32;
                for s in 0..rem {
                    let idx = (f + s) * channels;
                    let l = (ptr::read_unaligned(ptr_i16.add(idx)) as f32) * INV_32768;
                    if channels > 1 {
                        let r = (ptr::read_unaligned(ptr_i16.add(idx + 1)) as f32) * INV_32768;
                        sum_m += (l + r) * 0.5;
                        sum_s += (l - r) * 0.5;
                    } else {
                        sum_m += l;
                    }
                }
                self.step_sample(
                    sum_m * inv_r,
                    sum_s * inv_r,
                    f,
                    a_dc, a_sub, a_kick, a_snare, a_vox_lo, a_vox_hi,
                    mode, sens, strict_rhythm, frames, &mut max_strength,
                );
            }
        }
        self.chunk_frame_cursor = self.chunk_frame_cursor.wrapping_add(frames);
        max_strength
    }
}

pub struct RustEqualizer {
    channels: usize,
    sample_rate: i32,
    is_float: bool,
    current_gain: [f32; EQ_SLOTS],
    current_preamp: f32,
    a1: [f32; EQ_SLOTS],
    a2: [f32; EQ_SLOTS],
    a3: [f32; EQ_SLOTS],
    m0: [f32; EQ_SLOTS],
    m1: [f32; EQ_SLOTS],
    m2: [f32; EQ_SLOTS],
    active: [usize; EQ_SLOTS],
    running: [bool; EQ_SLOTS],
    state: Vec<f32>,
    frame: Vec<f32>,
    limiter_gain: f32,
    release: f32,
    hold_frames: i32,
    hold_left: i32,
    beat: BeatDetector,
}

impl RustEqualizer {
    pub fn new() -> Self {
        Self {
            channels: 0,
            sample_rate: 0,
            is_float: false,
            current_gain: [0.0; EQ_SLOTS],
            current_preamp: 0.0,
            a1: [0.0; EQ_SLOTS],
            a2: [0.0; EQ_SLOTS],
            a3: [0.0; EQ_SLOTS],
            m0: [0.0; EQ_SLOTS],
            m1: [0.0; EQ_SLOTS],
            m2: [0.0; EQ_SLOTS],
            active: [0; EQ_SLOTS],
            running: [false; EQ_SLOTS],
            state: Vec::new(),
            frame: Vec::new(),
            limiter_gain: 1.0,
            release: 0.0,
            hold_frames: 0,
            hold_left: 0,
            beat: BeatDetector::new(),
        }
    }

    pub fn configure(&mut self, channels: usize, sample_rate: i32, is_float: bool) {
        self.channels = channels;
        self.sample_rate = sample_rate;
        self.is_float = is_float;
        self.state.clear();
        self.state.resize(channels * EQ_SLOTS * 2, 0.0);
        self.frame.clear();
        self.frame.resize(channels, 0.0);
        self.hold_frames = sample_rate / 20;
        self.release = 1.0 - (-1.0f32 / (0.2f32 * sample_rate as f32)).exp();
        self.beat.configure(sample_rate);
    }

    pub fn flush(&mut self, gains: &[f32; EQ_SLOTS], preamp_db: f32) {
        self.state.fill(0.0);
        self.running.fill(false);
        self.limiter_gain = 1.0;
        self.hold_left = 0;
        self.current_gain = *gains;
        self.current_preamp = preamp_db;
        self.beat.flush();
    }

    pub fn reset(&mut self) {
        self.channels = 0;
        self.sample_rate = 0;
        self.state.clear();
        self.frame.clear();
        self.beat.flush();
    }

    #[inline(always)]
    fn settled_flat(&self) -> bool {
        self.current_preamp.abs() < SETTLED_DB
            && self.current_gain.iter().all(|g| g.abs() < SETTLED_DB)
    }

    #[inline(always)]
    fn coefficients(&mut self, i: usize) {
        let a = 10.0f32.powf(self.current_gain[i] / 40.0);
        let max_hz = self.sample_rate as f32 * 0.45;
        let hz = EQ_FREQUENCIES[i].clamp(10.0, max_hz);
        let base = ((PI as f64) * (hz as f64) / (self.sample_rate as f64)).tan() as f32;
        let g: f32;
        let k: f32;
        if i == 0 {
            // Low shelf
            g = base / a.sqrt();
            k = 1.0 / SHELF_Q;
            self.m0[i] = 1.0;
            self.m1[i] = k * (a - 1.0);
            self.m2[i] = a * a - 1.0;
        } else if i == EQ_SLOTS - 1 {
            // High shelf
            g = base * a.sqrt();
            k = 1.0 / SHELF_Q;
            self.m0[i] = a * a;
            self.m1[i] = k * (1.0 - a) * a;
            self.m2[i] = 1.0 - a * a;
        } else {
            // Bell
            g = base;
            k = 1.0 / (BELL_Q * a);
            self.m0[i] = 1.0;
            self.m1[i] = k * (a * a - 1.0);
            self.m2[i] = 0.0;
        }
        let d = 1.0 / (1.0 + g * (g + k));
        self.a1[i] = d;
        self.a2[i] = g * d;
        self.a3[i] = g * g * d;
    }

    #[inline(always)]
    fn prepare(&mut self) -> usize {
        let mut n = 0;
        for i in 0..EQ_SLOTS {
            if self.current_gain[i].abs() >= SETTLED_DB {
                self.coefficients(i);
                self.active[n] = i;
                n += 1;
                self.running[i] = true;
            } else if self.running[i] {
                for c in 0..self.channels {
                    let j = (c * EQ_SLOTS + i) * 2;
                    self.state[j] = 0.0;
                    self.state[j + 1] = 0.0;
                }
                self.running[i] = false;
            }
        }
        n
    }

    #[inline(always)]
    fn section(&mut self, i: usize, c: usize, x: f32) -> f32 {
        let j = (c * EQ_SLOTS + i) * 2;
        let ic1 = unsafe { *self.state.get_unchecked(j) };
        let ic2 = unsafe { *self.state.get_unchecked(j + 1) };
        let v3 = x - ic2;
        let a1 = self.a1[i];
        let a2 = self.a2[i];
        let a3 = self.a3[i];
        let v1 = a1 * ic1 + a2 * v3;
        let v2 = ic2 + a2 * ic1 + a3 * v3;
        unsafe {
            *self.state.get_unchecked_mut(j) = 2.0 * v1 - ic1;
            *self.state.get_unchecked_mut(j + 1) = 2.0 * v2 - ic2;
        }
        self.m0[i] * x + self.m1[i] * v1 + self.m2[i] * v2
    }

    #[inline(always)]
    fn flush_denormals(&mut self, n: usize) {
        for k in 0..n {
            let act = self.active[k];
            for c in 0..self.channels {
                let j = (c * EQ_SLOTS + act) * 2;
                if self.state[j].abs() < 1e-12 {
                    self.state[j] = 0.0;
                }
                if self.state[j + 1].abs() < 1e-12 {
                    self.state[j + 1] = 0.0;
                }
            }
        }
    }

    pub unsafe fn process_direct(
        &mut self,
        in_ptr: *const u8,
        out_ptr: *mut u8,
        frames: usize,
        has_target: bool,
        target_gains: &[f32; EQ_SLOTS],
        preamp_target: f32,
    ) {
        if frames == 0 || self.channels == 0 {
            return;
        }
        let sample_bytes = if self.is_float { 4 } else { 2 };
        let frame_bytes = sample_bytes * self.channels;
        if !has_target && self.settled_flat() {
            if self.running.iter().any(|&r| r) {
                self.state.fill(0.0);
                self.running.fill(false);
            }
            self.limiter_gain = 1.0;
            self.hold_left = 0;
            ptr::copy_nonoverlapping(in_ptr, out_ptr, frames * frame_bytes);
            if BEAT_ENABLED.load(Ordering::Relaxed) {
                self.detect_beats(in_ptr, frames);
            }
            return;
        }

        let channels = self.channels;
        let mut left = frames;
        let mut sample_idx = 0usize;

        if self.is_float {
            let in_f32 = in_ptr as *const f32;
            let out_f32 = out_ptr as *mut f32;
            while left > 0 {
                let block = left.min(GLIDE_FRAMES);
                for i in 0..EQ_SLOTS {
                    let tgt = if has_target { target_gains[i] } else { 0.0 };
                    self.current_gain[i] += (tgt - self.current_gain[i]) * GLIDE_RATE;
                }
                let p_tgt = if has_target { preamp_target } else { 0.0 };
                self.current_preamp += (p_tgt - self.current_preamp) * GLIDE_RATE;
                let n = self.prepare();
                let preamp = 10.0f32.powf(self.current_preamp / 20.0);

                for _ in 0..block {
                    let mut peak = 0.0f32;
                    for c in 0..channels {
                        let mut s = ptr::read_unaligned(in_f32.add(sample_idx + c));
                        for k in 0..n {
                            let band = self.active[k];
                            s = self.section(band, c, s);
                        }
                        s *= preamp;
                        *self.frame.get_unchecked_mut(c) = s;
                        let a = s.abs();
                        if a > peak {
                            peak = a;
                        }
                    }
                    let need = if peak > CEILING { CEILING / peak } else { 1.0 };
                    if peak * self.limiter_gain > CEILING * HOLD_NEAR {
                        self.hold_left = self.hold_frames;
                    } else if self.hold_left > 0 {
                        self.hold_left -= 1;
                    } else {
                        self.limiter_gain += (1.0 - self.limiter_gain) * self.release;
                    }
                    if need < self.limiter_gain {
                        self.limiter_gain = need;
                    }
                    let lg = self.limiter_gain;
                    for c in 0..channels {
                        let s = *self.frame.get_unchecked(c) * lg;
                        ptr::write_unaligned(out_f32.add(sample_idx + c), s);
                    }
                    sample_idx += channels;
                }
                self.flush_denormals(n);
                left -= block;
            }
        } else {
            let in_i16 = in_ptr as *const i16;
            let out_i16 = out_ptr as *mut i16;
            const INV_32768: f32 = 1.0 / 32768.0;
            while left > 0 {
                let block = left.min(GLIDE_FRAMES);
                for i in 0..EQ_SLOTS {
                    let tgt = if has_target { target_gains[i] } else { 0.0 };
                    self.current_gain[i] += (tgt - self.current_gain[i]) * GLIDE_RATE;
                }
                let p_tgt = if has_target { preamp_target } else { 0.0 };
                self.current_preamp += (p_tgt - self.current_preamp) * GLIDE_RATE;
                let n = self.prepare();
                let preamp = 10.0f32.powf(self.current_preamp / 20.0);

                for _ in 0..block {
                    let mut peak = 0.0f32;
                    for c in 0..channels {
                        let raw = ptr::read_unaligned(in_i16.add(sample_idx + c));
                        let mut s = (raw as f32) * INV_32768;
                        for k in 0..n {
                            let band = self.active[k];
                            s = self.section(band, c, s);
                        }
                        s *= preamp;
                        *self.frame.get_unchecked_mut(c) = s;
                        let a = s.abs();
                        if a > peak {
                            peak = a;
                        }
                    }
                    let need = if peak > CEILING { CEILING / peak } else { 1.0 };
                    if peak * self.limiter_gain > CEILING * HOLD_NEAR {
                        self.hold_left = self.hold_frames;
                    } else if self.hold_left > 0 {
                        self.hold_left -= 1;
                    } else {
                        self.limiter_gain += (1.0 - self.limiter_gain) * self.release;
                    }
                    if need < self.limiter_gain {
                        self.limiter_gain = need;
                    }
                    let lg = self.limiter_gain;
                    for c in 0..channels {
                        let s = *self.frame.get_unchecked(c) * lg;
                        ptr::write_unaligned(
                            out_i16.add(sample_idx + c),
                            java_round_to_i16(s * 32768.0),
                        );
                    }
                    sample_idx += channels;
                }
                self.flush_denormals(n);
                left -= block;
            }
        }
        if BEAT_ENABLED.load(Ordering::Relaxed) {
            self.detect_beats(out_ptr as *const u8, frames);
        }
    }

    #[inline(never)]
    pub unsafe fn detect_beats(&mut self, pcm_ptr: *const u8, frames: usize) -> i32 {
        let mode = BEAT_MODE.load(Ordering::Relaxed);
        let sens = BEAT_SENSITIVITY.load(Ordering::Relaxed);
        let strict = BEAT_STRICT_RHYTHM.load(Ordering::Relaxed);
        let max_strength = self.beat.detect_with(
            pcm_ptr,
            frames,
            self.channels,
            self.sample_rate,
            self.is_float,
            mode,
            sens,
            strict,
        );
        if max_strength > 0 {
            let prev = LAST_DETECTED_BEAT.load(Ordering::Relaxed);
            if max_strength > prev {
                LAST_DETECTED_BEAT.store(max_strength, Ordering::Relaxed);
            }
        }
        max_strength
    }

    pub unsafe fn detect_beats_with(
        &mut self,
        pcm_ptr: *const u8,
        frames: usize,
        mode: i32,
        sens: i32,
    ) -> i32 {
        self.beat.detect_with(
            pcm_ptr,
            frames,
            self.channels,
            self.sample_rate,
            self.is_float,
            mode,
            sens,
            true,
        )
    }

    pub unsafe fn detect_beats_with_strict(
        &mut self,
        pcm_ptr: *const u8,
        frames: usize,
        mode: i32,
        sens: i32,
        strict: bool,
    ) -> i32 {
        self.beat.detect_with(
            pcm_ptr,
            frames,
            self.channels,
            self.sample_rate,
            self.is_float,
            mode,
            sens,
            strict,
        )
    }
}

// ============================================================================
// 2. Mid/Side Spatial Stereo Widener + Makeup Gain + Limiter
// ============================================================================

const SPATIAL_SETTLED: f32 = 0.001;

pub struct RustSpatial {
    is_float: bool,
    width: f32,
    limiter_gain: f32,
    release: f32,
}

impl RustSpatial {
    pub fn new() -> Self {
        Self {
            is_float: false,
            width: 1.0,
            limiter_gain: 1.0,
            release: 0.0,
        }
    }

    pub fn configure(&mut self, sample_rate: i32, is_float: bool) {
        self.is_float = is_float;
        self.release = 1.0 - (-1.0f32 / (0.2f32 * sample_rate as f32)).exp();
    }

    pub fn flush(&mut self, target_width: f32) {
        self.width = target_width;
        self.limiter_gain = 1.0;
    }

    pub fn reset(&mut self) {
        self.width = 1.0;
        self.limiter_gain = 1.0;
    }

    #[inline(always)]
    fn makeup(width: f32) -> f32 {
        1.0 / (1.0 + (width - 1.0) * 0.25)
    }

    pub unsafe fn process_direct(
        &mut self,
        in_ptr: *const u8,
        out_ptr: *mut u8,
        frames: usize,
        target: f32,
    ) {
        if frames == 0 {
            return;
        }
        let frame_bytes = if self.is_float { 8 } else { 4 };
        if target == 1.0
            && (self.width - 1.0).abs() < SPATIAL_SETTLED
            && self.limiter_gain > 1.0 - SPATIAL_SETTLED
        {
            self.width = 1.0;
            self.limiter_gain = 1.0;
            ptr::copy_nonoverlapping(in_ptr, out_ptr, frames * frame_bytes);
            return;
        }

        let mut left = frames;
        let mut sample_idx = 0usize;

        if self.is_float {
            let in_f32 = in_ptr as *const f32;
            let out_f32 = out_ptr as *mut f32;
            while left > 0 {
                let block = left.min(GLIDE_FRAMES);
                self.width += (target - self.width) * GLIDE_RATE;
                if (target - self.width).abs() < SPATIAL_SETTLED {
                    self.width = target;
                }
                let unwinding = self.width == 1.0;
                if unwinding {
                    self.limiter_gain += (1.0 - self.limiter_gain) * GLIDE_RATE;
                }
                let gain = Self::makeup(self.width);
                let w = self.width;
                for _ in 0..block {
                    let l = ptr::read_unaligned(in_f32.add(sample_idx));
                    let r = ptr::read_unaligned(in_f32.add(sample_idx + 1));
                    let mid = (l + r) * 0.5;
                    let side = (l - r) * 0.5 * w;
                    let mut out_l = (mid + side) * gain;
                    let mut out_r = (mid - side) * gain;
                    if !unwinding {
                        let peak = out_l.abs().max(out_r.abs());
                        self.limiter_gain += (1.0 - self.limiter_gain) * self.release;
                        if peak * self.limiter_gain > CEILING {
                            self.limiter_gain = CEILING / peak;
                        }
                    }
                    out_l *= self.limiter_gain;
                    out_r *= self.limiter_gain;
                    ptr::write_unaligned(out_f32.add(sample_idx), out_l);
                    ptr::write_unaligned(out_f32.add(sample_idx + 1), out_r);
                    sample_idx += 2;
                }
                left -= block;
            }
        } else {
            let in_i16 = in_ptr as *const i16;
            let out_i16 = out_ptr as *mut i16;
            const INV_32768: f32 = 1.0 / 32768.0;
            while left > 0 {
                let block = left.min(GLIDE_FRAMES);
                self.width += (target - self.width) * GLIDE_RATE;
                if (target - self.width).abs() < SPATIAL_SETTLED {
                    self.width = target;
                }
                let unwinding = self.width == 1.0;
                if unwinding {
                    self.limiter_gain += (1.0 - self.limiter_gain) * GLIDE_RATE;
                }
                let gain = Self::makeup(self.width);
                let w = self.width;
                for _ in 0..block {
                    let l = (ptr::read_unaligned(in_i16.add(sample_idx)) as f32) * INV_32768;
                    let r = (ptr::read_unaligned(in_i16.add(sample_idx + 1)) as f32) * INV_32768;
                    let mid = (l + r) * 0.5;
                    let side = (l - r) * 0.5 * w;
                    let mut out_l = (mid + side) * gain;
                    let mut out_r = (mid - side) * gain;
                    if !unwinding {
                        let peak = out_l.abs().max(out_r.abs());
                        self.limiter_gain += (1.0 - self.limiter_gain) * self.release;
                        if peak * self.limiter_gain > CEILING {
                            self.limiter_gain = CEILING / peak;
                        }
                    }
                    out_l *= self.limiter_gain;
                    out_r *= self.limiter_gain;
                    ptr::write_unaligned(
                        out_i16.add(sample_idx),
                        java_round_to_i16(out_l * 32768.0),
                    );
                    ptr::write_unaligned(
                        out_i16.add(sample_idx + 1),
                        java_round_to_i16(out_r * 32768.0),
                    );
                    sample_idx += 2;
                }
                left -= block;
            }
        }
    }
}

// ============================================================================
// 3. MP4 Widevine PSSH Box Scanner in Rust
// ============================================================================

const WIDEVINE_SYSTEM_ID: [u8; 16] = [
    0xed, 0xef, 0x8b, 0xa9, 0x79, 0xd6, 0x4a, 0xce, 0xa3, 0xc8, 0x27, 0xdc, 0xd5, 0x1d, 0x21, 0xed,
];

fn scan_mp4_widevine_pssh(data: &[u8]) -> Option<&[u8]> {
    let mut at = 0usize;
    let to = data.len();
    while at + 8 <= to {
        let size = u32::from_be_bytes([data[at], data[at + 1], data[at + 2], data[at + 3]]) as usize;
        if size < 8 || at + size > to {
            return None;
        }
        let box_type = &data[at + 4..at + 8];
        if box_type == b"moov" {
            if let Some(found) = scan_mp4_widevine_pssh(&data[at + 8..at + size]) {
                return Some(found);
            }
        } else if box_type == b"pssh" && size >= 28 && data[at + 12..at + 28] == WIDEVINE_SYSTEM_ID {
            return Some(&data[at..at + size]);
        }
        at += size;
    }
    None
}

// ============================================================================
// 4. JNI Exports for com.music.spotui.rust.OrpheonRustAudio
// ============================================================================

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeVersion(
    env: JNIEnv,
    _class: jclass,
) -> jstring {
    let ver = b"orpheon-rust-audio 1.0.0 (LTO/SIMD native engine)\0";
    jni_new_string_utf(env, ver.as_ptr())
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeBoostAudioThread(
    _env: JNIEnv,
    _class: jclass,
) -> jint {
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_URGENT_AUDIO)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeEqCreate(
    _env: JNIEnv,
    _class: jclass,
) -> jlong {
    Box::into_raw(Box::new(RustEqualizer::new())) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeEqDestroy(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut RustEqualizer));
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeEqConfigure(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    channels: jint,
    sample_rate: jint,
    is_float: jboolean,
) {
    if handle == 0 {
        return;
    }
    let eq = &mut *(handle as *mut RustEqualizer);
    eq.configure(channels as usize, sample_rate, is_float != JNI_FALSE);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeEqFlush(
    env: JNIEnv,
    _class: jclass,
    handle: jlong,
    gains_arr: jfloatArray,
    preamp_db: jfloat,
) {
    if handle == 0 {
        return;
    }
    let eq = &mut *(handle as *mut RustEqualizer);
    let mut gains = [0.0f32; EQ_SLOTS];
    if !gains_arr.is_null() {
        let len = (jni_get_array_length(env, gains_arr) as usize).min(EQ_SLOTS);
        jni_get_float_array_region(env, gains_arr, 0, len as jsize, gains.as_mut_ptr());
    }
    eq.flush(&gains, preamp_db);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeEqReset(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let eq = &mut *(handle as *mut RustEqualizer);
    eq.reset();
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeEqProcessDirect(
    env: JNIEnv,
    _class: jclass,
    handle: jlong,
    in_buf: jobject,
    in_pos: jint,
    out_buf: jobject,
    out_pos: jint,
    frames: jint,
    has_target: jboolean,
    gains_arr: jfloatArray,
    preamp_target: jfloat,
) -> jboolean {
    if handle == 0 || frames <= 0 {
        return JNI_FALSE;
    }
    let in_base = jni_get_direct_buffer_address(env, in_buf);
    let out_base = jni_get_direct_buffer_address(env, out_buf);
    if in_base.is_null() || out_base.is_null() {
        return JNI_FALSE;
    }
    let eq = &mut *(handle as *mut RustEqualizer);
    let mut gains = [0.0f32; EQ_SLOTS];
    if has_target != JNI_FALSE && !gains_arr.is_null() {
        let len = (jni_get_array_length(env, gains_arr) as usize).min(EQ_SLOTS);
        jni_get_float_array_region(env, gains_arr, 0, len as jsize, gains.as_mut_ptr());
    }
    eq.process_direct(
        in_base.add(in_pos as usize),
        out_base.add(out_pos as usize),
        frames as usize,
        has_target != JNI_FALSE,
        &gains,
        preamp_target,
    );
    JNI_TRUE
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSpatialCreate(
    _env: JNIEnv,
    _class: jclass,
) -> jlong {
    Box::into_raw(Box::new(RustSpatial::new())) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSpatialDestroy(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut RustSpatial));
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSpatialConfigure(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    sample_rate: jint,
    is_float: jboolean,
) {
    if handle == 0 {
        return;
    }
    let sp = &mut *(handle as *mut RustSpatial);
    sp.configure(sample_rate, is_float != JNI_FALSE);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSpatialFlush(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    target_width: jfloat,
) {
    if handle == 0 {
        return;
    }
    let sp = &mut *(handle as *mut RustSpatial);
    sp.flush(target_width);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSpatialReset(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let sp = &mut *(handle as *mut RustSpatial);
    sp.reset();
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSpatialProcessDirect(
    env: JNIEnv,
    _class: jclass,
    handle: jlong,
    in_buf: jobject,
    in_pos: jint,
    out_buf: jobject,
    out_pos: jint,
    frames: jint,
    target_width: jfloat,
) -> jboolean {
    if handle == 0 || frames <= 0 {
        return JNI_FALSE;
    }
    let in_base = jni_get_direct_buffer_address(env, in_buf);
    let out_base = jni_get_direct_buffer_address(env, out_buf);
    if in_base.is_null() || out_base.is_null() {
        return JNI_FALSE;
    }
    let sp = &mut *(handle as *mut RustSpatial);
    sp.process_direct(
        in_base.add(in_pos as usize),
        out_base.add(out_pos as usize),
        frames as usize,
        target_width,
    );
    JNI_TRUE
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeExtractWidevinePssh(
    env: JNIEnv,
    _class: jclass,
    init_bytes: jbyteArray,
) -> jbyteArray {
    if init_bytes.is_null() {
        return ptr::null_mut();
    }
    let len = jni_get_array_length(env, init_bytes) as usize;
    if len < 28 || len > 1024 * 1024 {
        return ptr::null_mut();
    }
    let mut buf = vec![0u8; len];
    jni_get_byte_array_region(env, init_bytes, 0, len as jsize, buf.as_mut_ptr() as *mut jbyte);
    if let Some(pssh) = scan_mp4_widevine_pssh(&buf) {
        let out = jni_new_byte_array(env, pssh.len() as jsize);
        if !out.is_null() {
            jni_set_byte_array_region(
                env,
                out,
                0,
                pssh.len() as jsize,
                pssh.as_ptr() as *const jbyte,
            );
        }
        out
    } else {
        ptr::null_mut()
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeConfigureBeatDetector(
    _env: JNIEnv,
    _class: jclass,
    enabled: jboolean,
    mode: jint,
    sensitivity: jint,
    strict_rhythm: jboolean,
) {
    BEAT_ENABLED.store(enabled != JNI_FALSE, Ordering::Relaxed);
    BEAT_MODE.store(mode, Ordering::Relaxed);
    BEAT_SENSITIVITY.store(sensitivity, Ordering::Relaxed);
    BEAT_STRICT_RHYTHM.store(strict_rhythm != JNI_FALSE, Ordering::Relaxed);
    BEAT_CONFIG_GEN.fetch_add(1, Ordering::Relaxed);
    if enabled == JNI_FALSE {
        LAST_DETECTED_BEAT.store(0, Ordering::Relaxed);
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeDrainBeatEvents(
    env: JNIEnv,
    _class: jclass,
    handle: jlong,
    out_events: jlongArray,
) -> jint {
    if handle == 0 || out_events.is_null() {
        return 0;
    }
    let eq = &mut *(handle as *mut RustEqualizer);
    if eq.beat.pending_len == 0 {
        return 0;
    }
    let cap = (jni_get_array_length(env, out_events) as usize).min(MAX_PENDING_BEATS);
    if cap == 0 {
        return 0;
    }
    let mut tmp = [0i64; MAX_PENDING_BEATS];
    let count = eq.beat.drain_into(&mut tmp[..cap]);
    if count > 0 {
        jni_set_long_array_region(env, out_events, 0, count as jsize, tmp.as_ptr());
    }
    count as jint
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativePollBeat(
    _env: JNIEnv,
    _class: jclass,
) -> jint {
    LAST_DETECTED_BEAT.swap(0, Ordering::Relaxed)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeDetectBeatsDirect(
    env: JNIEnv,
    _class: jclass,
    handle: jlong,
    buf: jobject,
    pos: jint,
    frames: jint,
) -> jint {
    if handle == 0 || frames <= 0 || !BEAT_ENABLED.load(Ordering::Relaxed) {
        return 0;
    }
    let base = jni_get_direct_buffer_address(env, buf);
    if base.is_null() {
        return 0;
    }
    let eq = &mut *(handle as *mut RustEqualizer);
    eq.detect_beats(base.add(pos as usize), frames as usize)
}

#[no_mangle]
pub unsafe extern "C" fn Java_app_myspoti_engine_android_OrpheonRustBridge_nativeBoostAudioThread(
    _env: JNIEnv,
    _class: jclass,
) -> jint {
    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_URGENT_AUDIO)
}

#[no_mangle]
pub unsafe extern "C" fn Java_app_myspoti_engine_android_OrpheonRustBridge_nativeExtractWidevinePssh(
    env: JNIEnv,
    class: jclass,
    init_bytes: jbyteArray,
) -> jbyteArray {
    Java_com_music_spotui_rust_OrpheonRustAudio_nativeExtractWidevinePssh(env, class, init_bytes)
}

#[cfg(test)]
mod beat_tests {
    use super::*;

    fn synth_kick_sample(t_in_beat: f32) -> f32 {
        if t_in_beat >= 0.14 {
            return 0.0;
        }
        // Decaying 110Hz -> 48Hz kick punch
        let freq = 48.0 + 75.0 * (-t_in_beat * 35.0).exp();
        let env = (-t_in_beat * 20.0).exp();
        (2.0 * std::f32::consts::PI * freq * t_in_beat).sin() * env * 0.72
    }

    fn synth_vocal_phrase(t: f32) -> f32 {
        // Center-focused harmonic vowel triad (420 Hz + 840 Hz + 1260 Hz)
        (2.0 * PI * 420.0 * t).sin() * 0.22
            + (2.0 * PI * 840.0 * t).sin() * 0.14
            + (2.0 * PI * 1260.0 * t).sin() * 0.08
    }

    fn unpack(ev: i64) -> (usize, i32, i32, f32) {
        let off = (ev & 0xFF_FFFF) as usize;
        let strength = ((ev >> 24) & 0xFF) as i32;
        let kind = ((ev >> 32) & 0xF) as i32;
        let bpm = (((ev >> 36) & 0xFFF) as f32) * 0.1;
        (off, strength, kind, bpm)
    }

    #[test]
    fn detects_kicks_over_continuous_compressed_bass() {
        let sr = 44100usize;
        let mut eq = RustEqualizer::new();
        eq.configure(2, sr as i32, false);
        eq.flush(&[0.0; EQ_SLOTS], 0.0);

        // Warm up with 1.0s steady bassbed, then 2.0s @ 120 BPM (4 kicks at 0.25s, 0.75s, 1.25s, 1.75s)
        let warmup_frames = sr;
        let total_frames = sr * 2;
        let mut warmup = Vec::with_capacity(warmup_frames * 2);
        for i in 0..warmup_frames {
            let t = i as f32 / sr as f32;
            let ramp = (t / 0.35).min(1.0);
            let s = ((2.0 * std::f32::consts::PI * 55.0 * t).sin() * 0.18 * ramp * 32767.0) as i16;
            warmup.push(s);
            warmup.push(s);
        }
        for chunk in warmup.chunks(2048 * 2) {
            unsafe {
                eq.detect_beats_with(chunk.as_ptr() as *const u8, chunk.len() / 2, 0, 1);
            }
        }

        let mut pcm = Vec::with_capacity(total_frames * 2);
        for i in 0..total_frames {
            let t = i as f32 / sr as f32;
            let bass_bed = (2.0 * std::f32::consts::PI * 55.0 * t).sin() * 0.18;
            let phase = (t - 0.25).rem_euclid(0.5);
            let kick = if t >= 0.25 { synth_kick_sample(phase) } else { 0.0 };
            let s = ((bass_bed + kick).clamp(-1.0, 1.0) * 32767.0) as i16;
            pcm.push(s);
            pcm.push(s);
        }

        let mut detected = 0;
        let mut buf = [0i64; MAX_PENDING_BEATS];
        for chunk in pcm.chunks(2048 * 2) {
            unsafe {
                eq.detect_beats_with(chunk.as_ptr() as *const u8, chunk.len() / 2, 0, 1);
            }
            let n = eq.beat.drain_into(&mut buf);
            for ev in &buf[..n] {
                let (off, str_val, kind, _) = unpack(*ev);
                assert!(off < chunk.len() / 2);
                assert!(str_val > 0);
                assert_eq!(kind, BEAT_KIND_BASS);
                detected += 1;
            }
        }
        assert!(detected >= 3 && detected <= 5, "expected ~4 kick beats, got {detected}");
    }

    #[test]
    fn silence_never_triggers_beats() {
        let mut eq = RustEqualizer::new();
        eq.configure(2, 48000, true);
        eq.flush(&[0.0; EQ_SLOTS], 0.0);

        let silence = vec![0.0f32; 48000 * 2];
        for chunk in silence.chunks(1024 * 2) {
            let hit = unsafe { eq.detect_beats_with(chunk.as_ptr() as *const u8, chunk.len() / 2, 4, 2) };
            assert_eq!(hit, 0);
        }
    }

    #[test]
    fn steady_sub_tone_does_not_spam_after_initial_transient() {
        let sr = 48000usize;
        let mut eq = RustEqualizer::new();
        eq.configure(2, sr as i32, true);
        eq.flush(&[0.0; EQ_SLOTS], 0.0);

        let mut tone = Vec::with_capacity(sr * 2 * 2);
        for i in 0..(sr * 2) {
            let t = i as f32 / sr as f32;
            let s = (2.0 * std::f32::consts::PI * 60.0 * t).sin() * 0.25;
            tone.push(s);
            tone.push(s);
        }
        let mut post_warmup_hits = 0;
        for (idx, chunk) in tone.chunks(1024 * 2).enumerate() {
            let hit = unsafe { eq.detect_beats_with(chunk.as_ptr() as *const u8, chunk.len() / 2, 0, 1) };
            if idx > 20 && hit > 0 {
                post_warmup_hits += 1;
            }
        }
        assert_eq!(post_warmup_hits, 0, "steady tone caused {post_warmup_hits} spurious triggers");
    }

    #[test]
    fn center_vocal_phrases_detect_vocals_and_reject_wide_stereo_synth() {
        let sr = 48000usize;
        let mut eq = RustEqualizer::new();
        eq.configure(2, sr as i32, true);
        eq.flush(&[0.0; EQ_SLOTS], 0.0);

        // 2.0s of wide stereo pad only (L = +x, R = -x) at 600 Hz -> zero center vocal hits
        let mut stereo_only = Vec::with_capacity(sr * 2 * 2);
        for i in 0..(sr * 2) {
            let t = i as f32 / sr as f32;
            let env = if (t % 0.5) < 0.25 { 0.35 } else { 0.05 };
            let x = (2.0 * PI * 600.0 * t).sin() * env;
            stereo_only.push(x);
            stereo_only.push(-x);
        }
        let mut wide_hits = 0;
        for (idx, chunk) in stereo_only.chunks(1024 * 2).enumerate() {
            let hit = unsafe { eq.detect_beats_with(chunk.as_ptr() as *const u8, chunk.len() / 2, 5, 1) };
            if idx > 5 && hit > 0 {
                wide_hits += 1;
            }
        }
        assert_eq!(wide_hits, 0, "wide stereo pad triggered {wide_hits} false vocal hits");

        // Now reset and feed 3 clean center-panned vocal phrase entries at t=0.4s, 1.1s, 1.8s
        eq.flush(&[0.0; EQ_SLOTS], 0.0);
        let mut vox_pcm = Vec::with_capacity((sr * 5 / 2) * 2);
        for i in 0..(sr * 5 / 2) {
            let t = i as f32 / sr as f32;
            let phrase_env = if (0.40..0.75).contains(&t) || (1.10..1.45).contains(&t) || (1.80..2.15).contains(&t) {
                1.0
            } else {
                0.0
            };
            let v = synth_vocal_phrase(t) * phrase_env;
            vox_pcm.push(v);
            vox_pcm.push(v);
        }
        let mut vocal_events = 0;
        let mut buf = [0i64; MAX_PENDING_BEATS];
        for chunk in vox_pcm.chunks(1024 * 2) {
            unsafe {
                eq.detect_beats_with(chunk.as_ptr() as *const u8, chunk.len() / 2, 5, 1);
            }
            let n = eq.beat.drain_into(&mut buf);
            for ev in &buf[..n] {
                let (_, _, kind, _) = unpack(*ev);
                assert_eq!(kind, BEAT_KIND_VOCAL);
                vocal_events += 1;
            }
        }
        assert!(vocal_events >= 2 && vocal_events <= 5, "expected ~3 vocal phrase triggers without spamming, got {vocal_events}");
    }

    #[test]
    fn rhythm_hybrid_separates_bass_kicks_and_vocal_phrases_without_spam() {
        let sr = 44100usize;
        let mut eq = RustEqualizer::new();
        eq.configure(2, sr as i32, false);
        eq.flush(&[0.0; EQ_SLOTS], 0.0);

        // 4 seconds: first 2s steady 120 BPM kicks (every 0.5s), then 2s vocal verse with no kicks
        let total = sr * 4;
        let mut pcm = Vec::with_capacity(total * 2);
        for i in 0..total {
            let t = i as f32 / sr as f32;
            let kick = if t < 2.0 {
                let ph = (t - 0.15).rem_euclid(0.5);
                if t >= 0.15 { synth_kick_sample(ph) } else { 0.0 }
            } else {
                0.0
            };
            let vocal = if (2.30..2.65).contains(&t) || (3.10..3.45).contains(&t) {
                synth_vocal_phrase(t)
            } else {
                0.0
            };
            let s = ((kick + vocal).clamp(-1.0, 1.0) * 32767.0) as i16;
            pcm.push(s);
            pcm.push(s);
        }

        let mut bass_hits = 0;
        let mut vocal_hits = 0;
        let mut buf = [0i64; MAX_PENDING_BEATS];
        for chunk in pcm.chunks(2048 * 2) {
            unsafe {
                eq.detect_beats_with(chunk.as_ptr() as *const u8, chunk.len() / 2, 4, 1);
            }
            let n = eq.beat.drain_into(&mut buf);
            for ev in &buf[..n] {
                let (_, _, kind, _) = unpack(*ev);
                if kind == BEAT_KIND_BASS || kind == BEAT_KIND_SNARE {
                    bass_hits += 1;
                } else if kind == BEAT_KIND_VOCAL {
                    vocal_hits += 1;
                }
            }
        }
        assert!(bass_hits >= 3 && bass_hits <= 5, "expected ~4 bass kicks, got {bass_hits}");
        assert!(vocal_hits >= 1 && vocal_hits <= 3, "expected ~2 vocal entries, got {vocal_hits}");
    }
}

// ============================================================================
// SoundCheck (Gated Loudness Normalization + Soft Peak Ceiling Limiter)
// ============================================================================

const SC_TARGET_RMS: f32 = 0.168;
const SC_MIN_GAIN: f32 = 0.35;
const SC_MAX_GAIN: f32 = 2.20;
const SC_GATE_SQ: f32 = 0.004 * 0.004;
const SC_SETTLED: f32 = 0.002;
const SC_CEILING: f32 = 0.944;

pub struct RustSoundCheck {
    channels: usize,
    is_float: bool,
    hp_a: f32,
    hp_l: f32,
    hp_r: f32,
    fast_a: f32,
    slow_a: f32,
    gain_a: f32,
    rel_a: f32,
    rms_sq: f32,
    current_gain: f32,
    limiter_gain: f32,
    warmup_left: i32,
}

impl RustSoundCheck {
    pub fn new() -> Self {
        Self {
            channels: 0,
            is_float: false,
            hp_a: 0.0,
            hp_l: 0.0,
            hp_r: 0.0,
            fast_a: 0.0,
            slow_a: 0.0,
            gain_a: 0.0,
            rel_a: 0.0,
            rms_sq: SC_TARGET_RMS * SC_TARGET_RMS,
            current_gain: 1.0,
            limiter_gain: 1.0,
            warmup_left: 0,
        }
    }

    pub fn configure(&mut self, channels: usize, sample_rate: i32, is_float: bool) {
        self.channels = channels;
        self.is_float = is_float;
        let sr = (sample_rate.max(1)) as f32;
        self.hp_a = (1.0 - (-2.0 * PI * 140.0 / sr).exp()).clamp(0.001, 0.5);
        self.fast_a = 1.0 - (-1.0 / (0.18 * sr)).exp();
        self.slow_a = 1.0 - (-1.0 / (2.4 * sr)).exp();
        self.gain_a = 1.0 - (-1.0 / (0.35 * sr)).exp();
        self.rel_a = 1.0 - (-1.0 / (0.20 * sr)).exp();
        self.reset(sample_rate);
    }

    pub fn reset(&mut self, sample_rate: i32) {
        self.hp_l = 0.0;
        self.hp_r = 0.0;
        self.rms_sq = SC_TARGET_RMS * SC_TARGET_RMS;
        self.current_gain = 1.0;
        self.limiter_gain = 1.0;
        self.warmup_left = ((sample_rate.max(1) as f32) * 0.65).round() as i32;
    }

    pub unsafe fn process_direct(
        &mut self,
        in_ptr: *const u8,
        out_ptr: *mut u8,
        frames: usize,
        active: bool,
    ) {
        if frames == 0 || (self.channels != 1 && self.channels != 2) {
            return;
        }
        let sample_bytes = if self.is_float { 4 } else { 2 };
        let frame_bytes = self.channels * sample_bytes;
        if !active
            && (self.current_gain - 1.0).abs() < SC_SETTLED
            && self.limiter_gain > 1.0 - SC_SETTLED
        {
            self.current_gain = 1.0;
            self.limiter_gain = 1.0;
            ptr::copy_nonoverlapping(in_ptr, out_ptr, frames * frame_bytes);
            return;
        }

        let ch = self.channels;
        let hp_a = self.hp_a;
        let fast_a = self.fast_a;
        let slow_a = self.slow_a;
        let g_a = self.gain_a;
        let rel = self.rel_a;

        let mut hp_l = self.hp_l;
        let mut hp_r = self.hp_r;
        let mut rms_sq = self.rms_sq;
        let mut cur_gain = self.current_gain;
        let mut lim_gain = self.limiter_gain;
        let mut warmup = self.warmup_left;

        let in_f32 = in_ptr as *const f32;
        let out_f32 = out_ptr as *mut f32;
        let in_i16 = in_ptr as *const i16;
        let out_i16 = out_ptr as *mut i16;
        const INV_32768: f32 = 1.0 / 32768.0;

        for f in 0..frames {
            let idx = f * ch;
            let (l, r) = if self.is_float {
                let sl = ptr::read_unaligned(in_f32.add(idx));
                let sr = if ch == 2 { ptr::read_unaligned(in_f32.add(idx + 1)) } else { sl };
                (sl, sr)
            } else {
                let sl = (ptr::read_unaligned(in_i16.add(idx)) as f32) * INV_32768;
                let sr = if ch == 2 {
                    (ptr::read_unaligned(in_i16.add(idx + 1)) as f32) * INV_32768
                } else {
                    sl
                };
                (sl, sr)
            };

            if active {
                hp_l += hp_a * (l - hp_l);
                let hl = l - hp_l;
                let inst_sq = if ch == 2 {
                    hp_r += hp_a * (r - hp_r);
                    let hr = r - hp_r;
                    0.5 * (hl * hl + hr * hr)
                } else {
                    hl * hl
                };
                if inst_sq > SC_GATE_SQ {
                    let env_a = if warmup > 0 {
                        warmup -= 1;
                        fast_a
                    } else {
                        slow_a
                    };
                    rms_sq += env_a * (inst_sq - rms_sq);
                }
                let rms = rms_sq.max(1e-9).sqrt();
                let target_gain = (SC_TARGET_RMS / rms).clamp(SC_MIN_GAIN, SC_MAX_GAIN);
                cur_gain += g_a * (target_gain - cur_gain);
            } else {
                cur_gain += g_a * (1.0 - cur_gain);
            }

            let mut out_l = l * cur_gain;
            let mut out_r = r * cur_gain;
            let peak = out_l.abs().max(out_r.abs());
            lim_gain += (1.0 - lim_gain) * rel;
            if peak * lim_gain > SC_CEILING && peak > 1e-9 {
                lim_gain = SC_CEILING / peak;
            }
            out_l *= lim_gain;
            out_r *= lim_gain;

            if self.is_float {
                ptr::write_unaligned(out_f32.add(idx), out_l);
                if ch == 2 {
                    ptr::write_unaligned(out_f32.add(idx + 1), out_r);
                }
            } else {
                let il = (out_l * 32768.0).round().clamp(-32768.0, 32767.0) as i16;
                ptr::write_unaligned(out_i16.add(idx), il);
                if ch == 2 {
                    let ir = (out_r * 32768.0).round().clamp(-32768.0, 32767.0) as i16;
                    ptr::write_unaligned(out_i16.add(idx + 1), ir);
                }
            }
        }

        self.hp_l = hp_l;
        self.hp_r = hp_r;
        self.rms_sq = rms_sq;
        self.current_gain = cur_gain;
        self.limiter_gain = lim_gain;
        self.warmup_left = warmup;
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSoundCheckCreate(
    _env: JNIEnv,
    _class: jclass,
) -> jlong {
    Box::into_raw(Box::new(RustSoundCheck::new())) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSoundCheckDestroy(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut RustSoundCheck));
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSoundCheckConfigure(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    channels: jint,
    sample_rate: jint,
    is_float: jboolean,
) {
    if handle == 0 {
        return;
    }
    let sc = &mut *(handle as *mut RustSoundCheck);
    sc.configure(channels.max(0) as usize, sample_rate, is_float != JNI_FALSE);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSoundCheckReset(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    sample_rate: jint,
) {
    if handle == 0 {
        return;
    }
    let sc = &mut *(handle as *mut RustSoundCheck);
    sc.reset(sample_rate);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeSoundCheckProcessDirect(
    env: JNIEnv,
    _class: jclass,
    handle: jlong,
    in_buf: jobject,
    in_pos: jint,
    out_buf: jobject,
    out_pos: jint,
    frames: jint,
    active: jboolean,
) -> jboolean {
    if handle == 0 || frames <= 0 {
        return JNI_FALSE;
    }
    let in_base = jni_get_direct_buffer_address(env, in_buf);
    let out_base = jni_get_direct_buffer_address(env, out_buf);
    if in_base.is_null() || out_base.is_null() {
        return JNI_FALSE;
    }
    let sc = &mut *(handle as *mut RustSoundCheck);
    sc.process_direct(
        in_base.add(in_pos.max(0) as usize),
        out_base.add(out_pos.max(0) as usize),
        frames as usize,
        active != JNI_FALSE,
    );
    JNI_TRUE
}

// ============================================================================
// Perceptual OKLab Artwork Palette Extractor + Separable Fast Stack Blur
// ============================================================================

#[inline(always)]
fn srgb_to_linear_channel(c: u8) -> f32 {
    let s = (c as f32) * (1.0 / 255.0);
    if s <= 0.04045 {
        s * (1.0 / 12.92)
    } else {
        ((s + 0.055) * (1.0 / 1.055)).powf(2.4)
    }
}

#[inline(always)]
fn linear_rgb_to_oklab(r: f32, g: f32, b: f32) -> (f32, f32, f32) {
    let l = (0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b).max(0.0).cbrt();
    let m = (0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b).max(0.0).cbrt();
    let s = (0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b).max(0.0).cbrt();
    (
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )
}

#[inline(always)]
fn rgb_to_hsl(r: f32, g: f32, b: f32) -> (f32, f32, f32) {
    let max_c = r.max(g).max(b);
    let min_c = r.min(g).min(b);
    let l = 0.5 * (max_c + min_c);
    let d = max_c - min_c;
    if d < 1e-5 {
        return (0.0, 0.0, l);
    }
    let s = d / (1.0 - (2.0 * l - 1.0).abs()).max(1e-5);
    let mut h = if (max_c - r).abs() < 1e-6 {
        (g - b) / d + if g < b { 6.0 } else { 0.0 }
    } else if (max_c - g).abs() < 1e-6 {
        (b - r) / d + 2.0
    } else {
        (r - g) / d + 4.0
    };
    h *= 60.0;
    (h.rem_euclid(360.0), s.clamp(0.0, 1.0), l.clamp(0.0, 1.0))
}

#[inline(always)]
fn hsl_to_argb(h: f32, s: f32, l: f32) -> i32 {
    let h = h.rem_euclid(360.0);
    let s = s.clamp(0.0, 1.0);
    let l = l.clamp(0.0, 1.0);
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = l - 0.5 * c;
    let (r1, g1, b1) = match (h / 60.0) as i32 {
        0 => (c, x, 0.0),
        1 => (x, c, 0.0),
        2 => (0.0, c, x),
        3 => (0.0, x, c),
        4 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let r = ((r1 + m) * 255.0).round().clamp(0.0, 255.0) as i32;
    let g = ((g1 + m) * 255.0).round().clamp(0.0, 255.0) as i32;
    let b = ((b1 + m) * 255.0).round().clamp(0.0, 255.0) as i32;
    (0xFF << 24) | (r << 16) | (g << 8) | b
}

#[inline(always)]
fn readable_under_white_argb(argb: i32) -> i32 {
    let r = ((argb >> 16) & 0xFF) as f32;
    let g = ((argb >> 8) & 0xFF) as f32;
    let b = (argb & 0xFF) as f32;
    let mut best = argb | (0xFF << 24);
    for step in 0..=16 {
        let t = (step as f32) * 0.05;
        let k = 1.0 - t;
        let mr = (r * k).round().clamp(0.0, 255.0) as u8;
        let mg = (g * k).round().clamp(0.0, 255.0) as u8;
        let mb = (b * k).round().clamp(0.0, 255.0) as u8;
        let lum = 0.2126 * srgb_to_linear_channel(mr)
            + 0.7152 * srgb_to_linear_channel(mg)
            + 0.0722 * srgb_to_linear_channel(mb);
        best = (0xFF << 24) | ((mr as i32) << 16) | ((mg as i32) << 8) | (mb as i32);
        if lum <= 0.14 {
            break;
        }
    }
    best
}

#[derive(Clone, Copy)]
struct ColorCandidate {
    argb: i32,
    count: u32,
    ok_l: f32,
    ok_a: f32,
    ok_b: f32,
    chroma: f32,
    hsl_h: f32,
    hsl_s: f32,
    hsl_l: f32,
    score: f32,
}

const FALLBACK_PALETTE: [i32; 6] = [
    0xFF3A1C71u32 as i32,
    0xFFD76D77u32 as i32,
    0xFF2B5876u32 as i32,
    0xFFFFAF7Bu32 as i32,
    0xFF22133Fu32 as i32,
    0xFF44325Eu32 as i32,
];

/// Extracts `[mesh0, mesh1, mesh2, mesh3, darkTint, mutedAccent]` from ARGB pixels in one pass.
pub fn extract_artwork_colors(pixels: &[i32]) -> [i32; 6] {
    if pixels.is_empty() {
        return FALLBACK_PALETTE;
    }

    // 12-bit color cube (4 bits/channel -> 4096 stack/vec cells). Sparse tracking avoids clearing/scanning blanks.
    const BUCKETS: usize = 4096;
    let mut counts = vec![0u32; BUCKETS];
    let mut sum_r = vec![0u32; BUCKETS];
    let mut sum_g = vec![0u32; BUCKETS];
    let mut sum_b = vec![0u32; BUCKETS];
    let mut occupied: Vec<u16> = Vec::with_capacity(256);

    let stride = (pixels.len() / 4096).max(1);
    let mut i = 0usize;
    while i < pixels.len() {
        let px = pixels[i];
        i += stride;
        let a = (px >> 24) & 0xFF;
        if a < 16 {
            continue;
        }
        let r = ((px >> 16) & 0xFF) as u32;
        let g = ((px >> 8) & 0xFF) as u32;
        let b = (px & 0xFF) as u32;
        let idx = ((((r >> 4) & 0xF) << 8) | (((g >> 4) & 0xF) << 4) | ((b >> 4) & 0xF)) as usize;
        if counts[idx] == 0 {
            occupied.push(idx as u16);
        }
        counts[idx] += 1;
        sum_r[idx] += r;
        sum_g[idx] += g;
        sum_b[idx] += b;
    }

    if occupied.is_empty() {
        return FALLBACK_PALETTE;
    }

    let mut all: Vec<ColorCandidate> = Vec::with_capacity(occupied.len());
    for &cell in &occupied {
        let idx = cell as usize;
        let cnt = counts[idx];
        let r8 = ((sum_r[idx] + cnt / 2) / cnt).min(255) as u8;
        let g8 = ((sum_g[idx] + cnt / 2) / cnt).min(255) as u8;
        let b8 = ((sum_b[idx] + cnt / 2) / cnt).min(255) as u8;
        let lr = srgb_to_linear_channel(r8);
        let lg = srgb_to_linear_channel(g8);
        let lb = srgb_to_linear_channel(b8);
        let (ok_l, ok_a, ok_b) = linear_rgb_to_oklab(lr, lg, lb);
        let chroma = (ok_a * ok_a + ok_b * ok_b).sqrt();
        let (hsl_h, hsl_s, hsl_l) = rgb_to_hsl(r8 as f32 / 255.0, g8 as f32 / 255.0, b8 as f32 / 255.0);
        let argb = (0xFF << 24) | ((r8 as i32) << 16) | ((g8 as i32) << 8) | (b8 as i32);
        let score = (cnt as f32) * (0.45 + 2.6 * chroma);
        all.push(ColorCandidate {
            argb,
            count: cnt,
            ok_l,
            ok_a,
            ok_b,
            chroma,
            hsl_h,
            hsl_s,
            hsl_l,
            score,
        });
    }

    all.sort_unstable_by(|a, b| b.score.total_cmp(&a.score));

    // Prefer rich non-extreme candidates first, falling back to full list on dark/monochrome covers.
    let mut pool: Vec<ColorCandidate> = all
        .iter()
        .copied()
        .filter(|c| c.hsl_l >= 0.08 && c.hsl_l <= 0.90)
        .take(48)
        .collect();
    if pool.is_empty() {
        pool = all.iter().copied().take(48).collect();
    } else if pool.len() < 4 {
        for c in &all {
            if pool.len() >= 12 {
                break;
            }
            if !pool.iter().any(|p| p.argb == c.argb) {
                pool.push(*c);
            }
        }
    }

    let mut mesh: Vec<ColorCandidate> = Vec::with_capacity(4);
    mesh.push(pool[0]);
    let top_score = pool[0].score.max(1.0);

    // Greedy perceptual farthest-first selection in OKLab space.
    for pass_thresh in [0.020f32, 0.006f32, 0.0f32] {
        while mesh.len() < 4 {
            let mut best_idx: Option<usize> = None;
            let mut best_metric = -1.0f32;
            for (idx, c) in pool.iter().enumerate() {
                if mesh.iter().any(|m| m.argb == c.argb) {
                    continue;
                }
                let mut min_d2 = f32::MAX;
                for m in &mesh {
                    let dl = c.ok_l - m.ok_l;
                    let da = c.ok_a - m.ok_a;
                    let db = c.ok_b - m.ok_b;
                    let d2 = dl * dl * 0.8 + da * da + db * db;
                    if d2 < min_d2 {
                        min_d2 = d2;
                    }
                }
                if min_d2 < pass_thresh {
                    continue;
                }
                let weight = 0.55 + 0.45 * (c.score / top_score).sqrt();
                let metric = min_d2 * weight;
                if metric > best_metric {
                    best_metric = metric;
                    best_idx = Some(idx);
                }
            }
            if let Some(idx) = best_idx {
                mesh.push(pool[idx]);
            } else {
                break;
            }
        }
    }

    let mut out = [0i32; 6];
    let base_len = mesh.len().max(1);
    for slot in 0..4 {
        out[slot] = if slot < mesh.len() {
            mesh[slot].argb
        } else {
            let step = (slot - base_len + 1) as f32;
            let src = &mesh[(slot - base_len) % base_len];
            hsl_to_argb(
                src.hsl_h + 24.0 * step,
                src.hsl_s.max(0.28),
                (src.hsl_l + 0.12 * step).clamp(0.22, 0.68),
            )
        };
    }

    // Slot 4: dark vibrant / dominant tint readable under white text (WCAG <= 0.14 luminance).
    let dark_pick = all
        .iter()
        .max_by(|a, b| {
            let sa = (a.count as f32) * (0.4 + 2.2 * a.chroma) * if a.hsl_l <= 0.44 { 2.2 } else { 0.55 };
            let sb = (b.count as f32) * (0.4 + 2.2 * b.chroma) * if b.hsl_l <= 0.44 { 2.2 } else { 0.55 };
            sa.total_cmp(&sb)
        })
        .unwrap_or(&pool[0]);
    out[4] = readable_under_white_argb(dark_pick.argb);

    // Slot 5: muted / mid-tone surface tint for headers and secondary cards.
    let muted_pick = all
        .iter()
        .max_by(|a, b| {
            let wa = if (0.18..=0.58).contains(&a.hsl_l) { 1.8 } else { 0.6 }
                * if (0.12..=0.55).contains(&a.hsl_s) { 1.5 } else { 0.8 }
                * (a.count as f32);
            let wb = if (0.18..=0.58).contains(&b.hsl_l) { 1.8 } else { 0.6 }
                * if (0.12..=0.55).contains(&b.hsl_s) { 1.5 } else { 0.8 }
                * (b.count as f32);
            wa.total_cmp(&wb)
        })
        .unwrap_or(&pool[0]);
    out[5] = muted_pick.argb;

    out
}

/// Fast 3-pass separable box blur with clamp-to-edge on packed ARGB pixels (`O(W * H)` independent of radius).
pub fn blur_argb_in_place(pixels: &mut [i32], width: usize, height: usize, radius: i32) {
    if width < 2 || height < 2 || radius <= 0 || pixels.len() < width * height {
        return;
    }
    let rad = (radius as usize).min(width.max(height));
    let div = (rad * 2 + 1) as u32;
    let mut line = vec![0i32; width.max(height)];

    for _pass in 0..3 {
        // Horizontal pass
        for y in 0..height {
            let row_off = y * width;
            let row = &mut pixels[row_off..row_off + width];
            line[..width].copy_from_slice(row);

            let mut sa = 0u32;
            let mut sr = 0u32;
            let mut sg = 0u32;
            let mut sb = 0u32;
            for k in -(rad as isize)..=(rad as isize) {
                let x = k.clamp(0, (width - 1) as isize) as usize;
                let px = line[x];
                sa += ((px >> 24) & 0xFF) as u32;
                sr += ((px >> 16) & 0xFF) as u32;
                sg += ((px >> 8) & 0xFF) as u32;
                sb += (px & 0xFF) as u32;
            }
            for x in 0..width {
                let a = (sa + div / 2) / div;
                let r = (sr + div / 2) / div;
                let g = (sg + div / 2) / div;
                let b = (sb + div / 2) / div;
                row[x] = ((a as i32) << 24) | ((r as i32) << 16) | ((g as i32) << 8) | (b as i32);

                let add_x = (x + rad + 1).min(width - 1);
                let rem_x = if x >= rad { x - rad } else { 0 };
                let p_add = line[add_x];
                let p_rem = line[rem_x];
                sa = sa + (((p_add >> 24) & 0xFF) as u32) - (((p_rem >> 24) & 0xFF) as u32);
                sr = sr + (((p_add >> 16) & 0xFF) as u32) - (((p_rem >> 16) & 0xFF) as u32);
                sg = sg + (((p_add >> 8) & 0xFF) as u32) - (((p_rem >> 8) & 0xFF) as u32);
                sb = sb + ((p_add & 0xFF) as u32) - ((p_rem & 0xFF) as u32);
            }
        }

        // Vertical pass
        for x in 0..width {
            for y in 0..height {
                line[y] = pixels[y * width + x];
            }
            let mut sa = 0u32;
            let mut sr = 0u32;
            let mut sg = 0u32;
            let mut sb = 0u32;
            for k in -(rad as isize)..=(rad as isize) {
                let y = k.clamp(0, (height - 1) as isize) as usize;
                let px = line[y];
                sa += ((px >> 24) & 0xFF) as u32;
                sr += ((px >> 16) & 0xFF) as u32;
                sg += ((px >> 8) & 0xFF) as u32;
                sb += (px & 0xFF) as u32;
            }
            for y in 0..height {
                let a = (sa + div / 2) / div;
                let r = (sr + div / 2) / div;
                let g = (sg + div / 2) / div;
                let b = (sb + div / 2) / div;
                pixels[y * width + x] =
                    ((a as i32) << 24) | ((r as i32) << 16) | ((g as i32) << 8) | (b as i32);

                let add_y = (y + rad + 1).min(height - 1);
                let rem_y = if y >= rad { y - rad } else { 0 };
                let p_add = line[add_y];
                let p_rem = line[rem_y];
                sa = sa + (((p_add >> 24) & 0xFF) as u32) - (((p_rem >> 24) & 0xFF) as u32);
                sr = sr + (((p_add >> 16) & 0xFF) as u32) - (((p_rem >> 16) & 0xFF) as u32);
                sg = sg + (((p_add >> 8) & 0xFF) as u32) - (((p_rem >> 8) & 0xFF) as u32);
                sb = sb + ((p_add & 0xFF) as u32) - ((p_rem & 0xFF) as u32);
            }
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeExtractArtworkColors(
    env: JNIEnv,
    _class: jclass,
    pixels_arr: jintArray,
    width: jint,
    height: jint,
) -> jintArray {
    if pixels_arr.is_null() || width <= 0 || height <= 0 {
        return ptr::null_mut();
    }
    let arr_len = jni_get_array_length(env, pixels_arr).max(0) as usize;
    let need = (width as usize).saturating_mul(height as usize).min(arr_len);
    if need == 0 {
        return ptr::null_mut();
    }
    let mut pixels = vec![0i32; need];
    jni_get_int_array_region(env, pixels_arr, 0, need as jsize, pixels.as_mut_ptr());
    let colors = extract_artwork_colors(&pixels);
    let out = jni_new_int_array(env, colors.len() as jsize);
    if !out.is_null() {
        jni_set_int_array_region(env, out, 0, colors.len() as jsize, colors.as_ptr());
    }
    out
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_spotui_rust_OrpheonRustAudio_nativeBlurArgb(
    env: JNIEnv,
    _class: jclass,
    pixels_arr: jintArray,
    width: jint,
    height: jint,
    radius: jint,
) -> jboolean {
    if pixels_arr.is_null() || width <= 1 || height <= 1 || radius <= 0 {
        return JNI_FALSE;
    }
    let w = width as usize;
    let h = height as usize;
    let need = w.saturating_mul(h);
    let arr_len = jni_get_array_length(env, pixels_arr).max(0) as usize;
    if need == 0 || arr_len < need {
        return JNI_FALSE;
    }
    let mut pixels = vec![0i32; need];
    jni_get_int_array_region(env, pixels_arr, 0, need as jsize, pixels.as_mut_ptr());
    blur_argb_in_place(&mut pixels, w, h, radius);
    jni_set_int_array_region(env, pixels_arr, 0, need as jsize, pixels.as_ptr());
    JNI_TRUE
}

#[cfg(test)]
mod visual_and_dsp_tests {
    use super::*;

    #[test]
    fn sound_check_normalizes_quiet_signal_and_clamps_peaks() {
        let mut sc = RustSoundCheck::new();
        let sr = 48_000;
        sc.configure(2, sr, true);
        let frames = 24_000;
        let mut input = vec![0.0f32; frames * 2];
        let mut output = vec![0.0f32; frames * 2];
        for i in 0..frames {
            let s = (2.0 * PI * 440.0 * (i as f32 / sr as f32)).sin() * 0.04;
            input[i * 2] = s;
            input[i * 2 + 1] = s;
        }
        unsafe {
            sc.process_direct(input.as_ptr() as *const u8, output.as_mut_ptr() as *mut u8, frames, true);
        }
        let peak_in = input[frames..].iter().fold(0.0f32, |m, v| m.max(v.abs()));
        let peak_out = output[frames..].iter().fold(0.0f32, |m, v| m.max(v.abs()));
        assert!(peak_out > peak_in * 1.4, "expected SoundCheck boost: in={peak_in}, out={peak_out}");
        assert!(peak_out <= SC_CEILING + 1e-4, "expected ceiling compliance: out={peak_out}");
    }

    #[test]
    fn artwork_palette_separates_distinct_hues_and_handles_monochrome() {
        let mut img = vec![0i32; 64 * 64];
        for y in 0..64 {
            for x in 0..64 {
                img[y * 64 + x] = match (x < 32, y < 32) {
                    (true, true) => 0xFFE63946u32 as i32,
                    (false, true) => 0xFF457B9Du32 as i32,
                    (true, false) => 0xFF2A9D8Fu32 as i32,
                    (false, false) => 0xFFF4A261u32 as i32,
                };
            }
        }
        let colors = extract_artwork_colors(&img);
        for i in 0..4 {
            assert_eq!((colors[i] >> 24) & 0xFF, 0xFF);
        }
        // Dark tint must satisfy WCAG luminance <= 0.14 so white text stays crisp
        let d = colors[4];
        let lum = 0.2126 * srgb_to_linear_channel(((d >> 16) & 0xFF) as u8)
            + 0.7152 * srgb_to_linear_channel(((d >> 8) & 0xFF) as u8)
            + 0.0722 * srgb_to_linear_channel((d & 0xFF) as u8);
        assert!(lum <= 0.145, "dark tint luminance too high: {lum}");

        // Monochrome sleeve stays neutral gray/silver without falling back to purple
        let mono = vec![0xFF505050u32 as i32; 32 * 32];
        let mono_out = extract_artwork_colors(&mono);
        assert_eq!((mono_out[0] >> 16) & 0xFF, (mono_out[0] >> 8) & 0xFF);
    }

    #[test]
    fn stack_blur_smooths_edges_and_preserves_opaque_alpha() {
        let mut px = vec![0xFF000000u32 as i32; 32 * 32];
        for y in 8..24 {
            for x in 8..24 {
                px[y * 32 + x] = 0xFFFFFFFFu32 as i32;
            }
        }
        blur_argb_in_place(&mut px, 32, 32, 4);
        let center_r = (px[16 * 32 + 16] >> 16) & 0xFF;
        let border_r = (px[16 * 32 + 7] >> 16) & 0xFF;
        assert!(center_r > 180 && border_r > 20 && border_r < center_r, "blur gradient center={center_r}, border={border_r}");
        assert_eq!((px[0] >> 24) & 0xFF, 0xFF);
    }
}
