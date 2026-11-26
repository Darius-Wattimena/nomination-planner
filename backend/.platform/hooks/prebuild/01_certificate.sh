#!/bin/bash
# Gets the Let's Encrypt certificate for the address CloudFront reaches the backend on, before nginx starts with it.
# Let's Encrypt checks a DNS record in Route 53, so no port has to be open for it. Runs on every deploy, but only asks
# Let's Encrypt for a certificate when the instance has none yet or it expires within 30 days.
set -euo pipefail

domain=$(/opt/elasticbeanstalk/bin/get-config environment -k CERTIFICATE_DOMAIN || true)
if [ -z "$domain" ]; then
  echo "CERTIFICATE_DOMAIN is not set, see infra/planner.yml" >&2
  exit 1
fi

if [ ! -x /opt/certbot/bin/certbot ]; then
  python3 -m venv /opt/certbot
  /opt/certbot/bin/pip install --quiet --upgrade pip
  /opt/certbot/bin/pip install --quiet certbot certbot-dns-route53
fi

/opt/certbot/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email \
  --dns-route53 --cert-name planner --domain "$domain" --keep-until-expiring

# Renewing twice a day, as Let's Encrypt advises. Only renews when it expires within 30 days.
cat > /etc/systemd/system/certbot-renew.service <<'EOF'
[Unit]
Description=Renews the Let's Encrypt certificate of the planner

[Service]
Type=oneshot
ExecStart=/opt/certbot/bin/certbot renew --quiet --deploy-hook "systemctl reload nginx"
EOF

cat > /etc/systemd/system/certbot-renew.timer <<'EOF'
[Unit]
Description=Renews the Let's Encrypt certificate of the planner twice a day

[Timer]
OnCalendar=*-*-* 03,15:00:00
RandomizedDelaySec=1h
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now certbot-renew.timer
