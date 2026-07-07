#!/usr/bin/env bash
# run-scenario.sh — runner for the YAML scenarios in this project.
#
# Usage:
#   ./deployment/run-scenario.sh 01-baseline                   # default duration=120
#   ./deployment/run-scenario.sh 06-autoscaling-stress 300
#   ./deployment/run-scenario.sh --all                         # runs every scenario
#
# Scenarios live on the classpath at
#   src/main/resources/k8s-clusters/scenarios/<name>.yaml
# and are consumed by org.cloudsimplus.examples.kubernetes.K8sClusterFromYamlExample.
#
# The script builds the Spring Boot fat jar once (cached in target/) and then
# invokes the example directly via `java -jar`, disabling the web server so the
# JVM exits when the example finishes.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/.." && pwd)"
scenarios_dir="$project_root/src/main/resources/k8s-clusters/scenarios"
target_dir="$project_root/target"

ensure_jar() {
    local jar
    jar="$(find "$target_dir" -maxdepth 1 -name 'csp-examples-springboot-*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | head -1)"
    if [[ -z "$jar" ]]; then
        echo "Building fat jar (one-time; ~30 s)..." >&2
        ( cd "$project_root" && ./mvnw -q -DskipTests package )
        jar="$(find "$target_dir" -maxdepth 1 -name 'csp-examples-springboot-*.jar' \
            ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"
    fi
    [[ -n "$jar" ]] || { echo "ERROR: build succeeded but no jar found in $target_dir" >&2; exit 1; }
    echo "$jar"
}

run_scenario() {
    local name="$1"
    local duration="${2:-120}"
    local yaml="$scenarios_dir/$name.yaml"
    [[ -f "$yaml" ]] || { echo "ERROR: scenario not found: $yaml" >&2; exit 1; }
    local jar
    jar="$(ensure_jar)"
    local cp_path="k8s-clusters/scenarios/$name.yaml"
    echo
    echo "=== Running scenario: $name (duration=${duration}s) ==="
    java \
        "-Dfile.encoding=UTF-8" \
        "-Dstdout.encoding=UTF-8" \
        "-Dstderr.encoding=UTF-8" \
        "-Dk8syaml.config=$cp_path" \
        "-Dk8syaml.duration=$duration" \
        -jar "$jar" \
        "--example=K8sClusterFromYamlExample" \
        "--spring.main.web-application-type=none"
}

if [[ "${1:-}" == "--all" ]]; then
    duration="${2:-120}"
    for yaml in "$scenarios_dir"/*.yaml; do
        run_scenario "$(basename "$yaml" .yaml)" "$duration"
    done
elif [[ -n "${1:-}" ]]; then
    run_scenario "$1" "${2:-120}"
else
    echo "Available scenarios:"
    for yaml in "$scenarios_dir"/*.yaml; do
        echo "  $(basename "$yaml" .yaml)"
    done
    echo
    echo "Usage: ./deployment/run-scenario.sh <scenario-name> [duration-seconds]"
    echo "       ./deployment/run-scenario.sh --all [duration-seconds]"
fi
