# RQ1 real-cluster pod placement capture loop.
#
# Applies each generated manifest, waits for the pod to be Ready, captures the
# pod-to-node placement, then deletes the pod. Writes one CSV per scenario.
#
# Safe to interrupt and re-run: scenarios with an existing real-<name>.csv that
# is non-empty are skipped. Pods that fail to become Ready within the timeout
# are captured as "<podName>,<PENDING>" so the comparison script can still
# evaluate scheduling decisions (real K8s also reports unschedulable).
#
# Usage:
#   .\deployment\rq1\run-rq1-real.ps1
#   .\deployment\rq1\run-rq1-real.ps1 -TimeoutSec 90  # custom timeout

[CmdletBinding()]
param(
    [int] $TimeoutSec = 60,
    [string] $ManifestDir = ".\deployment\rq1\real-manifests",
    [string] $OutDir = ".\deployment\rq1"
)

$ErrorActionPreference = 'Continue'
$manifests = Get-ChildItem $ManifestDir -Filter "*.yaml" | Sort-Object Name
$total = $manifests.Count
$processed = 0
$pending = 0
$failed = 0
$skipped = 0

Write-Host "Processing $total RQ1 scenarios (timeout=${TimeoutSec}s)..." -ForegroundColor Cyan

foreach ($f in $manifests) {
    $name = $f.BaseName
    $csv = Join-Path $OutDir "real-$name.csv"
    $processed++

    # Skip if already captured
    if ((Test-Path $csv) -and ((Get-Item $csv).Length -gt 0)) {
        $skipped++
        Write-Host "[$processed/$total] $name : SKIP (already captured)" -ForegroundColor Gray
        continue
    }

    # Apply
    $apply = kubectl apply -f $f.FullName 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[$processed/$total] $name : APPLY FAILED - $apply" -ForegroundColor Red
        $failed++
        continue
    }

    # Wait for Ready (best-effort; do NOT fail the loop on timeout)
    kubectl wait --for=condition=Ready pod -l rq1-scenario=$name --timeout="${TimeoutSec}s" 2>&1 | Out-Null
    $ready = ($LASTEXITCODE -eq 0)

    # Capture placement regardless of Ready state
    try {
        $podJson = kubectl get pods -l rq1-scenario=$name -o json | ConvertFrom-Json
        $rows = $podJson.items | ForEach-Object {
            $node = $_.spec.nodeName
            if ([string]::IsNullOrEmpty($node)) { $node = "PENDING" }
            "$($_.metadata.name),$node"
        }
        $rows | Set-Content $csv

        if (-not $ready) {
            $pending++
            Write-Host "[$processed/$total] $name : PENDING (no node assigned)" -ForegroundColor Yellow
        } else {
            $nodeName = ($rows -split ',')[1]
            Write-Host "[$processed/$total] $name : OK -> $nodeName" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "[$processed/$total] $name : CAPTURE FAILED - $_" -ForegroundColor Red
        $failed++
    }

    # Clean up (always -- keeps cluster small)
    kubectl delete -f $f.FullName --wait=true --timeout="30s" 2>&1 | Out-Null
}

Write-Host ""
Write-Host "=== RQ1 capture done ===" -ForegroundColor Cyan
Write-Host "  Processed: $processed"
Write-Host "  Captured:  $($processed - $failed - $skipped)" -ForegroundColor Green
Write-Host "  Skipped:   $skipped (already had CSV)" -ForegroundColor Gray
Write-Host "  Pending:   $pending (captured as PENDING)" -ForegroundColor $(if ($pending -gt 0) { 'Yellow' } else { 'Green' })
Write-Host "  Failed:    $failed" -ForegroundColor $(if ($failed -gt 0) { 'Red' } else { 'Green' })
