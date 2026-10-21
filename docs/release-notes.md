# Version 0.1.1 — checkpoint

**Experimental multi-battery aggregation.**

- Combined signed native battery flow with explicit Mixed state and per-battery diagnostics.
- Validated membership/device tags, bounded retries and unavailable transition samples; no partial totals or native/WMI mixing.
- Capacity-weighted percentage when metadata is complete; multi-battery voltage/current and mixed ETA remain unavailable.
- Topology epochs reset estimation and separate chart/energy intervals while preserving historical totals. Energy is net flow, not gross per-battery throughput.
- Additive JSON metadata; unchanged CSV columns with new Mixed state. Review device identifiers before sharing exports.
- Default five-second polling, frozen display behavior and desktop meter selection are preserved.

Sequential IOCTL calls are not atomic; synchronous driver calls cannot be cancelled. Physical attach/detach, suspend/resume and firmware behavior remain unverified. Version 0.1.1 records this checkpoint; existing tags/releases are untouched. Local validation ZIPs are not published releases.
