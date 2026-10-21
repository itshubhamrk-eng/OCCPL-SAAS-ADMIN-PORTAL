# extra/

Caddy site blocks that are not part of the platform (`*.caddy`) are loaded from here: the `Caddyfile` ends with
`import /etc/caddy/extra/*.caddy`, and `docker-compose.yml` mounts this folder at `/etc/caddy/extra`.

The release deploy copies `infra/vps/` to `/opt/occpl/` with `rsync` and no `--delete`, so files that only exist on the server
(for example `website.caddy` of the public website, and the `site/` folder with its static files) are left alone.
Only the `Caddyfile` and `docker-compose.yml` are overwritten on each deploy, which is why the import and the two mounts live in them.
