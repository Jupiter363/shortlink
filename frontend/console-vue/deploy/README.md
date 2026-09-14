# Relay Console Nginx deployment

`nginx.conf` serves the Vite `dist/` tree and proxies same-origin `/api/` requests to the Compose service `apisix:9080`. It deliberately sends `Host: admin.local.test`, because the checked-in APISIX management route is Host-scoped. Change this value together with the deployed `MANAGEMENT_HOST`; changing only one side produces APISIX 404 responses.

## Build and mount

Build the static files from `frontend/console-vue`:

```powershell
npm.cmd ci
npm.cmd run build
```

For the current generated local runtime, copy the reviewed source configuration into its generated input, then let the runtime owner recreate only the frontend container:

```powershell
Copy-Item -LiteralPath frontend/console-vue/deploy/nginx.conf -Destination .work/local-dev/nginx.conf
docker compose -f .work/local-dev/compose.yaml up -d --no-deps --force-recreate frontend
```

The copy overwrites a generated runtime file, so run it only from the repository root after reviewing local `.work/local-dev` ownership. This package does not contain a production Compose file. A deployment must mount:

- `frontend/console-vue/dist` at `/usr/share/nginx/html:ro`
- `frontend/console-vue/deploy/nginx.conf` at `/etc/nginx/nginx.conf:ro`
- an upstream reachable by the DNS name `apisix` on port `9080`

Validate before replacing a running container. One isolated option, if the image is already available, is:

```powershell
docker run --rm --network shortlink-local-dev_app --entrypoint nginx `
  -v "${PWD}/frontend/console-vue/deploy/nginx.conf:/etc/nginx/nginx.conf:ro" `
  nginx:1.27.4-alpine -t
```

This command uses the current local Compose network so `apisix` can resolve. Other deployments must use their actual network name. A syntax check does not exercise upstream routes, Host policy, authentication, exports, or timeouts; also test through the frontend origin.

## Budgets encoded here

- `client_max_body_size 8m` equals Admin's dedicated batch limit of 8,388,608 bytes (`services/admin/src/main/resources/application-production.properties:48-50`). Ordinary endpoints remain stricter at Admin (262,144 bytes). Nginx is only the outer ceiling and does not replace that per-route enforcement.
- `client_body_timeout 5s` follows Admin's bounded body read timeout (`application-production.properties:51`). Request buffering is disabled so APISIX and Admin can enforce their streaming and aggregate budgets without Nginx staging another complete body.
- General API reads use a 15-second inactivity timeout, leaving room above APISIX's 10-second management timeout (`deploy/apisix/apisix.yaml:45-46`).
- Agent chat uses 55 seconds. Admin's Agent Feign call has a 45-second read timeout (`application-production.properties:63-66`) and APISIX allows 50 seconds (`deploy/apisix/apisix.yaml:78-79`), so APISIX remains the effective public deadline and Nginx does not cut it off first.
- Export response buffering is disabled. Admin may stream a terminal batch for up to five minutes, but APISIX and Nginx timeouts are inactivity limits: the stream must continue producing data. A quiet interval beyond APISIX's 10 seconds still terminates the request.

API responses always receive `Cache-Control: no-store`. `/assets/` is cached for one year with `immutable`, matching Vite's content-hashed output. `index.html` and SPA fallbacks use `no-store`, ensuring a deployment can point users at the latest asset names.

The file contains no credentials or internal token. Browser authentication remains the `Username` and `Token` headers sent by the application. The access log deliberately records `$uri` without query arguments: the existing logout API puts its token in the query string, which must not be written to request logs.
