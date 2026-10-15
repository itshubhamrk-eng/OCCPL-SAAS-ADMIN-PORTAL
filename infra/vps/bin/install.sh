#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu 24.04 server (run as root, or with sudo, over SSH):
#   Docker + Compose, firewall (SSH, 80, 443), fail2ban, automatic security updates, a 2 GB swap
#   file, the unprivileged "occpl" user that owns /opt/occpl, and the backup/heartbeat cron jobs.
# Idempotent. Copy the bundle first:  scp -r infra/vps root@<server>:/opt/occpl
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
root=/opt/occpl
[ -f "$root/docker-compose.yml" ] || { echo "copy infra/vps to $root first" >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q docker.io docker-compose-v2 ufw fail2ban unattended-upgrades jq curl unzip python3
systemctl enable --now docker fail2ban

# Firewall: only SSH and web traffic. (Docker publishes 3306/8081/8088/9000 on 127.0.0.1 only.)
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443 >/dev/null
ufw --force enable >/dev/null

# Security updates install automatically; reboots stay manual.
cat > /etc/apt/apt.conf.d/52occpl-unattended <<'CONF'
Unattended-Upgrade::Automatic-Reboot "false";
CONF

if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# AWS CLI: only used for the off-host backup copy.
if ! command -v aws >/dev/null; then
  tmp=$(mktemp -d)
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "$tmp/awscli.zip"
  unzip -q "$tmp/awscli.zip" -d "$tmp" && "$tmp/aws/install" >/dev/null
  rm -rf "$tmp"
fi

id occpl >/dev/null 2>&1 || useradd --create-home --shell /bin/bash --groups docker occpl
usermod -aG docker occpl
# The deploy user gets the SSH keys of whoever prepared the server (the sudo user on EC2/Ubuntu,
# otherwise root); root's file on Ubuntu cloud images carries a "log in as ubuntu" forced command.
keys=/root/.ssh/authorized_keys
if [ -n "${SUDO_USER:-}" ] && [ -s "/home/$SUDO_USER/.ssh/authorized_keys" ]; then keys="/home/$SUDO_USER/.ssh/authorized_keys"; fi
if [ -s "$keys" ]; then
  install -d -m 700 -o occpl -g occpl /home/occpl/.ssh
  install -m 600 -o occpl -g occpl "$keys" /home/occpl/.ssh/authorized_keys
fi
chown -R occpl:occpl "$root"
chmod +x "$root"/bin/*.sh "$root"/backup/*.sh 2>/dev/null || true
install -d -m 750 -o occpl -g occpl /var/backups/occpl

cat > /etc/cron.d/occpl <<'CRON'
# Full backup 02:00 IST, binary-log increments every 5 minutes, heartbeat every 5 minutes.
30 20 * * * occpl /opt/occpl/bin/backup.sh full
*/5 * * * * occpl /opt/occpl/bin/backup.sh binlog
*/5 * * * * occpl /opt/occpl/bin/healthcheck.sh
CRON
chmod 644 /etc/cron.d/occpl
echo "server prepared. Next, as user occpl:  cd /opt/occpl && bin/init-env.sh"
