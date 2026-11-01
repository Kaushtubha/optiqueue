# Network Security & Zero-Trust Communication Guide

This document outlines network isolation, firewall rules, and Mutual TLS (mTLS) configurations for OptiQueue.

---

## 1. Network Segmentation Architecture

OptiQueue components should reside in isolated VPC subnets:

```
[ Public DMZ ]            [ Application Private Subnet ]      [ Data Isolated Subnet ]
ALB (HTTPS / 443)  --->   OptiQueue Pods (Port 8080)     --->  PostgreSQL (Port 5432)
                                                              Redis (Port 6379)
```

- Ingress to OptiQueue pods is restricted strictly to the Load Balancer security group.
- Ingress to PostgreSQL and Redis is restricted strictly to OptiQueue pod CIDRs.

---

## 2. In-Transit Encryption & mTLS

1. **Redis TLS**: Configured via `REDIS_SSL=true`. All commands and payloads sent between Spring Data Redis Lettuce client and Redis cluster nodes are encrypted with TLS 1.3.
2. **PostgreSQL SSL**: Configured with `sslmode=verify-full` enforcing certificate authority validation against server spoofing.
