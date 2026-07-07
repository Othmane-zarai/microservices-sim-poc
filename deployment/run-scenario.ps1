# run-scenario.ps1 — runner for the YAML scenarios in this project.
#
# Usage:
#   .\deployment\run-scenario.ps1 -Scenario 01-baseline
#   .\deployment\run-scenario.ps1 -Scenario 06-autoscaling-stress -Duration 300
#   .\deployment\run-scenario.ps1 -All
#
# Scenarios live on the classpath at
#   src/main/resources/k8s-clusters/scenarios/<name>.yaml
# and are consumed by org.cloudsimplus.examples.kubernetes.K8sClusterFromYamlExample.
#
# The script builds the Spring Boot fat jar once (cached in target/) and then
# invokes the example directly via `java -jar`, disabling the web server so the
# JVM exits when the example finishes.

[CmdletBinding(DefaultParameterSetName = 'Single')]
param(
    [Parameter(ParameterSetName = 'Single', Position = 0)]
    [string]$Scenario,

    # Filesystem path to a scenario YAML outside the bundled classpath dir
    # (e.g. deployment/rq1/scenarios/s000.yaml). Mutually exclusive with -Scenario.
    [Parameter(ParameterSetName = 'External')]
    [string]$ScenarioPath,

    [Parameter(ParameterSetName = 'All')]
    [switch]$All,

    [int]$Duration = 120
)

$ErrorActionPreference = 'Stop'
$projectRoot   = Split-Path -Parent $PSScriptRoot
$scenariosDir  = Join-Path $projectRoot 'src\main\resources\k8s-clusters\scenarios'
$targetDir     = Join-Path $projectRoot 'target'
$jarPattern    = 'csp-examples-springboot-*.jar'

function Ensure-Jar {
    $jar = Get-ChildItem -Path $targetDir -Filter $jarPattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
        Select-Object -First 1
    if (-not $jar) {
        Write-Host "Building fat jar (one-time; ~30 s)..." -ForegroundColor Yellow
        Push-Location $projectRoot
        try { & .\mvnw.cmd -q -DskipTests package }
        finally { Pop-Location }
        $jar = Get-ChildItem -Path $targetDir -Filter $jarPattern |
            Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
            Select-Object -First 1
    }
    if (-not $jar) { Write-Error "Build succeeded but no jar found in $targetDir" }
    return $jar.FullName
}

function Invoke-Scenario {
    param(
        [string]$Name,
        [string]$ExternalPath
    )
    if ($ExternalPath) {
        if (-not (Test-Path $ExternalPath)) {
            Write-Error "Scenario file not found: $ExternalPath"
        }
        $cpPath = (Resolve-Path $ExternalPath).Path
        $Name = [System.IO.Path]::GetFileNameWithoutExtension($cpPath)
    }
    else {
        $yaml = Join-Path $scenariosDir "$Name.yaml"
        if (-not (Test-Path $yaml)) {
            Write-Error "Scenario not found: $yaml"
        }
        $cpPath = "k8s-clusters/scenarios/$Name.yaml"
    }
    $jarPath = Ensure-Jar
    Write-Host "`n=== Running scenario: $Name (duration=${Duration}s) ===" -ForegroundColor Cyan

    # Ensure UTF-8 end-to-end so the simulator's box-drawing chars render
    # correctly (otherwise PowerShell decodes them as cp1252 / cp850 mojibake).
    $prevOut = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    try {
        & java `
            "-Dfile.encoding=UTF-8" `
            "-Dstdout.encoding=UTF-8" `
            "-Dstderr.encoding=UTF-8" `
            "-Dk8syaml.config=$cpPath" `
            "-Dk8syaml.duration=$Duration" `
            -jar $jarPath `
            "--example=K8sClusterFromYamlExample" `
            "--spring.main.web-application-type=none"
    }
    finally {
        [Console]::OutputEncoding = $prevOut
    }
}

if ($All) {
    Get-ChildItem -Path $scenariosDir -Filter '*.yaml' | Sort-Object Name | ForEach-Object {
        $name = [System.IO.Path]::GetFileNameWithoutExtension($_.Name)
        Invoke-Scenario -Name $name > "deployment/rq1/sim-$name.log"
    }
}
elseif ($ScenarioPath) {
    Invoke-Scenario -ExternalPath $ScenarioPath
}
elseif ($Scenario) {
    Invoke-Scenario -Name $Scenario
}
else {
    Write-Host "Available scenarios:" -ForegroundColor Yellow
    Get-ChildItem -Path $scenariosDir -Filter '*.yaml' | Sort-Object Name | ForEach-Object {
        Write-Host "  $([System.IO.Path]::GetFileNameWithoutExtension($_.Name))"
    }
    Write-Host "`nUsage: .\deployment\run-scenario.ps1 -Scenario <name> [-Duration <seconds>]"
    Write-Host "       .\deployment\run-scenario.ps1 -All [-Duration <seconds>]"
}
