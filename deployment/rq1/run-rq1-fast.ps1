# Fast RQ1 sim run (patched build, taint-toleration bonus removed).
# Generalised from run-la-fast.ps1: takes a scenario dir and an output dir so
# both COST_OPTIMIZED (scenarios/) and LEAST_ALLOCATED (scenarios-la/) can be
# regenerated. One JVM per scenario via Spring Boot PropertiesLauncher.
#
# Usage:
#   .\deployment\rq1\run-rq1-fast.ps1 -ScenarioDir scenarios    -OutDir rq1-co-fresh
#   .\deployment\rq1\run-rq1-fast.ps1 -ScenarioDir scenarios-la -OutDir rq1-la-fresh
param(
    [string]$ScenarioDir = "scenarios",
    [string]$OutDir      = "rq1-co-fresh"
)
$root   = "C:\Users\Administrator\Documents\microservices-sim-poc"
Set-Location $root
$jar    = (Resolve-Path "$root\target\cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar").Path
$scDir  = (Resolve-Path "$root\deployment\rq1\$ScenarioDir").Path
$outDir = Join-Path "$root\deployment\rq1" $OutDir
if (Test-Path $outDir) { Remove-Item "$outDir\*.log" -Force -ErrorAction SilentlyContinue }
$outDir = (New-Item -ItemType Directory -Force -Path $outDir).FullName
$files = Get-ChildItem $scDir -Filter "s*.yaml" | Sort-Object Name
$n = $files.Count; $i = 0; $fail = 0
$sw = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($f in $files) {
    $i++
    $log = Join-Path $outDir ("sim-" + $f.BaseName + ".log")
    $out = & java -cp $jar "-Dloader.main=org.cloudsimplus.examples.kubernetes.K8sClusterFromYamlExample" "-Dk8syaml.config=$($f.FullName)" org.springframework.boot.loader.launch.PropertiesLauncher 2>&1
    [System.IO.File]::WriteAllText($log, ($out -join "`n"), [System.Text.UTF8Encoding]::new($false))
    if (-not (Select-String -Path $log -Pattern 'rq1-s\d{3}-\d' -Quiet)) { $fail++ }
    if ($i % 50 -eq 0) { Write-Host ("  $i/$n  (elapsed {0}s, fails={1})" -f [math]::Round($sw.Elapsed.TotalSeconds), $fail) }
}
$sw.Stop()
Write-Host ("DONE $OutDir : $n scenarios in {0}s; missing-placement logs={1}" -f [math]::Round($sw.Elapsed.TotalSeconds), $fail)
