# Database & Redis Backup and Restore Runbook

This runbook describes automated snapshot procedures, WAL archiving, and disaster recovery restoration for OptiQueue storage layers.

---

## 1. PostgreSQL Backup Procedures

### Automated Point-In-Time Recovery (PITR)
1. **Continuous WAL Archiving**: Write-Ahead Logs are shipped continuously to durable object storage (e.g. AWS S3 / GCS).
2. **Nightly Physical Base Backups**: `pg_dump` or `pg_basebackup` runs daily at 02:00 UTC.

```bash
# Manual logical backup
pg_dump -h $PGHOST -U optiqueue -Fc -f "optiqueue_backup_$(date +%Y%m%d).dump" optiqueue
```

### Restoration Workflow
```bash
# Restore to temporary database
createdb -h $PGHOST -U optiqueue optiqueue_restore
pg_restore -h $PGHOST -U optiqueue -d optiqueue_restore -v "optiqueue_backup_20261102.dump"
```

---

## 2. Redis RDB Snapshots & AOF Verification

Redis acts as ephemeral coordination state (locks and rate-limiter windows). Persistence is enabled via:
- **AOF (Append Only File)**: `appendfsync everysec`
- **RDB Snapshots**: Captured hourly:
  ```bash
  redis-cli bgsave
  ```
