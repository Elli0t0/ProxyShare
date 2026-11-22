//! Speed history: the engine samples its own live counters once a
//! second while busy and keeps the last 120 seconds, so the chart
//! shows the full 2 minutes even when the UI was hidden. No Kotlin
//! timer, no JNI upcall per connection: the UI pulls the history with
//! one nativeHistory call per second while it is visible, and the
//! sampler itself only runs while a connection is open.

use std::collections::VecDeque;
use std::time::Duration;

/// One second of speed, in bytes per second.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct SpeedSample {
    pub up_bps: u64,
    pub down_bps: u64,
}

/// How many seconds the history keeps.
pub const HISTORY_LEN: usize = 120;

/// Below this raw rate a direction reads as exactly 0: keepalive
/// trickles never register as speed.
pub const SNAP_ZERO_BPS: f64 = 64.0;

/// One EMA step (alpha 0.5), the same smoothing the Kotlin sampler
/// used before the engine owned the stats: the raw rate is the byte
/// delta over the real elapsed time; a direction with no new bytes
/// (or under 64 B/s) snaps to exactly 0 instead of decaying forever.
/// The first sample after busy halves the raw rate (EMA from 0).
pub fn smooth_sample(
    prev_up: u64,
    prev_down: u64,
    now_up: u64,
    now_down: u64,
    elapsed: Duration,
    prev: SpeedSample,
) -> SpeedSample {
    let seconds = elapsed.as_secs_f64().max(0.001);
    let ema = |delta: u64, prev_bps: u64| -> u64 {
        if delta == 0 {
            return 0;
        }
        let raw = delta as f64 / seconds;
        if raw < SNAP_ZERO_BPS {
            return 0;
        }
        (0.5 * raw + 0.5 * prev_bps as f64) as u64
    };
    SpeedSample {
        up_bps: ema(now_up.saturating_sub(prev_up), prev.up_bps),
        down_bps: ema(now_down.saturating_sub(prev_down), prev.down_bps),
    }
}

/// The last 120 seconds of speed, one sample per unix second. Newer
/// samples replace an entry with the same second (ticks are 1 s
/// apart, so this only fires on clock adjustments or in tests).
pub struct History {
    entries: VecDeque<(u64, SpeedSample)>,
}

impl History {
    pub fn new() -> Self {
        Self {
            entries: VecDeque::new(),
        }
    }

    pub fn push(&mut self, unix_sec: u64, sample: SpeedSample) {
        if let Some(last) = self.entries.back_mut() {
            if last.0 == unix_sec {
                last.1 = sample;
                return;
            }
        }
        self.entries.push_back((unix_sec, sample));
        while self.entries.len() > HISTORY_LEN {
            self.entries.pop_front();
        }
    }

    /// The latest sample, or zeros when nothing was ever sampled.
    /// The snapshot's speedUp/speedDown come from here.
    pub fn latest(&self) -> SpeedSample {
        self.entries.back().map(|(_, s)| *s).unwrap_or_default()
    }

    /// The last 120 seconds as "up,down;up,down;…" oldest first, one
    /// pair per second, aligned to `now_sec`: seconds with no sample
    /// read as "0,0", so the chart always gets a full 120 s even
    /// after the UI was hidden or the sampler was idle.
    pub fn render(&self, now_sec: u64) -> String {
        let mut out = String::new();
        for i in 0..HISTORY_LEN {
            let sec = now_sec.saturating_sub((HISTORY_LEN - 1 - i) as u64);
            let sample = self
                .entries
                .iter()
                .rev()
                .find(|(s, _)| *s == sec)
                .map(|(_, sample)| *sample)
                .unwrap_or_default();
            if i > 0 {
                out.push(';');
            }
            out.push_str(&format!("{},{}", sample.up_bps, sample.down_bps));
        }
        out
    }

    /// Drop every sample (a fresh start gets a fresh history).
    pub fn clear(&mut self) {
        self.entries.clear();
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.len()
    }
}

impl Default for History {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(up: u64, down: u64) -> SpeedSample {
        SpeedSample { up_bps: up, down_bps: down }
    }

    #[test]
    fn zero_new_bytes_snaps_to_zero() {
        assert_eq!(
            smooth_sample(1000, 2000, 1000, 2000, Duration::from_secs(1), sample(500, 600)),
            sample(0, 0)
        );
    }

    #[test]
    fn first_tick_halves_raw_speed() {
        // 2000 new up bytes in 1 s, EMA from 0 with alpha 0.5 -> 1000.
        assert_eq!(
            smooth_sample(0, 0, 2000, 3000, Duration::from_secs(1), sample(0, 0)),
            sample(1000, 1500)
        );
    }

    #[test]
    fn ema_blends_with_previous() {
        // Raw up 2000/s, prev 1000 -> 1500; down has no new bytes -> 0.
        assert_eq!(
            smooth_sample(1000, 5000, 3000, 5000, Duration::from_secs(1), sample(1000, 800)),
            sample(1500, 0)
        );
    }

    #[test]
    fn elapsed_time_scales_speed() {
        // 2000 new bytes in 2 s -> 1000 raw, EMA from 0 -> 500.
        assert_eq!(
            smooth_sample(0, 0, 2000, 0, Duration::from_secs(2), sample(0, 0)),
            sample(500, 0)
        );
    }

    #[test]
    fn trickle_under_64_bps_snaps_to_zero() {
        // 30 bytes in 1 s is a keepalive, not speed.
        assert_eq!(
            smooth_sample(0, 0, 30, 0, Duration::from_secs(1), sample(0, 0)),
            sample(0, 0)
        );
        // 100 B/s clears the threshold: EMA from 0 -> 50.
        assert_eq!(
            smooth_sample(0, 0, 100, 0, Duration::from_secs(1), sample(0, 0)),
            sample(50, 0)
        );
    }

    #[test]
    fn push_replaces_same_second_and_caps_at_120() {
        let mut h = History::new();
        for sec in 1..=120u64 {
            h.push(sec, sample(sec, 0));
        }
        assert_eq!(h.len(), 120);
        h.push(120, sample(999, 0));
        assert_eq!(h.len(), 120);
        assert_eq!(h.latest(), sample(999, 0));
        h.push(121, sample(121, 0));
        assert_eq!(h.len(), 120);
        assert_eq!(h.entries.front().map(|(s, _)| *s), Some(2));
    }

    #[test]
    fn render_fills_gaps_with_zeros() {
        let mut h = History::new();
        h.push(1000, sample(500, 600));
        h.push(1002, sample(700, 800));
        let text = h.render(1002);
        let pairs: Vec<&str> = text.split(';').collect();
        assert_eq!(pairs.len(), 120);
        // Oldest is now-119 = 883, all zeros until the two samples.
        assert_eq!(pairs[117], "500,600"); // second 1000
        assert_eq!(pairs[118], "0,0"); // second 1001: gap
        assert_eq!(pairs[119], "700,800"); // second 1002
        assert_eq!(pairs[0], "0,0");
    }

    #[test]
    fn render_of_empty_history_is_all_zeros() {
        let h = History::new();
        let text = h.render(1000);
        assert_eq!(text.split(';').count(), 120);
        assert!(text.split(';').all(|p| p == "0,0"));
    }
}
