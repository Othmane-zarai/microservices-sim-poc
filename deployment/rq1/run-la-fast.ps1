# Fast LEAST_ALLOCATED RQ1 run: invokes the example's own main via Spring Boot
# PropertiesLauncher (no Spring context => ~1s/run instead of ~30s). One JVM per
# scenario, output captured per scenario for rq1_agreement.py / kss recompute.
Set-Location "C:\Users\Administrator\Documents\microservices-sim-poc"
$jar    = (Resolve-Path "target\cloudsimplus-k8s-examples-1.0.0-SNAPSHOT.jar").Path
$scDir  = (Resolve-Path "deployment\rq1\scenarios-la").Path
$outDir = "deployment\rq1\rq1-la-fresh"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
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
Write-Host ("DONE $n scenarios in {0}s; missing-placement logs={1}" -f [math]::Round($sw.Elapsed.TotalSeconds), $fail)
