#!/bin/bash
# Puts the passwords from AWS Secrets Manager in a file only Tomcat can read, which application.yml imports.
# They are not Beanstalk settings, as the Tomcat platform writes those into the system log of the instance.
set -euo pipefail

secret=$(/opt/elasticbeanstalk/bin/get-config environment -k SECRET_ID || true)
if [ -z "$secret" ]; then
  echo "SECRET_ID is not set, the passwords have to be in the Beanstalk settings"
  exit 0
fi

token=$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
region=$(curl -sf -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/placement/region)
group=$(stat -c %G /var/lib/tomcat11/webapps)

install -d -m 750 -o root -g "$group" /etc/bnplanner
umask 027
# The secret is JSON, which Spring reads as YAML
aws secretsmanager get-secret-value --region "$region" --secret-id "$secret" --query SecretString --output text \
  > /etc/bnplanner/secrets.yml.new
chown root:"$group" /etc/bnplanner/secrets.yml.new
chmod 640 /etc/bnplanner/secrets.yml.new
mv -f /etc/bnplanner/secrets.yml.new /etc/bnplanner/secrets.yml
echo "Wrote the passwords of $secret for Tomcat"
