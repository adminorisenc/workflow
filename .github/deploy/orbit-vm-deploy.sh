#!/bin/bash
# Runs on orbit-vm (Azure run-command) to roll one backend to a new image tag. Parameters arrive as
# environment variables: SERVICE, IMAGE_TAG and the protected ACR_TOKEN.
set -euo pipefail
cd /opt/orisenc

echo "$ACR_TOKEN" | docker login orisencorbitacr.azurecr.io \
  -u 00000000-0000-0000-0000-000000000000 --password-stdin >/dev/null

# Each service keeps its own tag in .env (TAG_SALES=...), so deploying one never moves the others.
var="TAG_$(echo "$SERVICE" | tr 'a-z-' 'A-Z_')"
if grep -q "^$var=" .env; then
  sed -i "s#^$var=.*#$var=$IMAGE_TAG#" .env
else
  echo "$var=$IMAGE_TAG" >> .env
fi

# Pin the service's image to that variable in the deploy override (first deployment only).
if ! grep -q "/$SERVICE:\${$var}" docker-compose.deploy.yml; then
  line="    image: \${ORISENC_IMAGE_REGISTRY}/$SERVICE:\${$var}"
  awk -v svc="  $SERVICE:" -v add="$line" '{ print } $0 == svc { print add }' \
    docker-compose.deploy.yml > docker-compose.deploy.yml.tmp
  mv docker-compose.deploy.yml.tmp docker-compose.deploy.yml
fi

DC="docker compose -f docker-compose.prod.yml -f docker-compose.deploy.yml"
$DC pull -q "$SERVICE"
$DC up -d --no-deps "$SERVICE"
docker logout orisencorbitacr.azurecr.io >/dev/null
echo "deployed $SERVICE:$IMAGE_TAG"
