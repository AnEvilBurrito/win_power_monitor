use serde::{Deserialize, Serialize};
use std::{collections::VecDeque, time::Duration};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum State {
    Charging,
    Discharging,
    Idle,
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
    pub ac_online: Option<bool>,
    pub battery_present: Option<bool>,
    pub source: String,
    pub measurement_kind: String,
    pub estimated: bool,
    pub available: bool,
    pub reason: Option<String>,
    pub gap: bool,
    pub diagnostics: Vec<String>,
}
impl Sample {
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
            ac_online: None,
            battery_present: None,
            source: "none".into(),
            measurement_kind: "unavailable".into(),
            estimated: false,
            available: false,
            reason: Some("no_valid_battery_rate".into()),
            gap: false,
            diagnostics: vec![],
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct Stats {
    pub count: u64,
    pub sum: f64,
    pub min: Option<f64>,
    pub max: Option<f64>,
    pub discharge_wh: f64,
    pub charge_wh: f64,
    pub covered_s: f64,
}
impl Stats {
    pub fn average(&self) -> Option<f64> {
        (self.count > 0).then(|| self.sum / self.count as f64)
    }
    pub fn add(&mut self, s: &Sample, previous: Option<&Sample>, all: bool, rate: Duration) {
        let eligible = |s: &Sample| all || s.state == State::Discharging;
        if !eligible(s) {
            return;
        }
        if let Some(w) = s.watts.filter(|w| w.is_finite()) {
            self.count += 1;
            self.sum += w;
            self.min = Some(self.min.map_or(w, |x| x.min(w)));
            self.max = Some(self.max.map_or(w, |x| x.max(w)));
            if let Some(p) = previous.filter(|p| eligible(p) && p.state == s.state && !s.gap) {
                let dt = s.elapsed_s - p.elapsed_s;
                if dt > 0.0
                    && dt <= rate.as_secs_f64() * 2.0
                    && let Some(pw) = p.watts.filter(|w| w.is_finite())
                {
                    let wh = (w + pw) * 0.5 * dt / 3600.0;
                    self.discharge_wh += (-wh).max(0.0);
                    self.charge_wh += wh.max(0.0);
                    self.covered_s += dt;
                }
            }
        }
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
        s.gap = self
            .previous
            .as_ref()
            .is_some_and(|p| s.elapsed_s - p.elapsed_s > self.rate.as_secs_f64() * 2.0);
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
