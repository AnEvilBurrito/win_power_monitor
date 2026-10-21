use crate::model::Sample;
use std::{
    fs::{File, OpenOptions},
    io::{self, BufWriter, Write},
    path::Path,
};
pub const HEADER: &str = "Time,State,Watts,Volts,Amps,BatteryPercent\n";
fn field(n: Option<f64>) -> String {
    n.map_or_else(String::new, |v| format!("{v:.4}"))
}
pub fn csv_row(s: &Sample) -> String {
    format!(
        "{},{:?},{},{},{},{}\n",
        s.ts,
        s.state,
        field(s.watts),
        field(s.volts),
        field(s.amps),
        field(s.pct)
    )
}
pub struct Log {
    writer: BufWriter<File>,
    pending: usize,
}
impl Log {
    pub fn open(path: &Path) -> io::Result<Self> {
        let file = OpenOptions::new().create(true).append(true).open(path)?;
        let empty = file.metadata()?.len() == 0;
        let mut writer = BufWriter::new(file);
        if empty {
            writer.write_all(HEADER.as_bytes())?;
            writer.flush()?;
        }
        Ok(Self { writer, pending: 0 })
    }
    pub fn write(&mut self, s: &Sample) -> io::Result<()> {
        self.writer.write_all(csv_row(s).as_bytes())?;
        self.pending += 1;
        if self.pending >= 10 {
            self.flush()?;
        }
        Ok(())
    }
    pub fn flush(&mut self) -> io::Result<()> {
        self.writer.flush()?;
        self.pending = 0;
        Ok(())
    }
}
impl Drop for Log {
    fn drop(&mut self) {
        let _ = self.flush();
    }
}
pub fn export(samples: &std::collections::VecDeque<Sample>) -> io::Result<String> {
    let base = format!(
        "wpm-export-{}-{}",
        chrono::Utc::now().format("%Y%m%dT%H%M%S%.3fZ"),
        std::process::id()
    );
    let mut csv = BufWriter::new(
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(format!("{base}.csv"))?,
    );
    let mut json = BufWriter::new(
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(format!("{base}.json"))?,
    );
    csv.write_all(HEADER.as_bytes())?;
    for s in samples {
        csv.write_all(csv_row(s).as_bytes())?;
    }
    serde_json::to_writer_pretty(&mut json, samples)?;
    csv.flush()?;
    json.flush()?;
    Ok(base)
}
