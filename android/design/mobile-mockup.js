/* Offline UI concept. Synthetic fixtures only: no device APIs, network, or polling. */
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const all = (selector) => [...document.querySelectorAll(selector)];
  const state = { recording: true, frozen: false, frozenScenario: null, scenario: "discharge", range: 30, policy: "low", cadence: 15, absolute: true, screen: "monitor" };
  const layoutHomes = new Map();
  const fixtures = {
    discharge: { watts: "4.80", percentage: 68, direction: "↓ Discharging", flowClass: "discharge", amps: "1.194", rawCurrent: "−1,194,000 µA", age: "2s", stamp: "09:41", message: "" },
    charge: { watts: "6.20", percentage: 68, direction: "↑ Charging", flowClass: "charge", amps: "1.542", rawCurrent: "+1,542,000 µA", age: "2s", stamp: "09:41", message: "Net flow into the battery. This is not charger output power." },
    low: { watts: "4.80", percentage: 18, direction: "↓ Discharging", flowClass: "discharge", amps: "1.194", rawCurrent: "−1,194,000 µA", age: "2s", stamp: "09:41", message: "" },
    stale: { watts: "—", percentage: null, direction: "No recent sample", flowClass: "", amps: "—", rawCurrent: "Unavailable", age: "2m 30s", stamp: "09:38", historical: true, message: "Current readings are unavailable. History is retained; the missing interval is not zero watts." },
    unsupported: { watts: "—", percentage: 68, direction: "Power unavailable", flowClass: "", amps: "—", rawCurrent: "Not available", age: "2s", stamp: "09:38", historical: true, message: "Current is missing from the latest sample. Percentage and voltage are available; past power readings are retained." },
  };

  // Hand-authored, decimated sketch; not a device trace or an integration fixture.
  // History stores signed power, just like the desktop model and CSV exports.
  const minuteValues = [3.2, 3.6, 4.1, 2.6, 1.2, 2.1, 3.3, 4.8, 3.8, 4.1, 5.3, 5, 4.4, 2.2, 3.7, 4.1, 3.8, 5.4, 7.8, 5.8, 4.4, 4.8, 3.6, 4.1, 4.4];
  const history = minuteValues.map((watts, minute) => [minute * 60, watts]);
  history.push([24.9 * 60, 4.2], [27.5 * 60, 3.8], [28 * 60, 4.4], [29 * 60, 4.7], [30 * 60, 4.4], [31 * 60, 3.9], [32 * 60, 4.2], [33 * 60, 5.8], [34 * 60, 4.3], [35 * 60, 4.8], [36.2 * 60, 3.1], [37 * 60, 5.7], [38 * 60, 4.9], [39 * 60, 4.5], [40 * 60, 4.72], [41 * 60, 4.8], [2466, 4.8]);
  const signedHistory = history.map(([seconds, watts]) => [seconds, -watts]);
  const sessionEnd = 2466;
  const gap = [1500, 1650];
  const rangeAverages = { 5: 4.62, 30: 4.20, 120: 4.35 };
  let pendingAction = null;
  let feedbackTimeout;

  function clock(seconds) {
    const total = 9 * 3600 + seconds;
    return `${String(Math.floor(total / 3600)).padStart(2, "0")}:${String(Math.floor((total % 3600) / 60)).padStart(2, "0")}`;
  }

  function signedReading(fixture, field) {
    if (fixture[field] === "—") return null;
    return Number(fixture[field]) * (fixture === fixtures.charge ? 1 : -1);
  }

  function displayNumber(value, digits = 2) {
    if (value === null || !Number.isFinite(value)) return "—";
    const magnitude = Math.abs(value).toFixed(digits);
    return state.absolute || value === 0 ? magnitude : `${value < 0 ? "−" : "+"}${magnitude}`;
  }

  function showFeedback(message) {
    $("preview-feedback").textContent = message;
    clearTimeout(feedbackTimeout);
    // One-shot feedback only. There are no repeating timers or live demo updates.
    feedbackTimeout = setTimeout(() => { $("preview-feedback").textContent = ""; }, 4500);
  }

  function applyOrientation() {
    const landscape = document.body.dataset.orientation === "landscape";
    // Share real controls/state between two purpose-built compositions. Comment
    // anchors restore the exact portrait DOM without duplicating IDs or polling.
    if (landscape) {
      const placements = [
        [".status-line", "landscape-status-slot"],
        [".reading-block", "landscape-reading-slot"],
        [".flow-line", "landscape-flow-slot"],
        ["#sample-age", "landscape-flow-slot"],
        [".window-stats", "landscape-stats-slot"],
        ["#sample-message", "landscape-message-slot"],
        [".chart-section", "landscape-chart-slot"],
        [".session-link", "landscape-session-slot"],
        ["#recording-hint", "landscape-mode-slot"],
      ];
      for (const [selector, slot] of placements) {
        const node = document.querySelector(selector);
        if (!layoutHomes.has(node)) {
          const home = document.createComment("portrait position");
          node.before(home);
          layoutHomes.set(node, home);
        }
        $(slot).append(node);
      }
    } else {
      for (const [node, home] of layoutHomes) home.after(node);
    }
    $("landscape-context").hidden = !landscape;
    $("landscape-status-slot").hidden = !landscape;
    openScreen(state.screen);
    render();
  }

  function openScreen(name) {
    state.screen = name;
    const landscape = document.body.dataset.orientation === "landscape";
    const target = name === "monitor" && landscape ? "screen-monitor-landscape" : `screen-${name}`;
    all(".screen").forEach((screen) => { screen.hidden = screen.id !== target; });
    $("landscape-live-strip").hidden = !landscape || name !== "monitor";
    all("[data-screen]").forEach((button) => {
      if (button.dataset.screen === name) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
    $("app-scroll").scrollTop = 0;
    if (name === "monitor") renderChart(fixtures[state.frozen ? state.frozenScenario : state.scenario]);
  }

  function renderChart(fixture) {
    const end = sessionEnd - (fixture.historical ? 150 : 0);
    const start = end - state.range * 60;
    const visible = signedHistory.filter(([seconds]) => seconds >= start && seconds <= end).map((sample) => [...sample]);
    const current = signedReading(fixture, "watts");
    if (!fixture.historical && current !== null) visible[visible.length - 1][1] = current;
    const display = visible.map(([seconds, watts]) => [seconds, state.absolute ? Math.abs(watts) : watts]);
    const values = display.map((sample) => sample[1]);
    const minimum = Math.min(...values);
    const maximum = Math.max(...values);
    // Always include zero. Mixed signed history needs both halves of the axis.
    const lower = !state.absolute && minimum < 0 ? -8 : 0;
    const upper = state.absolute || maximum > 0 ? 8 : 0;
    const landscape = document.body.dataset.orientation === "landscape";
    const box = $("power-chart").getBoundingClientRect();
    const width = landscape ? Math.max(336, Math.round(box.width)) : 336;
    const height = landscape ? Math.max(100, Math.round(box.height)) : 180;
    const left = landscape ? 32 : 30;
    const right = width - (landscape ? 12 : 10);
    const top = landscape ? 18 : 24;
    const bottom = height - (landscape ? 24 : 28);
    const middle = (top + bottom) / 2;
    $("power-chart").setAttribute("viewBox", `0 0 ${width} ${height}`);
    Object.assign($("power-chart").dataset, { plotLeft: left, plotRight: right, plotTop: top, plotBottom: bottom });
    $("chart-grid-lines").setAttribute("d", `M${left} ${top}H${right}M${left} ${middle}H${right}M${left} ${bottom}H${right}`);
    [["axis-top", top], ["axis-middle", middle], ["axis-bottom", bottom]].forEach(([id, position]) => $(id).setAttribute("y", position + 4));
    $("chart-start").setAttribute("x", left);
    $("chart-end").setAttribute("x", right);
    ["chart-start", "chart-end"].forEach((id) => $(id).setAttribute("y", height - 4));
    const x = (seconds) => left + ((seconds - start) / (end - start)) * (right - left);
    const y = (watts) => bottom - ((watts - lower) / (upper - lower)) * (bottom - top);
    const tick = (value) => value < 0 ? `−${Math.abs(value)}` : value > 0 && !state.absolute ? `+${value}` : String(value);
    $("axis-top").textContent = tick(upper);
    $("axis-middle").textContent = tick((upper + lower) / 2);
    $("axis-bottom").textContent = tick(lower);
    $("chart-zero").setAttribute("d", `M${left} ${y(0).toFixed(2)}H${right}`);
    $("power-chart").dataset.scale = state.absolute ? "absolute" : "signed";

    const segments = [];
    for (const sample of display) {
      const last = segments[segments.length - 1];
      if (!last || (last[last.length - 1][0] < gap[0] && sample[0] >= gap[1])) segments.push([sample]);
      else last.push(sample);
    }
    const gapVisible = gap[1] > start && gap[0] < end;
    const gapStart = Math.max(left, x(gap[0]));
    const gapWidth = Math.min(right, x(gap[1])) - gapStart;
    const gapMarkup = gapVisible ? `<rect class="chart-gap" x="${gapStart.toFixed(2)}" y="${top - 2}" width="${gapWidth.toFixed(2)}" height="${bottom - top + 4}"/><text class="chart-gap-label" x="${(gapStart + gapWidth / 2).toFixed(2)}" y="${top - 8}" text-anchor="middle">gap</text>` : "";
    const paths = segments.map((segment) => `<path class="chart-trace" d="${segment.map(([seconds, watts], index) => `${index ? "L" : "M"}${x(seconds).toFixed(2)},${y(watts).toFixed(2)}`).join(" ")}"/>`).join("");
    const last = display[display.length - 1];
    const point = last ? `<circle class="chart-point" cx="${x(last[0]).toFixed(2)}" cy="${y(last[1]).toFixed(2)}" r="2.7"/>` : "";
    // Only controlled fixture numbers are inserted into this SVG group.
    $("chart-data").innerHTML = gapMarkup + paths + point;
    $("chart-start").textContent = clock(start);
    $("chart-end").textContent = clock(end);
    const period = state.range === 120 ? "2h window · session started 09:00" : `Last ${state.range} minutes`;
    $("chart-caption").textContent = state.frozen ? "View frozen · recording continues" : `${fixture.historical || !state.recording ? "Historical · " : ""}${period}`;
    $("chart-caption").classList.toggle("frozen", state.frozen);
    $("gap-key").textContent = gapVisible ? "Blank region = no samples" : "No gaps in this window";
    $("chart-description").textContent = `${state.absolute ? "Absolute" : "Signed"} battery power in a ${state.range}-minute window ending ${clock(end)}. ${gapVisible ? "The two-minute thirty-second sleep gap is blank, with no line connecting it." : "No sleep gap in this window."} ${state.range === 120 ? "The empty area before 09:00 precedes recording." : ""}`;
    const stats = [state.absolute ? rangeAverages[state.range] : -rangeAverages[state.range], minimum, maximum];
    ["window-average", "window-min", "window-max"].forEach((id, index) => { $(id).innerHTML = `${displayNumber(stats[index])} <small>W</small>`; });
    all("[data-range]").forEach((button) => {
      button.setAttribute("aria-pressed", String(Number(button.dataset.range) === state.range));
      button.disabled = state.frozen;
    });
  }

  function render() {
    const live = fixtures[state.scenario];
    const view = fixtures[state.frozen ? state.frozenScenario : state.scenario];
    const available = state.recording;
    const low = available && view.percentage !== null && view.percentage <= 20;
    $("recording-status").textContent = available ? "● Recording" : "■ Recording stopped";
    $("sample-age").textContent = state.frozen ? `Frozen sample · ${view.stamp}` : available ? `Sample · ${view.age} ago` : "History retained";
    $("watts").textContent = available ? displayNumber(signedReading(view, "watts")) : "—";
    $("percentage").innerHTML = available && view.percentage !== null ? `${view.percentage}<span class="small-unit">%</span>` : "—";
    $("percentage").classList.toggle("low", low);
    $("battery-caption").classList.toggle("low", low);
    $("battery-caption").textContent = low ? "battery · low" : "battery";
    $("flow-direction").textContent = available ? view.direction : "Not recording";
    $("flow-direction").className = available ? view.flowClass : "";
    $("electrical").textContent = available && view !== fixtures.stale ? `4.02 V / ${displayNumber(signedReading(view, "amps"), 3)} A` : "— V / — A";
    $("sample-message").hidden = !available || !view.message;
    $("sample-message").textContent = view.message;
    $("sample-message").classList.toggle("attention", view === fixtures.stale);
    renderChart(view);
    $("scale-toggle").setAttribute("aria-checked", String(state.absolute));
    $("absolute-setting").checked = state.absolute;
    $("session-average-label").textContent = state.absolute ? "Average magnitude" : "Average signed power";
    $("session-average").textContent = `${displayNumber(-4.35)} W`;
    $("session-extrema").textContent = state.absolute ? "1.20 / 7.80 W" : view === fixtures.charge ? "−7.80 / +6.20 W" : "−7.80 / −1.20 W";

    const high = state.policy === "high";
    $("mode-summary").textContent = high ? (available ? "High resolution · CPU kept awake" : "High resolution selected · stopped") : "Low impact · no wake lock";
    $("mode-detail").textContent = !available ? "Recording stopped. No wake lock is held." : high ? `${state.cadence}s interval. Uses more battery.` : `${state.cadence}s interval while awake. Sleep gaps are marked.`;
    $("freeze-button").disabled = !available;
    $("freeze-button").setAttribute("aria-pressed", String(state.frozen));
    $("freeze-button").querySelector("span").textContent = state.frozen ? "Resume view" : "Freeze view";
    $("record-button").classList.toggle("start", !available);
    $("record-button").querySelector("span").textContent = available ? "Stop recording" : "Start recording";
    $("record-button").querySelector("use").setAttribute("href", available ? "#i-stop" : "#i-play");
    $("recording-hint").textContent = document.body.dataset.orientation === "landscape"
      ? (available ? `${high ? "CPU awake" : "Low impact"} · ${state.cadence}s interval\nRecording in background` : "Recording stopped\nHistory saved")
      : (available ? `${high ? "High resolution · CPU kept awake" : `Low impact · ${state.cadence}s interval while awake`}\nContinues when you leave the app` : "Recording stopped · history saved");
    $("session-status").textContent = available ? "Started 09:00 · recording in the background" : "09:00–09:41 · saved session";

    const fresh = available && live !== fixtures.stale;
    $("diagnostic-age").textContent = available ? live.age : "Session stopped";
    $("diagnostic-current").textContent = available ? live.rawCurrent : "—";
    $("diagnostic-voltage").textContent = fresh ? "4,020 mV" : "Unavailable";
    $("diagnostic-temperature").textContent = fresh ? "31.2 °C" : "Unavailable";
    $("diagnostic-condition").textContent = fresh ? "Good" : "Unavailable";
    $("diagnostic-wakelock").textContent = high && available ? "Held · enabled by you" : "Not held";
    $("diagnostic-cadence").textContent = high ? `${state.cadence}s interval` : `${state.cadence}s while awake`;
    all("input[name='policy']").forEach((radio) => { radio.checked = radio.value === state.policy; });

    $("notification-state").textContent = available ? "Recording" : "Stopped";
    $("notification-title").textContent = available ? (high ? "High-resolution monitoring" : "Low-impact monitoring") : "Session ended";
    $("notification-reading").textContent = !available ? "Recording stopped · history saved" : live.watts === "—" ? (live === fixtures.stale ? "No recent sample · last captured 09:38" : "Latest sample · current unavailable") : `Last sample ${live.stamp} · ${displayNumber(signedReading(live, "watts"))} W ${live === fixtures.charge ? "↑ charge" : "↓ discharge"}`;
    $("notification-stop").disabled = !available;
  }

  function confirmAction(title, description, actionLabel, action) {
    pendingAction = action;
    $("confirm-title").textContent = title;
    $("confirm-description").textContent = description;
    $("confirm-accept").textContent = actionLabel;
    $("confirm-dialog").showModal();
  }

  function stopRecording() {
    state.recording = false;
    state.frozen = false;
    render();
    showFeedback("Recording stopped. History saved.");
  }

  function requestStop() {
    if (!state.recording) return;
    confirmAction("Stop recording?", "The background session ends. Your recorded history stays available.", "Stop recording", stopRecording);
  }

  all("[data-screen]").forEach((button) => button.addEventListener("click", () => openScreen(button.dataset.screen)));
  all("[data-open-screen]").forEach((button) => button.addEventListener("click", () => openScreen(button.dataset.openScreen)));
  all("[data-range]").forEach((button) => button.addEventListener("click", () => {
    state.range = Number(button.dataset.range);
    render();
  }));
  $("orientation-select").addEventListener("change", (event) => {
    const orientation = event.target.value;
    document.body.dataset.orientation = orientation;
    $("device-caption").textContent = orientation === "landscape" ? "Landscape concept · synthetic data · scroll sideways on narrow screens" : "Portrait concept · synthetic data · no background work";
    applyOrientation();
  });
  $("theme-select").addEventListener("change", (event) => { document.body.dataset.theme = event.target.value; });
  $("scenario-select").addEventListener("change", (event) => { state.scenario = event.target.value; render(); });
  $("scale-toggle").addEventListener("click", () => { state.absolute = !state.absolute; render(); });
  $("absolute-setting").addEventListener("change", (event) => { state.absolute = event.target.checked; render(); });
  $("freeze-button").addEventListener("click", () => {
    state.frozen = !state.frozen;
    state.frozenScenario = state.frozen ? state.scenario : null;
    render();
  });
  $("settings-button").addEventListener("click", () => $("settings-dialog").showModal());
  all("[data-close-dialog]").forEach((button) => button.addEventListener("click", () => $(button.dataset.closeDialog).close()));
  $("cadence-select").addEventListener("change", (event) => { state.cadence = Number(event.target.value); render(); });
  all("input[name='policy']").forEach((radio) => radio.addEventListener("change", () => {
    if (radio.value === "high") {
      confirmAction("Keep the processor awake?", "This uses extra battery and may increase the idle drain being measured. The screen can stay off. You can stop recording at any time.", "Use high resolution", () => { state.policy = "high"; render(); });
    } else {
      state.policy = "low";
      render();
    }
  }));
  $("confirm-cancel").addEventListener("click", () => { pendingAction = null; $("confirm-dialog").close(); render(); });
  $("confirm-accept").addEventListener("click", () => {
    const action = pendingAction;
    pendingAction = null;
    $("confirm-dialog").close();
    if (action) action();
  });
  $("confirm-dialog").addEventListener("close", () => { pendingAction = null; render(); });
  $("record-button").addEventListener("click", () => {
    if (state.recording) requestStop();
    else {
      state.recording = true;
      render();
      showFeedback("Recording started.");
    }
  });
  // Notification Stop is direct: the real action must not require opening the app.
  $("notification-stop").addEventListener("click", stopRecording);
  $("notification-open").addEventListener("click", () => {
    openScreen("monitor");
    $("tab-monitor").focus({ preventScroll: true });
  });
  $("export-button").addEventListener("click", () => {
    const rows = [
      "synthetic,timestamp,record_kind,watts,volts,amps,percentage,reason",
      "true,2026-01-01T09:24:54Z,sample,-4.2,4.02,-1.0448,70,illustrative_fixture",
      "true,2026-01-01T09:25:00Z,gap_start,,,,,no_samples_during_sleep",
      "true,2026-01-01T09:27:30Z,sample,-3.8,4.02,-0.9453,69,sampling_resumed",
      "true,2026-01-01T09:41:06Z,sample,-4.8,4.02,-1.194,68,illustrative_fixture",
    ];
    const url = URL.createObjectURL(new Blob([rows.join("\r\n") + "\r\n"], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "wpm-android-SYNTHETIC-example.csv";
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    showFeedback("CSV exported.");
  });

  window.addEventListener("resize", () => {
    if (state.screen === "monitor" && document.body.dataset.orientation === "landscape") {
      renderChart(fixtures[state.frozen ? state.frozenScenario : state.scenario]);
    }
  });
  applyOrientation();
})();
