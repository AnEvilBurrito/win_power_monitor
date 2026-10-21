# ---- Config ----
$IntervalSeconds = 2        # time between samples
$LogPath         = $null    # set to e.g. "C:\temp\power.csv" to also log to CSV

# ---- Running stats (kept manually so memory/CPU stay flat over long runs) ----
$sum = 0.0; $count = 0; $min = [double]::MaxValue; $max = [double]::MinValue

Write-Host "Sampling every $IntervalSeconds s. Press Ctrl+C to stop.`n" -ForegroundColor Cyan
Write-Host ("{0,-8} {1,-12} {2,9} {3,8} {4,9} {5,5}  {6,9} {7,9} {8,9}" -f `
            'Time','State','Power','Volts','Amps','Bat%','Avg','Min','Max') -ForegroundColor DarkGray

while ($true) {
    $b = Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus -ErrorAction SilentlyContinue |
         Select-Object -First 1

    if (-not $b) {
        Write-Warning "BatteryStatus not available (desktop, or the battery driver doesn't expose it)."
        Start-Sleep $IntervalSeconds
        continue
    }

    # ---- Derive watts + state ----
    if     ($b.Discharging) { $watts = -$b.DischargeRate / 1000; $state = 'Discharging' }  # negative = drawing down
    elseif ($b.Charging)    { $watts =  $b.ChargeRate    / 1000; $state = 'Charging'    }  # positive = filling up
    elseif ($b.PowerOnline) { $watts =  0.0;                     $state = 'AC / full'   }
    else                    { $watts =  0.0;                     $state = 'Idle'        }

    # ---- Volts / amps (mW / mV == A, no extra scaling needed) ----
    $volts = [math]::Round($b.Voltage / 1000, 2)
    $amps  = if ($volts -gt 0) { [math]::Round($watts / $volts, 3) } else { 0 }
    $watts = [math]::Round($watts, 2)
    $pct   = (Get-CimInstance -ClassName Win32_Battery -EA SilentlyContinue |
              Select-Object -First 1).EstimatedChargeRemaining

    # ---- Update running stats ----
    $sum += $watts; $count++
    if ($watts -lt $min) { $min = $watts }
    if ($watts -gt $max) { $max = $watts }
    $avg = $sum / $count

    # ---- Report ----
    $line = "{0,-8} {1,-12} {2,7:N2} W {3,6:N2} V {4,7:N3} A {5,4}%  {6,7:N2}  {7,7:N2}  {8,7:N2}" -f `
            (Get-Date -Format 'HH:mm:ss'), $state, $watts, $volts, $amps, $pct, $avg, $min, $max

    $color = switch ($state) { 'Discharging' { 'Yellow' } 'Charging' { 'Green' } default { 'Gray' } }
    Write-Host $line -ForegroundColor $color

    if ($LogPath) {
        [pscustomobject]@{
            Time = (Get-Date); State = $state; Watts = $watts
            Volts = $volts; Amps = $amps; BatteryPercent = $pct
        } | Export-Csv -Path $LogPath -NoTypeInformation -Append -Encoding UTF8
    }

    Start-Sleep -Seconds $IntervalSeconds
}