# RQ1 simulator run loop.
#
# Runs the K8sClusterFromYamlExample simulator against each scenario YAML in
# deployment/rq1/scenarios/ and writes the structured summary to
# deployment/rq1/sim-<name>.log so rq1_agreement.py can pair it with the real
# cluster placement CSV.
#
# Safe to interrupt and re-run: scenarios with an existing non-empty sim log
# are skipped.
#
# Files are written with `*>&1 >` so both stdout (the placement table) and
# stderr (SLF4J INFO lines) end up in the same file. rq1_agreement.py
# auto-detects the resulting UTF-16 BOM that PowerShell `>` produces.
#
# Usage:
#   .\deployment\rq1\run-rq1-sim.ps1
#   .\deployment\rq1\run-rq1-sim.ps1 -Duration 30

[CmdletBinding()]
param(
    [int] $Duration = 10,
    [string] $ScenarioDir = ".\deployment\rq1\scenarios",
    [string] $OutDir = ".\deployment\rq1"
)

$ErrorActionPreference = 'Continue'
$scenarios = Get-ChildItem $ScenarioDir -Filter "s*.yaml" | Sort-Object Name
$total = $scenarios.Count
$processed = 0
$skipped = 0
$failed = 0

Write-Host "Running $total RQ1 simulator scenarios (duration=${Duration}s each)..." -ForegroundColor Cyan

foreach ($f in $scenarios) {
    $name = $f.BaseName
    $log = Join-Path $OutDir "sim-$name.log"
    $processed++

    if ((Test-Path $log) -and ((Get-Item $log).Length -gt 0)) {
        $skipped++
        Write-Host "[$processed/$total] $name : SKIP (already have sim log)" -ForegroundColor Gray
        continue
    }

    # *>&1 captures stdout AND stderr; the placement table is on stdout and
    # the SLF4J INFO lines are on stderr -- we want both.
    .\deployment\run-scenario.ps1 -ScenarioPath $f.FullName -Duration $Duration *>&1 > $log

    if ((Test-Path $log) -and ((Get-Item $log).Length -gt 0)) {
        # Quick sanity: did we capture the placement table?
        if (Select-String -Path $log -Pattern "Pod.*node placement" -Quiet) {
            Write-Host "[$processed/$total] $name : OK" -ForegroundColor Green
        }
        else {
            Write-Host "[$processed/$total] $name : LOG WRITTEN but no placement table -- investigate" -ForegroundColor Yellow
        }
    }
    else {
        Write-Host "[$processed/$total] $name : FAILED (empty log)" -ForegroundColor Red
        $failed++
    }
}

Write-Host ""
Write-Host "=== RQ1 sim runs done ===" -ForegroundColor Cyan
Write-Host "  Processed: $processed"
Write-Host "  Generated: $($processed - $skipped - $failed)" -ForegroundColor Green
Write-Host "  Skipped:   $skipped (already had log)" -ForegroundColor Gray
Write-Host "  Failed:    $failed" -ForegroundColor $(if ($failed -gt 0) { 'Red' } else { 'Green' })

Write-Host ""
Write-Host "Next: python .\comparison-scripts\rq1_agreement.py .\deployment\rq1 > .\deployment\rq1\summary.csv"
