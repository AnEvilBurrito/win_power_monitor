//! Distribution of the power readings currently held in memory.
//!
//! The line chart answers "what happened when"; this module answers "where does the
//! time go", which is the same samples counted into watt bins. Charging is excluded
//! on purpose: its wattage is a constant while the pack fills, so it would add a
//! spike and no information.
use crate::{
    analytics::consumption,
    model::{History, Sample},
};

/// Below this many readings the shape is noise, so the caller keeps the line chart.
pub const MIN_SAMPLES: usize = 8;
/// Fewer bins hide structure; more than this turn single samples into full bars.
const MIN_BINS: usize = 6;
const MAX_BINS: usize = 14;
/// A log axis only earns its complexity once the support spans a decade. Real
/// battery sessions span two or three times at most, and compressing those into a
/// few columns costs more than it explains.
const LOG_SUPPORT_RATIO: f64 = 10.0;
/// Meters report in fixed increments; bins must be multiples of that increment or
/// the histogram becomes a comb of one-value spikes.
const QUANTUM_DECIMALS: u32 = 3;
/// Readable percentage ceilings for the value axis.
const NICE_PERCENT: [f64; 11] = [
    5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 75.0, 100.0,
];

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Scale {
    #[default]
    Linear,
    Log,
}

/// Readings that entered the distribution, plus the count the filters rejected so
/// the header can be honest about `n`.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Population {
    pub values: Vec<f64>,
    pub excluded: usize,
}

impl Population {
    /// Every in-memory reading that carries discharge or whole-system input power.
    /// Readings from a different meter identity are excluded rather than mixed: a
    /// battery flow and a verified system input are not the same quantity.
    pub fn collect(history: &History) -> Self {
        let Some(reference) = history.samples.back() else {
            return Self::default();
        };
        let mut population = Self::default();
        for sample in &history.samples {
            match eligible(sample, reference) {
                Some(watts) => population.values.push(watts),
                None => population.excluded += 1,
            }
        }
        population
    }
    pub fn len(&self) -> usize {
        self.values.len()
    }
    pub fn is_empty(&self) -> bool {
        self.values.is_empty()
    }
}

fn eligible(sample: &Sample, reference: &Sample) -> Option<f64> {
    if sample.gap
        || sample.source != reference.source
        || sample.measurement_kind != reference.measurement_kind
        || sample.topology_epoch != reference.topology_epoch
    {
        return None;
    }
    consumption(sample)
}

/// Percentages per watt bin, ready to draw.
#[derive(Clone, Debug, PartialEq)]
pub struct Bins {
    pub edges: Vec<f64>,
    pub counts: Vec<usize>,
    pub scale: Scale,
    pub n: usize,
    /// Readable ceiling for the percentage axis.
    pub top_pct: f64,
}

impl Bins {
    /// Count of one bin as a percentage of every counted sample.
    pub fn percent(&self, index: usize) -> f64 {
        100.0 * self.counts[index] as f64 / self.n as f64
    }
    /// Largest percentage actually present, for axis ceilings and tests.
    pub fn peak_pct(&self) -> f64 {
        (0..self.counts.len())
            .map(|i| self.percent(i))
            .fold(0.0, f64::max)
    }
    /// Tick text for a lower bin edge, with the precision the step actually needs.
    pub fn label(&self, index: usize) -> String {
        trim_zeros(format!(
            "{:.decimals$}",
            self.edges[index],
            decimals = decimals(self.step())
        ))
    }
    /// Bin width as text: floating-point widths like 0.15000000000000036 must not
    /// reach the header.
    pub fn step_label(&self) -> String {
        match self.scale {
            Scale::Linear => trim_zeros(format!(
                "{:.decimals$}",
                self.step(),
                decimals = decimals(self.step())
            )),
            Scale::Log => "log-spaced".to_string(),
        }
    }
    fn step(&self) -> f64 {
        self.edges[1] - self.edges[0]
    }
    pub fn bins(&self) -> usize {
        self.counts.len()
    }
}

/// Build the distribution, or report that there is not enough data yet.
pub fn build(population: &Population) -> Option<Bins> {
    if population.len() < MIN_SAMPLES {
        return None;
    }
    let mut values = population.values.clone();
    values.retain(|value| value.is_finite() && *value > 0.0);
    if values.len() < MIN_SAMPLES {
        return None;
    }
    let n = values.len();
    values.sort_by(f64::total_cmp);
    let quantum = quantum(&values);
    let low = values[0];
    let high = values[n - 1];
    let (edges, scale) = if low > 0.0 && high / low >= LOG_SUPPORT_RATIO {
        log_edges(low, high)
    } else {
        linear_edges(&values, low, high, quantum)
    };
    let counts = count(&values, &edges, scale);
    let peak = (0..counts.len())
        .map(|i| 100.0 * counts[i] as f64 / n as f64)
        .fold(0.0, f64::max);
    let top_pct = NICE_PERCENT
        .iter()
        .copied()
        .find(|nice| *nice >= peak)
        .unwrap_or(100.0);
    Some(Bins {
        edges,
        counts,
        scale,
        n,
        top_pct,
    })
}

fn count(values: &[f64], edges: &[f64], scale: Scale) -> Vec<usize> {
    let mut counts = vec![0; edges.len() - 1];
    for &value in values {
        let last = counts.len() - 1;
        let index = match scale {
            Scale::Log => {
                let step = (edges[1] / edges[0]).ln();
                ((value / edges[0]).ln() / step).floor()
            }
            Scale::Linear => {
                let step = edges[1] - edges[0];
                ((value - edges[0]) / step).floor()
            }
        };
        if index.is_finite() {
            counts[(index as isize).clamp(0, last as isize) as usize] += 1;
        }
    }
    counts
}

/// Freedman–Diaconis width, rounded to a readable step and snapped to the meter's
/// increment. The start edge is rounded down so the first bar is never half-empty.
fn linear_edges(values: &[f64], low: f64, high: f64, quantum: f64) -> (Vec<f64>, Scale) {
    let n = values.len() as f64;
    let iqr = quantile(values, 0.75) - quantile(values, 0.25);
    let freedman = 2.0 * iqr * n.powf(-1.0 / 3.0);
    let fallback = (high - low) / 9.0;
    // Ignore a degenerate IQR (a run of repeats gives zero) rather than dividing by it.
    let target = if freedman.is_finite() && freedman > 0.0 {
        freedman
    } else {
        fallback
    };
    let mut step = nice_step(target.max(quantum));
    // Walk the step ladder until the bin count is readable: coarser when single
    // samples would fill whole bars, finer when the data would sit in two columns.
    for _ in 0..16 {
        let bins = bin_count(low, high, step);
        if bins > MAX_BINS {
            step = rescale(step, 1);
        } else if bins < MIN_BINS {
            let finer = rescale(step, -1);
            if finer + 1e-9 < quantum {
                break;
            }
            step = finer;
        } else {
            break;
        }
    }
    if step < quantum {
        step = quantum;
    } else {
        // A meter that reports in 0.1 W steps cannot have 0.25 W bins without
        // leaving some of them permanently empty.
        step = (step / quantum).round() * quantum;
        if step <= 0.0 {
            step = quantum;
        }
    }
    let start = (low / step).floor() * step;
    let bins = bin_count(start, high, step).clamp(MIN_BINS, MAX_BINS);
    (edges_from(start, step, bins), Scale::Linear)
}

fn bin_count(start: f64, high: f64, step: f64) -> usize {
    if step <= 0.0 || !step.is_finite() {
        return MIN_BINS;
    }
    (((high - start) / step).ceil() as usize).max(1)
}

fn log_edges(low: f64, high: f64) -> (Vec<f64>, Scale) {
    let span = high.log10() - low.log10();
    let ladder = [0.05, 0.1, 0.2, 0.25, 0.5, 1.0];
    let step = ladder
        .iter()
        .copied()
        .find(|step| (span / step).ceil() <= MAX_BINS as f64)
        .unwrap_or(1.0);
    let start = (low.log10() / step).floor() * step;
    let mut bins = (span / step).ceil() as usize;
    bins = bins.clamp(MIN_BINS, MAX_BINS);
    let edges = (0..=bins)
        .map(|i| 10f64.powf(start + step * i as f64))
        .collect();
    (edges, Scale::Log)
}

fn edges_from(start: f64, step: f64, bins: usize) -> Vec<f64> {
    (0..=bins).map(|i| start + step * i as f64).collect()
}

/// Readable steps: 1, 1.5, 2, 2.5, 3, 4, 5, 7.5, ×10^k. The 7.5 entry keeps a
/// Freedman–Diaconis width like 0.68 W at 0.75 W instead of jumping to 1 W and
/// flattening the distribution into seven columns.
const STEP_LADDER: [f64; 9] = [1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 7.5, 10.0];

/// Smallest ladder step at or above the target.
fn nice_step(target: f64) -> f64 {
    if !target.is_finite() || target <= 0.0 {
        return 1.0;
    }
    let decade = 10f64.powf(target.log10().floor());
    let normalized = target / decade;
    let factor = STEP_LADDER
        .iter()
        .copied()
        .find(|factor| normalized <= *factor + 1e-9)
        .unwrap_or(10.0);
    factor * decade
}

/// Move one ladder entry up (direction 1) or down (-1), crossing decades.
fn rescale(step: f64, direction: i32) -> f64 {
    let decade = 10f64.powf(step.log10().floor());
    let normalized = step / decade;
    let index = STEP_LADDER
        .iter()
        .position(|entry| (entry - normalized).abs() < 0.01 * entry)
        .unwrap_or(0);
    let next = index as i32 + direction;
    if next >= STEP_LADDER.len() as i32 {
        1.0 * decade * 10.0
    } else if next < 0 {
        7.5 * decade / 10.0
    } else {
        STEP_LADDER[next as usize] * decade
    }
}

/// Decimals a step needs to print exactly: 0.75 needs two, 2.5 one, 3 none.
fn decimals(step: f64) -> usize {
    let mut decimals = 0usize;
    while decimals < 4 {
        let scaled = step * 10f64.powi(decimals as i32);
        if (scaled - scaled.round()).abs() < 1e-6 {
            break;
        }
        decimals += 1;
    }
    decimals
}

/// 3.00 -> 3, 4.50 -> 4.5: keep the digits the step needs, drop the padding.
fn trim_zeros(text: String) -> String {
    if text.contains('.') {
        text.trim_end_matches('0').trim_end_matches('.').to_string()
    } else {
        text
    }
}

fn quantile(sorted: &[f64], q: f64) -> f64 {
    let position = q * (sorted.len() - 1) as f64;
    let lower = position.floor() as usize;
    let upper = position.ceil() as usize;
    let weight = position - lower as f64;
    sorted[lower] * (1.0 - weight) + sorted[upper] * weight
}

/// Smallest increment the meter actually resolves, inferred from adjacent readings.
fn quantum(values: &[f64]) -> f64 {
    let scale = 10f64.powi(QUANTUM_DECIMALS as i32);
    let mut gcd = 0u64;
    for pair in values.windows(2) {
        let delta = ((pair[1] - pair[0]) * scale).round();
        if delta >= 1.0 {
            let delta = delta as u64;
            gcd = if gcd == 0 {
                delta
            } else {
                greatest_common_divisor(gcd, delta)
            };
        }
    }
    if gcd == 0 {
        return 1.0 / scale;
    }
    gcd as f64 / scale
}

fn greatest_common_divisor(a: u64, b: u64) -> u64 {
    if b == 0 {
        a
    } else {
        greatest_common_divisor(b, a % b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::State;

    fn sample(elapsed_s: f64, watts: f64, state: State) -> Sample {
        let mut sample = Sample::empty(elapsed_s);
        sample.available = true;
        sample.state = state;
        sample.watts = Some(watts);
        sample
    }

    /// The 60 discharge readings from a real five-minute session (5 s polling, one
    /// battery): right-skewed, spanning 2.5x, with a one-sample-per-bin tail.
    const SESSION: [f64; 60] = [
        3.728, 3.743, 3.821, 3.840, 3.841, 3.841, 3.889, 3.987, 4.035, 4.049, 4.052, 4.066, 4.084,
        4.100, 4.112, 4.149, 4.164, 4.225, 4.259, 4.324, 4.370, 4.403, 4.498, 4.627, 4.664, 4.743,
        4.793, 4.803, 4.821, 4.821, 4.883, 4.890, 4.966, 4.997, 5.044, 5.095, 5.141, 5.175, 5.189,
        5.189, 5.190, 5.256, 5.306, 5.335, 5.478, 5.496, 5.561, 5.657, 5.747, 6.027, 6.223, 6.318,
        6.492, 6.595, 6.810, 6.859, 6.979, 7.848, 8.089, 9.292,
    ];

    fn session() -> History {
        // A long span keeps the fixture intact whatever elapsed offsets the tests add.
        let mut history = History::new(
            Duration::from_secs(3600),
            1000,
            Duration::from_secs(5),
            false,
        );
        for (index, value) in SESSION.iter().enumerate() {
            history.push(sample(index as f64 * 5.0, -value, State::Discharging));
        }
        history
    }

    use std::time::Duration;

    #[test]
    fn percentages_divide_by_every_counted_sample() {
        let bins = build(&Population::collect(&session())).expect("distribution");
        assert_eq!(bins.n, 60);
        let total: f64 = (0..bins.bins()).map(|i| bins.percent(i)).sum();
        assert!(
            (total - 100.0).abs() < 1e-9,
            "percentages summed to {total}"
        );
        assert_eq!(bins.counts.iter().sum::<usize>(), 60);
        assert!((bins.percent(0) - 100.0 * bins.counts[0] as f64 / 60.0).abs() < 1e-9);
        assert_eq!(
            bins.top_pct, 40.0,
            "peak is 35%, so the axis tops out at 40"
        );
        assert!(bins.peak_pct() <= bins.top_pct);
        assert!((bins.peak_pct() - 35.0).abs() < 1e-9);
    }

    /// Pins the real session: 0.75 W bins from 3.0 W, and the sparse tail really does
    /// leave an empty bin. That hole is the data, not a rendering bug.
    #[test]
    fn the_real_session_lands_on_readable_bins() {
        let bins = build(&Population::collect(&session())).expect("distribution");
        assert_eq!(bins.scale, Scale::Linear);
        assert_eq!(bins.bins(), 9);
        assert_eq!(bins.step(), 0.75);
        assert_eq!(bins.edges[0], 3.0);
        assert_eq!(bins.counts, vec![2, 21, 18, 8, 5, 3, 2, 0, 1]);
        assert_eq!(bins.label(0), "3");
        assert_eq!(bins.label(1), "3.75");
        assert_eq!(bins.label(2), "4.5");
    }

    #[test]
    fn a_two_and_a_half_times_spread_stays_linear() {
        let bins = build(&Population::collect(&session())).expect("distribution");
        assert_eq!(
            bins.scale,
            Scale::Linear,
            "0.4 decades cannot justify a log axis"
        );
        assert!(bins.edges[0] <= 3.73 && *bins.edges.last().unwrap() >= 9.29);
        for edge in &bins.edges {
            assert!(edge.is_finite());
        }
    }

    #[test]
    fn bins_are_few_enough_to_read_and_edges_are_evenly_spaced() {
        let bins = build(&Population::collect(&session())).expect("distribution");
        assert!(
            (MIN_BINS..=MAX_BINS).contains(&bins.bins()),
            "{} bins",
            bins.bins()
        );
        let step = bins.step();
        assert!(step > 0.0);
        for pair in bins.edges.windows(2) {
            assert!((pair[1] - pair[0] - step).abs() < 1e-9, "uneven edges");
        }
        assert_eq!(bins.counts.iter().sum::<usize>(), bins.n);
    }

    #[test]
    fn a_log_axis_needs_a_decade_of_support() {
        let mut history = History::new(
            Duration::from_secs(300),
            1000,
            Duration::from_secs(5),
            false,
        );
        for index in 0..60 {
            let watts = 2.0 * 1.08f64.powi(index);
            history.push(sample(index as f64 * 5.0, -watts, State::Discharging));
        }
        let bins = build(&Population::collect(&history)).expect("distribution");
        assert_eq!(bins.scale, Scale::Log, "2 W to 190 W spans two decades");
        assert!(
            bins.edges[1] / bins.edges[0] > 1.0
                && (bins.edges[1] / bins.edges[0] - bins.edges[2] / bins.edges[1]).abs() < 1e-6,
            "log edges must share one ratio"
        );
        assert_eq!(bins.counts.iter().sum::<usize>(), bins.n);
    }

    #[test]
    fn charging_readings_are_excluded_rather_than_binned() {
        let mut history = session();
        for (index, watts) in [12.0, 12.1, 12.2].iter().enumerate() {
            history.push(sample(300.0 + index as f64 * 5.0, *watts, State::Charging));
        }
        let population = Population::collect(&history);
        assert_eq!(
            population.len(),
            60,
            "charging watts must not enter the bins"
        );
        assert_eq!(population.excluded, 3);
        assert!(
            population.values.iter().all(|w| *w < 10.0),
            "no charging magnitude leaked into the population"
        );
    }

    #[test]
    fn gaps_are_excluded_and_a_meter_change_starts_a_new_population() {
        let mut gapped_history = session();
        let mut gapped = sample(300.0, -5.0, State::Discharging);
        gapped.gap = true;
        gapped_history.push(gapped);
        let population = Population::collect(&gapped_history);
        assert_eq!(population.len(), 60, "a gap sample is not a measurement");
        assert_eq!(population.excluded, 1);

        // Reading flow from one meter and whole-system input from another would mix
        // two different quantities, so the newest identity defines the population.
        let mut switched = session();
        let mut foreign = sample(300.0, -5.0, State::Discharging);
        foreign.source = "native:pdh_system_power".into();
        switched.push(foreign);
        let population = Population::collect(&switched);
        assert_eq!(population.len(), 1, "only the current meter is counted");
        assert_eq!(population.excluded, 60);
    }

    #[test]
    fn coarse_meters_force_steps_that_are_multiples_of_their_increment() {
        let mut history = History::new(
            Duration::from_secs(300),
            1000,
            Duration::from_secs(5),
            false,
        );
        for index in 0..60 {
            let watts = 10.0 + (index % 7) as f64 / 10.0;
            history.push(sample(index as f64 * 5.0, -watts, State::Discharging));
        }
        let bins = build(&Population::collect(&history)).expect("distribution");
        let step = bins.step();
        let ratio = step / 0.1;
        assert!(
            (ratio - ratio.round()).abs() < 1e-6,
            "step {step} is not a multiple of the 0.1 W increment"
        );
    }

    #[test]
    fn identical_readings_still_get_a_readable_axis() {
        let mut history = History::new(
            Duration::from_secs(300),
            1000,
            Duration::from_secs(5),
            false,
        );
        for index in 0..20 {
            history.push(sample(index as f64 * 5.0, -5.0, State::Discharging));
        }
        let bins = build(&Population::collect(&history)).expect("distribution");
        assert_eq!(bins.counts.iter().sum::<usize>(), 20);
        assert!(
            *bins.edges.last().unwrap() > 5.0 && bins.edges[0] <= 5.0,
            "a flat run still brackets its value: {:?}",
            bins.edges
        );
    }

    #[test]
    fn a_short_run_reports_no_distribution_yet() {
        let mut history = History::new(
            Duration::from_secs(300),
            1000,
            Duration::from_secs(5),
            false,
        );
        for index in 0..MIN_SAMPLES - 1 {
            history.push(sample(index as f64 * 5.0, -5.0, State::Discharging));
        }
        assert!(build(&Population::collect(&history)).is_none());
    }
}
