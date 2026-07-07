# Reproducibility - OCI k3s cluster
## Captured 2026-05-20T20:05:08Z
### k3s server
```

```
### Node specs
#### k3s-server
```
Capacity:
  Kernel Version:             6.8.0-1049-oracle
  OS Image:                   Ubuntu 22.04.5 LTS
  Architecture:               arm64
  Container Runtime Version:  containerd://2.2.3-k3s1
```
#### k3s-worker-1
```
Capacity:
  Kernel Version:             6.8.0-1049-oracle
  OS Image:                   Ubuntu 22.04.5 LTS
  Architecture:               arm64
  Container Runtime Version:  containerd://2.2.3-k3s1
```
#### k3s-worker-2
```
Capacity:
  Kernel Version:             6.8.0-1049-oracle
  OS Image:                   Ubuntu 22.04.5 LTS
  Architecture:               arm64
  Container Runtime Version:  containerd://2.2.3-k3s1
```
#### k3s-worker-3
```
Capacity:
  Kernel Version:             6.8.0-1049-oracle
  OS Image:                   Ubuntu 22.04.5 LTS
  Architecture:               arm64
  Container Runtime Version:  containerd://2.2.3-k3s1
```
### Pinned container images
```
python:3.12-slim
rancher/klipper-lb:v0.4.15
rancher/local-path-provisioner:v0.0.35
rancher/mirrored-coredns-coredns:1.14.2
registry.k8s.io/autoscaling/vpa-recommender:1.6.0
registry.k8s.io/metrics-server/metrics-server:v0.8.1
```
