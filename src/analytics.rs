use crate::model::{History, Sample, State};

#[derive(Clone, Debug, Default)]
pub struct Summary {
    pub observed_s: f64,
    pub count: usize,
    pub covered_s: f64,
    pub idle_w: Option<f64>,
    pub below_average_pct: Option<f64>,
    pub eta_s: Option<f64>,
}

#[derive(Default)]
pub struct Analytics {
    pub summary: Option<Summary>,
    last_attempt: Option<f64>,
    revision: Option<f64>,
    anchor: Option<Sample>,
}

impl Analytics {
    pub fn reset(&mut self) {
        self.summary = None;
        self.revision = None;
        self.anchor = None;
    }

    pub fn observe(&mut self, sample: &Sample) {
        if sample.gap
            || self
                .anchor
                .as_ref()
                .is_some_and(|anchor| !compatible(anchor, sample))
        {
            self.summary = None;
        }
    }

    pub fn refresh(&mut self, history: &History, now: f64, visible: bool) -> bool {
        let revision = history.samples.back().map(|sample| sample.elapsed_s);
        if !visible
            || revision == self.revision
            || self.last_attempt.is_some_and(|last| now - last < 30.0)
        {
            return false;
        }
        self.last_attempt = Some(now);
        self.revision = revision;
        self.summary = Some(calculate(history));
        self.anchor = history.samples.back().cloned();
        true
    }
}

fn consumption(sample: &Sample) -> Option<f64> {
    if !sample.available || sample.transition {
        return None;
    }
    sample
        .watts
        .filter(|watts| watts.is_finite())
        .and_then(|watts| {
            if sample.is_system_power() && watts >= 0.0 {
                Some(watts)
            } else if sample.state == State::Discharging && watts < 0.0 {
                Some(-watts)
            } else {
                None
            }
        })
}

pub fn compatible(previous: &Sample, current: &Sample) -> bool {
    consumption(current).is_some()
        && consumption(previous).is_some()
        && previous.topology_epoch == current.topology_epoch
        && previous.source == current.source
        && previous.measurement_kind == current.measurement_kind
        && previous.state == current.state
        && previous.ac_online == current.ac_online
}

fn calculate(history: &History) -> Summary {
    let Some(latest) = history.samples.back() else {
        return Summary::default();
    };
    let mut summary = Summary {
        observed_s: latest.elapsed_s,
        ..Summary::default()
    };
    if consumption(latest).is_none() {
        return summary;
    }
    let mut readings = Vec::new();
    let mut newer: Option<&Sample> = None;
    for sample in history.samples.iter().rev() {
        if consumption(sample).is_none()
            || newer.is_some_and(|next| {
                next.gap || !compatible(sample, next) || sample.elapsed_s >= next.elapsed_s
            })
        {
            break;
        }
        readings.push(sample);
        newer = Some(sample);
    }
    readings.reverse();
    summary.count = readings.len();
    summary.covered_s = readings
        .first()
        .map_or(0.0, |first| latest.elapsed_s - first.elapsed_s);
    if summary.count < 10 || summary.covered_s < 60.0 {
        return summary;
    }
    let mean = readings
        .iter()
        .filter_map(|sample| consumption(sample))
        .sum::<f64>()
        / summary.count as f64;
    summary.below_average_pct = Some(
        100.0
            * readings
                .iter()
                .filter(|sample| consumption(sample).is_some_and(|watts| watts < mean))
                .count() as f64
            / summary.count as f64,
    );
    let mut histogram = std::collections::BTreeMap::<u64, usize>::new();
    for sample in &readings {
        let rounded = consumption(sample).unwrap().round();
        *histogram.entry(rounded.to_bits()).or_default() += 1;
    }
    let peak = histogram.values().copied().max().unwrap_or(0);
    if histogram.values().filter(|count| **count == peak).count() == 1 {
        let mode = f64::from_bits(
            *histogram
                .iter()
                .find(|(_, count)| **count == peak)
                .unwrap()
                .0,
        );
        let (sum, count) = readings
            .iter()
            .filter_map(|sample| consumption(sample))
            .filter(|watts| (*watts - mode).abs() <= 1.0)
            .fold((0.0, 0usize), |(sum, count), watts| {
                (sum + watts, count + 1)
            });
        let estimate = sum / count as f64;
        summary.idle_w = (estimate.is_finite() && estimate < mean).then_some(estimate);
    }
    let mut energy_ws = 0.0;
    for pair in readings.windows(2) {
        let duration = pair[1].elapsed_s - pair[0].elapsed_s;
        let start = consumption(pair[0]).unwrap();
        let end = consumption(pair[1]).unwrap();
        energy_ws += (start + end) * 0.5 * duration;
    }
    let average = energy_ws / summary.covered_s;
    if !latest.is_system_power() && average > 0.0 {
        summary.eta_s = latest
            .remaining_wh
            .filter(|energy| energy.is_finite() && *energy >= 0.0)
            .map(|energy| energy / average * 3600.0)
            .filter(|seconds| seconds.is_finite() && *seconds < u32::MAX as f64);
    }
    summary
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn history() -> History {
        let mut history = History::new(
            Duration::from_secs(300),
            1000,
            Duration::from_secs(5),
            false,
        );
        for index in 0..=12 {
            let mut sample = Sample::empty(index as f64 * 5.0);
            sample.available = true;
            sample.state = State::Discharging;
            sample.watts = Some(-10.0);
            sample.remaining_wh = Some(20.0);
            history.push(sample);
        }
        history
    }

    #[test]
    fn constant_trace_and_throttle() {
        let mut history = history();
        let mut analytics = Analytics::default();
        assert!(!analytics.refresh(&history, 60.0, false));
        assert!(analytics.refresh(&history, 60.0, true));
        let summary = analytics.summary.as_ref().unwrap();
        assert_eq!(summary.idle_w, None);
        assert_eq!(summary.below_average_pct, Some(0.0));
        assert_eq!(summary.eta_s, Some(7200.0));
        assert!(!analytics.refresh(&history, 90.0, true));
        let mut sample = history.samples.back().unwrap().clone();
        sample.elapsed_s = 65.0;
        history.push(sample);
        assert!(!analytics.refresh(&history, 89.0, true));
        assert!(analytics.refresh(&history, 90.0, true));
        analytics.reset();
        assert!(analytics.summary.is_none());
        assert!(!analytics.refresh(&history, 91.0, true));
    }

    #[test]
    fn gaps_capacity_and_invalid_values() {
        let mut history = history();
        history.samples.back_mut().unwrap().remaining_wh = None;
        assert_eq!(calculate(&history).eta_s, None);
        history.samples.back_mut().unwrap().gap = true;
        assert_eq!(calculate(&history).below_average_pct, None);
        history.samples.back_mut().unwrap().watts = Some(f64::NAN);
        assert_eq!(calculate(&history).count, 0);
    }

    #[test]
    fn rounded_mode_original_neighbours_and_burst_rejection() {
        let estimate = |values: &[f64]| {
            let mut history = history();
            for (sample, watts) in history.samples.iter_mut().zip(values) {
                sample.watts = Some(-watts);
            }
            calculate(&history).idle_w
        };
        let values = [
            4.0, 4.49, 4.5, 4.8, 4.9, 5.0, 5.1, 5.2, 5.49, 5.5, 6.0, 6.01, 25.0,
        ];
        assert!(
            (estimate(&values).unwrap() - values[..11].iter().sum::<f64>() / 11.0).abs() < 1e-9
        );
        assert_eq!(
            estimate(&[
                5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 20.0
            ]),
            None
        );
        assert_eq!(
            estimate(&[
                20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 5.0, 5.0, 5.0, 5.0, 5.0
            ]),
            None
        );
        assert_eq!(
            estimate(&[
                5.0, 25.0, 5.0, 25.0, 5.0, 25.0, 5.0, 25.0, 5.0, 25.0, 5.0, 25.0, 5.0
            ]),
            Some(5.0)
        );
    }

    #[test]
    fn distribution_weighting_and_invalidation() {
        let mut history = history();
        for (index, sample) in history.samples.iter_mut().enumerate() {
            sample.watts = Some(if index < 10 { -10.0 } else { -20.0 });
        }
        let summary = calculate(&history);
        assert!((summary.below_average_pct.unwrap() - 1000.0 / 13.0).abs() < 1e-9);
        assert_eq!(summary.idle_w, Some(10.0));
        assert!((summary.eta_s.unwrap() - 72000.0 / (725.0 / 60.0)).abs() < 1e-9);
        let mut analytics = Analytics::default();
        analytics.refresh(&history, 60.0, true);
        let mut changed = history.samples.back().unwrap().clone();
        changed.topology_epoch += 1;
        analytics.observe(&changed);
        assert!(analytics.summary.is_none());
        assert!(!analytics.refresh(&history, 61.0, true));
        for sample in &mut history.samples {
            sample.measurement_kind = "system_input_power_user_confirmed".into();
            sample.watts = sample.watts.map(f64::abs);
        }
        assert_eq!(calculate(&history).eta_s, None);
        assert!(calculate(&history).idle_w.is_some());
    }

    #[test]
    fn irregular_polling_and_state_changes() {
        let mut history = history();
        for (index, sample) in history.samples.iter_mut().enumerate() {
            sample.elapsed_s = if index == 12 {
                65.0
            } else {
                index as f64 * 5.0
            };
            sample.watts = Some(if index == 12 { -20.0 } else { -10.0 });
        }
        let summary = calculate(&history);
        assert_eq!(summary.covered_s, 65.0);
        assert!((summary.eta_s.unwrap() - 72000.0 / (700.0 / 65.0)).abs() < 1e-9);
        let mut analytics = Analytics::default();
        analytics.refresh(&history, 65.0, true);
        let mut sample = history.samples.back().unwrap().clone();
        sample.gap = true;
        analytics.observe(&sample);
        assert!(analytics.summary.is_none());
        sample.state = State::Charging;
        history.samples.push_back(sample);
        assert_eq!(calculate(&history).count, 0);
    }

    #[test]
    #[ignore = "manual analytics cost measurement"]
    fn analytics_cost() {
        let mut history = History::new(
            Duration::from_secs(125000),
            500000,
            Duration::from_millis(250),
            false,
        );
        let mut sample = Sample::empty(0.0);
        sample.available = true;
        sample.state = State::Discharging;
        sample.watts = Some(-10.0);
        for index in 0..500000 {
            sample.elapsed_s = index as f64 * 0.25;
            history.push(sample.clone());
        }
        let start = std::time::Instant::now();
        let summary = calculate(&history);
        eprintln!(
            "500000-reading analytics calculation: {:?}",
            start.elapsed()
        );
        assert_eq!(summary.count, 500000);
    }
}
