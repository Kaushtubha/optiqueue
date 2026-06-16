#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# Oracle Cloud ARM VM — One-time setup script
#
# Run this ONCE after creating a free-tier Oracle Cloud VM.
# It installs Docker, clones the repo, and starts the app.
#
# USAGE:
#   chmod +x setup-oracle-vm.sh
#   ./setup-oracle-vm.sh
#
# PREREQUISITES:
#   - Oracle Cloud "Always Free" A1 Ampere VM (arm64)
#   - Ubuntu 22.04 image
#   - SSH access configured
#   - Port 8080 opened in Security List (OCI Console → VCN → Security List)
#
# INTERVIEW Q: "Why Oracle Cloud Always Free?"
#   Ampere A1 VMs: 4 OCPUs + 24 GB RAM free FOREVER (not just trial period).
#   No credit card charge after trial. Only cloud with truly free ARM compute.
#   arm64 → Docker images must be linux/arm64 (our CI builds multi-arch).
# ═══════════════════════════════════════════════════════════════════

set -e   # Exit on error
set -o pipefail

echo "════════════════════════════════════════"
echo " OptiQueue — Oracle Cloud VM Setup"
echo "════════════════════════════════════════"

# ── 1. UPDATE SYSTEM ────────────────────────────────────────────
echo "➜ Updating system packages..."
sudo apt-get update -q
sudo apt-get upgrade -y -q

# ── 2. INSTALL DOCKER ───────────────────────────────────────────
# Official Docker install method (NOT apt-get install docker.io which is outdated)
echo "➜ Installing Docker..."
curl -fsSL https://get.docker.com | sudo sh

# Add current user to docker group (no sudo needed for docker commands)
sudo usermod -aG docker "$USER"
echo "⚠  Log out and log back in for docker group to take effect (or run: newgrp docker)"

# Enable Docker to start on boot
sudo systemctl enable docker
sudo systemctl start docker

echo "✅ Docker installed: $(docker --version)"

# ── 3. CONFIGURE FIREWALL ───────────────────────────────────────
# Oracle Cloud has TWO firewall layers:
#   1. iptables (OS level) — must open here
#   2. Security List (OCI Console) — must open in OCI Console manually
echo "➜ Opening port 8080 in iptables..."
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8080 -j ACCEPT

# Make iptables rules persistent across reboots
sudo apt-get install -y iptables-persistent -q
sudo netfilter-persistent save

echo "✅ Port 8080 opened in iptables"
echo "⚠  Remember to open port 8080 in OCI Console → VCN → Security List!"

# ── 4. CLONE REPOSITORY ─────────────────────────────────────────
echo "➜ Cloning OptiQueue repository..."
mkdir -p ~/optiqueue
cd ~/optiqueue

# Will fail if already cloned — that's fine
git clone https://github.com/${GITHUB_REPO:-YOUR_USERNAME/optiqueue} . 2>/dev/null || \
  git pull origin main

echo "✅ Repository ready at ~/optiqueue"

# ── 5. CREATE .env FILE ─────────────────────────────────────────
# Production secrets go here — this file is .gitignored
echo "➜ Creating .env file for production secrets..."

if [ ! -f ~/optiqueue/.env ]; then
  # Generate a random API key for production
  API_KEY=$(openssl rand -hex 32)

  cat > ~/optiqueue/.env << EOF
# OptiQueue Production Environment
# Generated on $(date)
# ⚠ DO NOT COMMIT THIS FILE — it is .gitignored

DB_USER=optiqueue
DB_PASS=$(openssl rand -hex 16)
REDIS_PASS=

# API authentication
API_KEYS=${API_KEY}

# Worker configuration (adjust for your VM size)
WORKER_POOL_SIZE=8
WORKER_QUEUE_CAP=200

# Logging
LOG_LEVEL=INFO
EOF

  echo "✅ .env created with random credentials"
  echo "📋 Your API key: ${API_KEY}"
  echo "   (Save this — you'll need it for X-API-Key header)"
else
  echo "⚠  .env already exists — skipping"
fi

# ── 6. START THE APPLICATION ─────────────────────────────────────
echo "➜ Starting OptiQueue with Docker Compose..."
cd ~/optiqueue

# Pull images (pulls from ghcr.io for arm64)
docker compose pull

# Start all services
docker compose up -d

echo ""
echo "════════════════════════════════════════"
echo "✅ OptiQueue is starting!"
echo ""
echo "Check status:  docker compose ps"
echo "View logs:     docker compose logs -f app"
echo "Health check:  curl http://localhost:8080/actuator/health"
echo ""
echo "⚠  Wait ~60s for Postgres + Flyway migrations to complete"
echo "════════════════════════════════════════"
