# RQ1 simulator determinism check.
#
# Re-runs the K8sClusterFromYamlExample simulator N times on a representative
# subset of RQ1 scenarios, parses each run's placement decisions, hashes the
# canonical placement dict, and reports whether all runs produce bit-identical
# placement output.
#
# Output: deployment/rq1/sim-determinism-hashes.txt
#
# Usage:
#   .\comparison-scripts\rq1_determinism_check.ps1
#   .\comparison-scripts\rq1_determinism_check.ps1 -Runs 5 -Sample 20

[CmdletBinding()]
param(
    [int] $Runs = 5,
    [int] $Sample = 10,
    [int] $Duration = 10
)

$ErrorActionPreference = 'Continue'

$root = Resolve-Path "$PSScriptRoot\.."
$scenarioDir = Join-Path $root "deployment\rq1\scenarios"
$outFile = Join-Path $root "deployment\rq1\sim-determinism-hashes.txt"
$workDir = Join-Path $root "deployment\rq1\determinism"
New-Item -ItemType Directory -Force -Path $workDir | Out-Null

# Deterministic scenario sample: take the first $Sample after sorting.
$scenarios = Get-ChildItem $scenarioDir -Filter "s*.yaml" | Sort-Object Name | Select-Object -First $Sample
Write-Host "Determinism check: $($scenarios.Count) scenarios x $Runs runs = $($scenarios.Count * $Runs) sim invocations" -ForegroundColor Cyan

"# RQ1 simulator determinism check" | Set-Content -Encoding utf8 $outFile
"# date            : $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')" | Add-Content -Encoding utf8 $outFile
"# runs            : $Runs" | Add-Content -Encoding utf8 $outFile
"# scenarios       : $($scenarios.Count) (first $Sample alphabetic)" | Add-Content -Encoding utf8 $outFile
"# duration_per    : $Duration seconds" | Add-Content -Encoding utf8 $outFile
"" | Add-Content -Encoding utf8 $outFile

$allHashes = @{}

foreach ($f in $scenarios) {
    $name = $f.BaseName
    $hashes = @()
    for ($r = 1; $r -le $Runs; $r++) {
        $log = Join-Path $workDir "sim-$name-run$r.log"
        if (-not (Test-Path $log) -or (Get-Item $log).Length -eq 0) {
            & "$root\deployment\run-scenario.ps1" -ScenarioPath $f.FullName -Duration $Duration *>&1 > $log
        }
        # Extract canonical placement dict via parse_sim, hash it.
        $py = @"
import hashlib, json, sys
from pathlib import Path
sys.path.insert(0, r'$($root.Path)\comparison-scripts')
from rq1_agreement import parse_sim
placements = parse_sim(Path(r'$log'))
canonical = json.dumps({k: list(v) for k, v in sorted(placements.items())}, sort_keys=True)
print(hashlib.sha256(canonical.encode('utf-8')).hexdigest())
print(canonical, file=sys.stderr)
"@
        $hash = (python -c $py 2>$null).Trim()
        if (-not $hash) { $hash = "<no-placements>" }
        $hashes += $hash
        Write-Host "  $name run $r : $hash"
    }
    $allEqual = ($hashes | Select-Object -Unique).Count -eq 1
    $marker = if ($allEqual) { "OK" } else { "DIFFERS" }
    "${name}: ${marker}  $($hashes -join '  ')" | Add-Content -Encoding utf8 $outFile
    $allHashes[$name] = $hashes
}

$totalDifferent = ($allHashes.Values | ForEach-Object {
    if (($_ | Select-Object -Unique).Count -ne 1) { 1 } else { 0 }
} | Measure-Object -Sum).Sum

"" | Add-Content -Encoding utf8 $outFile
"# summary: $($scenarios.Count - $totalDifferent)/$($scenarios.Count) scenarios produce bit-identical placements across $Runs runs" | Add-Content -Encoding utf8 $outFile

Write-Host ""
Write-Host "=== Determinism check complete ===" -ForegroundColor Cyan
Write-Host "  $($scenarios.Count - $totalDifferent)/$($scenarios.Count) scenarios bit-identical across $Runs runs"
Write-Host "  Output: $outFile"
