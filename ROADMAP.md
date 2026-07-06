# PhD Year 2 Research Roadmap — Adaptive Game-Theoretic Orchestrator for Microservices

## Main Goal

Build a **simulation-based research paper** that demonstrates measurable improvements in microservice performance using a **runtime game-theoretic orchestrator**.

Your paper should move from **literature review** to **concrete contribution**.

---

# Core Research Idea

Design an orchestrator that:

1. Monitors system metrics in real time.
2. Detects congestion / SLA risks / imbalance.
3. Uses game theory to make decisions.
4. Reallocates resources dynamically.
5. Improves performance versus baseline schedulers.

---

# Suggested Paper Titles

## Option 1

Adaptive Game-Theoretic Orchestration for Performance Optimization in Microservice Systems

## Option 2

A Hybrid Multi-Agent and Game-Theoretic Runtime Scheduler for Microservices

## Option 3

Dynamic Resource Coordination in Microservices using Congestion Games and Agent-Based Monitoring

---

# Research Problem to Solve

Choose one main focus:

## A. Latency reduction under overload

## B. Fair CPU / memory allocation during traffic spikes

## C. Avoid cascading failures during congestion

### Recommended Choice

Combine **A + B**:

> Minimize latency while fairly allocating resources.

---

# Phase 1 — Problem Definition (Weeks 1–2)

Define:

* System architecture
  n- Performance metrics
* Resource constraints
* Workload patterns
* Optimization objective

## Example Objective Function

Maximize:

U = Throughput - Latency - Cost

---

# Phase 2 — Build Simulation Environment (Weeks 3–6)

## Recommended Tools

### Option A (Best)

Python + SimPy

### Option B (Advanced)

Kubernetes + Locust + Prometheus + Grafana

---

# Simulated Microservices Architecture

Create a chain such as:

* API Gateway
* Auth Service
* Product Service
* Payment Service
* Recommendation Service
* Database Service

Each service should have:

* Queue
* CPU limit
* Memory limit
* Response time model
* Request rate

---

# Phase 3 — Baseline Experiments (Weeks 7–9)

Run the system **without your orchestrator**.

## Baseline Policies

1. Round Robin
2. Random Placement
3. Static Allocation
4. Kubernetes HPA-like autoscaling
5. First-Fit placement

## Metrics to Measure

* Average latency
* P95 / P99 latency
* Throughput
* SLA violations
* CPU utilization
* Memory utilization
* Fairness index
* Failed requests

---

# Phase 4 — Build Your Main Contribution (Weeks 10–14)

# Hybrid Runtime Game-Theoretic Orchestrator (HRGO)

## Components

### Layer 1 — Monitoring Agent

Collects:

* CPU
  n- Memory
* Queue lengths
* Response times
* Failure rate

### Layer 2 — Congestion Game Engine

Detect overloaded nodes and competing services.

### Layer 3 — Nash Bargaining Resolver

Fairly allocate resources among services.

### Layer 4 — Actuator

Executes decisions:

* Scale replicas
* Move workloads
* Reassign traffic
* Increase/decrease CPU shares

---

# Utility Model for Each Service

Each service is a player.

Utility example:

Ui = a(Throughput) - b(Latency) - c(Resource Cost)

Where a,b,c are tunable weights.

---

# Decisions Each Service Can Make

* Request more CPU
* Release resources
* Replicate instances
* Migrate to another node
* Share traffic with another service
* Join cooperative coalition

---

# Phase 5 — Comparison Experiments (Weeks 15–18)

Compare:

| Method       | Avg Latency | SLA Violations | CPU Balance |
| ------------ | ----------- | -------------- | ----------- |
| Round Robin  | High        | High           | Poor        |
| HPA          | Medium      | Medium         | Medium      |
| Static GT    | Better      | Lower          | Good        |
| HRGO (Yours) | Best        | Lowest         | Best        |

---

# Phase 6 — Dynamic Workload Scenarios (Weeks 19–21)

Simulate:

1. Normal traffic
2. Sudden spike
3. Periodic bursts
4. Node failure
5. Heavy service bottleneck
6. Flash crowd scenario

Show that HRGO adapts faster than baselines.

---

# Required Graphs for Paper

Create at least 5 graphs:

1. Latency vs Requests/sec
2. Throughput vs Time
3. SLA Violations vs Time
4. CPU Utilization Heatmap
5. Fairness Index Comparison
6. Recovery Time After Failure

---

# Strong Novel Extensions (Optional)

## Idea A — Predictive Orchestrator

Use ARIMA / LSTM traffic forecasting before game decisions.

## Idea B — Trust-Aware Cooperation

Some services behave selfishly; orchestrator learns trust scores.

## Idea C — Coalition Formation

Temporary resource-sharing alliances between services.

## Idea D — Multi-objective Optimization

Optimize latency + cost + energy together.

---

# Paper Structure

## 1. Introduction

Why microservices need adaptive orchestration.

## 2. Related Work

Reuse and extend your review paper.

## 3. System Model

Simulation architecture and assumptions.

## 4. Proposed HRGO Method

Algorithms + equations + workflow.

## 5. Experimental Setup

Traffic models, workloads, hardware assumptions.

## 6. Results

Tables + graphs + statistical analysis.

## 7. Discussion

Scalability, limits, production relevance.

## 8. Conclusion

Summary + future work.

---

# 3-Month Execution Plan

## Month 1

* Finalize problem statement
* Build simulator
* Implement baselines

## Month 2

* Implement HRGO orchestrator
* Run experiments
* Tune parameters

## Month 3

* Generate graphs
* Write paper
* Submit to conference/journal

---

# Good Venues for Submission

## Conferences

* IEEE CLOUD
* Middleware
* ICSOC
* IEEE ICC
* AAMAS

## Journals

* Future Generation Computer Systems
* Journal of Systems and Software
* IEEE Transactions on Cloud Computing

---

# Key Advice

Do **not** try to solve everything.

Make one strong measurable claim:

> HRGO reduces average latency by 22% under burst workloads while lowering SLA violations by 30%.

That alone can become a publishable Year 2 paper.

---

# Final Deliverables

## Technical Deliverables

* Simulator source code
* HRGO algorithm
* Datasets/logs
* Graphs
* Experimental report

## Academic Deliverables

* Conference paper
* Thesis chapter draft
* Reproducible methodology

---

# Best Practical Direction

If unsure, build:

> Online Congestion-Game Orchestrator for Kubernetes-like Microservices

This strongly connects theory with real systems.
