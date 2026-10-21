param(
    [int]$Samples = 30,
    [double]$IntervalSeconds = 2,
    [string]$OutputPath = '.\reference.csv'
)
$ErrorActionPreference = 'Stop'
if ($Samples -lt 1 -or $IntervalSeconds -le 0) { throw 'Samples and interval must be positive.' }
if (Test-Path $OutputPath) { throw 'Choose a new output file; existing data will not be overwritten.' }
for ($i = 0; $i -lt $Samples; $i++) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $batteries = @(Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus)
    $timer.Stop()
    $timestamp = [DateTime]::UtcNow.ToString('o')
    foreach ($battery in $batteries) {
        [pscustomobject]@{
            TimestampUtc = $timestamp
            Poll = $i + 1
            QueryMs = $timer.Elapsed.TotalMilliseconds
            InstanceName = $battery.InstanceName
            Active = $battery.Active
            Charging = $battery.Charging
            Discharging = $battery.Discharging
            ChargeRate_mW = $battery.ChargeRate
            DischargeRate_mW = $battery.DischargeRate
            Voltage_mV = $battery.Voltage
            RemainingCapacity_mWh = $battery.RemainingCapacity
        } | Export-Csv -Path $OutputPath -NoTypeInformation -Append
    }
    if ($i + 1 -lt $Samples) { Start-Sleep -Milliseconds ([int]($IntervalSeconds * 1000)) }
}
Write-Output "Reference captured: $OutputPath"
