use serde::{Deserialize, Serialize};
use std::{collections::VecDeque, time::Duration};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum State {
    Charging,
    Discharging,
    Idle,
    Mixed,
    Unknown,
}
#[derive(Clone, Debug, Serialize)]
pub struct Sample {
    pub ts: String,
    pub elapsed_s: f64,
    pub state: State,
    pub watts: Option<f64>,
    pub volts: Option<f64>,
    pub amps: Option<f64>,
    pub pct: Option<f64>,
    pub eta_s: Option<u32>,
    pub eta_source: Option<String>,
    pub remaining_wh: Option<f64>,
    pub drain_pct_hr: Option<f64>,
    pub poll_count: u64,
    pub unchanged_polls: u64,
    pub query_ms: f64,
    pub ac_online: Option<bool>,
    pub battery_present: Option<bool>,
    pub source: String,
    pub measurement_kind: String,
    pub estimated: bool,
    pub available: bool,
    pub reason: Option<String>,
    pub gap: bool,
    pub diagnostics: Vec<String>,
    pub topology_epoch: u64,
    pub transition: bool,
    pub battery_count: usize,
    pub collection_start_s: Option<f64>,
    pub collection_end_s: Option<f64>,
    pub batteries: Vec<crate::native_battery::BatteryDiagnostic>,
}
impl Sample {
    pub fn is_system_power(&self) -> bool {
        self.measurement_kind == "system_input_power_user_confirmed"
    }
    pub fn empty(elapsed_s: f64) -> Self {
        Self {
            ts: chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
            elapsed_s,
            state: State::Unknown,
            watts: None,
            volts: None,
            amps: None,
            pct: None,
            eta_s: None,
            eta_source: None,
            remaining_wh: None,
            drain_pct_hr: None,
            poll_count: 0,
            unchanged_polls: 0,
            query_ms: 0.0,
            ac_online: None,
            battery_present: None,
            source: "none".into(),
            measurement_kind: "unavailable".into(),
            estimated: false,
            available: false,
            reason: Some("no_valid_battery_rate".into()),
            gap: false,
            diagnostics: vec![],
            topology_epoch: 0,
            transition: false,
            battery_count: 0,
            collection_start_s: None,
            collection_end_s: None,
            batteries: vec![],
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct Stats {
    pub count: u64,
    pub sum: f64,
    pub magnitude_sum: f64,
    pub magnitude_min: Option<f64>,
    pub magnitude_max: Option<f64>,
    pub min: Option<f64>,
    pub max: Option<f64>,
    pub discharge_wh: f64,
    pub charge_wh: f64,
    pub system_wh: f64,
    pub covered_s: f64,
}
impl Stats {
    pub fn average(&self) -> Option<f64> {
        (self.count > 0).then(|| self.sum / self.count as f64)
    }
    pub fn add(&mut self, s: &Sample, previous: Option<&Sample>, all: bool, rate: Duration) {
        let eligible = |s: &Sample| all || s.state == State::Discharging || s.is_system_power();
        if !eligible(s) {
            return;
        }
        if let Some(w) = s.watts.filter(|w| w.is_finite()) {
            self.count += 1;
            self.sum += w;
            self.magnitude_sum += w.abs();
            self.magnitude_min = Some(self.magnitude_min.map_or(w.abs(), |x| x.min(w.abs())));
            self.magnitude_max = Some(self.magnitude_max.map_or(w.abs(), |x| x.max(w.abs())));
            self.min = Some(self.min.map_or(w, |x| x.min(w)));
            self.max = Some(self.max.map_or(w, |x| x.max(w)));
            if let Some(p) = previous.filter(|p| {
                eligible(p)
                    && p.state == s.state
                    && p.ac_online == s.ac_online
                    && p.source == s.source
                    && p.measurement_kind == s.measurement_kind
                    && !s.gap
                    && !p.transition
                    && !s.transition
                    && p.topology_epoch == s.topology_epoch
            }) {
                let dt = s.elapsed_s - p.elapsed_s;
                if dt > 0.0
                    && dt <= rate.as_secs_f64() * 2.0
                    && let Some(pw) = p.watts.filter(|w| w.is_finite())
                {
                    let wh = (w + pw) * 0.5 * dt / 3600.0;
                    if s.is_system_power() {
                        self.system_wh += wh.max(0.0);
                    } else {
                        self.discharge_wh += (-wh).max(0.0);
                        self.charge_wh += wh.max(0.0);
                    }
                    self.covered_s += dt;
                }
            }
        }
    }
    pub fn display_label(&self, absolute: bool) -> String {
        if !absolute {
            return self.label();
        }
        format!(
            "avg {} min {} max {} W | out {:.3} in {:.3} Wh | {} valid",
            number((self.count > 0).then(|| self.magnitude_sum / self.count as f64)),
            number(self.magnitude_min),
            number(self.magnitude_max),
            self.discharge_wh,
            self.charge_wh,
            self.count
        )
    }
    pub fn label(&self) -> String {
        format!(
            "avg {} min {} max {} W | out {:.4} in {:.4} Wh | {} valid | {:.0}s integrated",
            number(self.average()),
            number(self.min),
            number(self.max),
            self.discharge_wh,
            self.charge_wh,
            self.count,
            self.covered_s
        )
    }
}
pub fn number(n: Option<f64>) -> String {
    n.map_or_else(|| "—".into(), |n| format!("{n:.2}"))
}

#[derive(Clone)]
pub struct History {
    pub samples: VecDeque<Sample>,
    pub session: Stats,
    span: f64,
    cap: usize,
    rate: Duration,
    all: bool,
    previous: Option<Sample>,
}
impl History {
    pub fn new(span: Duration, cap: usize, rate: Duration, all: bool) -> Self {
        Self {
            samples: VecDeque::new(),
            session: Stats::default(),
            span: span.as_secs_f64(),
            cap,
            rate,
            all,
            previous: None,
        }
    }
    pub fn push(&mut self, mut s: Sample) -> Sample {
        s.gap |= self.previous.as_ref().is_some_and(|p| {
            s.transition
                || p.transition
                || s.topology_epoch != p.topology_epoch
                || s.elapsed_s <= p.elapsed_s
                || s.elapsed_s - p.elapsed_s > self.rate.as_secs_f64() * 2.0
        });
        self.session
            .add(&s, self.previous.as_ref(), self.all, self.rate);
        self.previous = Some(s.clone());
        while self
            .samples
            .front()
            .is_some_and(|p| p.elapsed_s < s.elapsed_s - self.span)
        {
            self.samples.pop_front();
        }
        self.samples.push_back(s.clone());
        while self.samples.len() > self.cap {
            self.samples.pop_front();
        }
        s
    }
    pub fn reset(&mut self) {
        self.samples.clear();
        self.session = Stats::default();
        self.previous = None;
    }
    pub fn window(&self) -> Stats {
        let mut stats = Stats::default();
        let mut previous = None;
        for s in &self.samples {
            stats.add(s, previous, self.all, self.rate);
            previous = Some(s);
        }
        stats
    }
}

/// Bounded contiguous discharge window. Percentages are quantized, so a flat slope
/// means no usable estimate, not zero consumption.
pub struct Metrics {
    points: VecDeque<(f64, f64)>,
    previous: Option<Sample>,
    count: u64,
    unchanged: u64,
    rate: Duration,
}
impl Metrics {
    pub fn new(rate: Duration) -> Self {
        Self {
            points: VecDeque::new(),
            previous: None,
            count: 0,
            unchanged: 0,
            rate,
        }
    }
    pub fn apply(&mut self, s: &mut Sample) {
        self.count += 1;
        let dt = self.previous.as_ref().map(|p| s.elapsed_s - p.elapsed_s);
        s.gap |= s.transition
            || self.previous.as_ref().is_some_and(|p| {
                p.transition
                    || p.topology_epoch != s.topology_epoch
                    || p.ac_online != s.ac_online
                    || p.state != s.state
                    || p.source != s.source
                    || p.measurement_kind != s.measurement_kind
            })
            || dt.is_some_and(|dt| dt <= 0.0 || dt > self.rate.as_secs_f64() * 2.0);
        let same = self.previous.as_ref().is_some_and(|p| {
            p.watts == s.watts
                && p.volts == s.volts
                && p.pct == s.pct
                && p.state == s.state
                && p.remaining_wh == s.remaining_wh
                && p.ac_online == s.ac_online
                && p.source == s.source
        });
        self.unchanged = if same && !s.gap {
            self.unchanged + 1
        } else {
            0
        };
        s.poll_count = self.count;
        s.unchanged_polls = self.unchanged;
        s.drain_pct_hr = None;
        if s.gap || s.transition || s.watts.is_none() {
            s.eta_s = None;
            s.eta_source = None;
        }
        if s.state != State::Discharging
            || s.battery_present == Some(false)
            || s.gap
            || s.pct.is_none()
            || s.watts.is_none()
        {
            self.points.clear();
        }
        if s.state == State::Discharging
            && s.battery_present != Some(false)
            && !s.transition
            && s.watts.is_some()
        {
            if let Some(pct) = s.pct.filter(|p| p.is_finite() && (0.0..=100.0).contains(p)) {
                if self.points.back().is_some_and(|(_, p)| pct > *p) {
                    self.points.clear();
                }
                self.points.push_back((s.elapsed_s, pct));
                while self
                    .points
                    .front()
                    .is_some_and(|(t, _)| *t < s.elapsed_s - 300.0)
                    || self.points.len() > 1201
                {
                    self.points.pop_front();
                }
                if self.points.len() >= 2 && s.elapsed_s - self.points[0].0 >= 10.0 {
                    let n = self.points.len() as f64;
                    let origin = self.points[0].0;
                    let mt = self.points.iter().map(|(t, _)| t - origin).sum::<f64>() / n;
                    let mp = self.points.iter().map(|(_, p)| p).sum::<f64>() / n;
                    let numerator = self
                        .points
                        .iter()
                        .map(|(t, p)| (t - origin - mt) * (p - mp))
                        .sum::<f64>();
                    let denominator = self
                        .points
                        .iter()
                        .map(|(t, _)| (t - origin - mt).powi(2))
                        .sum::<f64>();
                    let drain = -numerator / denominator * 3600.0;
                    if drain.is_finite() && drain > 0.0 {
                        s.drain_pct_hr = Some(drain);
                    }
                }
            } else {
                self.points.clear();
            }
            if s.eta_s.is_none() && !s.gap {
                let capacity = s
                    .remaining_wh
                    .zip(s.watts)
                    .filter(|(wh, w)| *wh >= 0.0 && *w < 0.0)
                    .map(|(wh, w)| (wh / -w * 3600.0, "capacity/rate estimate"));
                let slope = s
                    .pct
                    .zip(s.drain_pct_hr)
                    .map(|(p, d)| (p / d * 3600.0, "percentage trend estimate"));
                if let Some((seconds, source)) = capacity
                    .or(slope)
                    .filter(|(t, _)| t.is_finite() && *t >= 0.0 && *t < u32::MAX as f64)
                {
                    s.eta_s = Some(seconds.round() as u32);
                    s.eta_source = Some(source.into());
                }
            }
        } else {
            s.eta_s = None;
            s.eta_source = None;
        }
        self.previous = Some(s.clone());
    }
}

/// Completion timestamps, not query-start timestamps, drive charts and integration.
pub fn stamp_completion(s: &mut Sample, elapsed: f64, ts: String, query_ms: f64) {
    s.elapsed_s = elapsed;
    s.ts = ts;
    s.query_ms = query_ms;
}
