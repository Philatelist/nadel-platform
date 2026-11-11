# Test stand (demo data on one server)

A public stand for showing the product to a cooperative board: the demo cooperative of the `dev` profile, behind
Caddy with automatic TLS. It is not production (no backups, demo users); production is
`docs/features/backlog/deployment.md`. Never enter real members' data on the stand.

```
internet ──443──> Caddy ── /        -> web build (/srv)
                        ├─ /api/*   -> backend:8080   (Spring, profile dev)
                        └─ /auth/*  -> keycloak:8080  (start mode; /auth/admin blocked)
                  Postgres + PostGIS (no published port)
```

## 1. Server (once)

1. A Linux VM with 4 vCPU and 8 GB RAM, Ubuntu 24.04, your SSH key added. x86 (Hetzner CX/CPX) uses the default
   PostGIS image; ARM (Hetzner CAX) needs `NADEL_POSTGIS_IMAGE=imresamu/postgis:17-3.5` in `.env`.
2. Firewall (Hetzner Cloud Firewall): inbound 22, 80, 443 only.
3. DNS: an A record `nadel.example.com -> <server IP>`. Wait until `dig +short nadel.example.com` shows the IP.
4. Docker, and `/opt/nadel` owned by the SSH user (on Google Cloud or AWS you log in as your own user, not root;
   pass `<user>@<ip>` to `deploy.sh`):

   ```bash
   ssh <user>@<ip>
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker $USER
   sudo mkdir -p /opt/nadel && sudo chown $USER /opt/nadel
   exit    # log in again so the docker group applies; `ssh <user>@<ip> docker ps` must work without sudo
   ```

   Google Cloud: reserve a static external IP (otherwise the IP changes after a stop), tick "Allow HTTP/HTTPS
   traffic" on the VM (port 22 is open by `default-allow-ssh`), e2-standard-2, Ubuntu 24.04 x86/64, 30 GB disk.

## 2. First install

On your machine, from the repository root on `main`:

```bash
infra/stand/deploy.sh <user>@<ip> nadel.example.com
```

On the server:

```bash
cd /opt/nadel
cp .env.example .env && chmod 600 .env
nano .env                                  # domain and secrets: openssl rand -hex 16 / openssl rand -base64 32
docker compose up -d postgres keycloak     # Caddy not started yet: nothing is reachable from outside
docker compose ps                          # wait until keycloak is "healthy" (about a minute)
./stand-init.sh                            # new demo passwords, web client URLs, extra users disabled, backend Keycloak secret
docker compose up -d                       # Caddy (gets the certificate) and the backend
docker compose logs -f backend             # wait for "Started NadelApplication"
```

`stand-init.sh` also sets the realm password policy `length(8)` (so `STAND_USER_PASSWORD` needs at least 8
characters; the script checks it) and rotates the secret of the Keycloak client `nadel-backend-admin` (the backend's
service account for creating users), writing it to `.env` as `NADEL_KEYCLOAK_ADMIN_CLIENT_SECRET` (mode 600). Do not
choose that value by hand. It is safe to run again, but each run changes the secret: run
`docker compose up -d backend` afterwards. A stand imported before this client existed needs a fresh realm import
(see "Reset" below) before the script finds the client.

`stand-init.sh` also generates the Web Push (VAPID) key pair into `.env` (`NADEL_WEBPUSH_PUBLIC_KEY`,
`NADEL_WEBPUSH_PRIVATE_KEY`) the first time, only when either is empty, and never prints it. Later runs keep the pair:
a new pair would make every stored push subscription useless (members would have to subscribe again). To rotate on
purpose, empty both lines in `.env`, run `./stand-init.sh` and `docker compose up -d backend`. The script waits for
Keycloak and retries the admin login (`KC_LOGIN_TIMEOUT`, default 120 seconds) because a healthy Keycloak may still be
finishing its start. `make stand-test` checks the script with a stub `docker`.

Installing the app on a phone and push notifications need HTTPS with a valid certificate; Caddy provides it on the
stand's domain. Caddy serves `/sw.js`, `/manifest.webmanifest` and `/index.html` with `Cache-Control: no-cache`, so a
new deploy reaches phones on their next visit.

Open `https://nadel.example.com`, sign in as `chairman@demo.local` with `STAND_USER_PASSWORD`.

Keep a copy of `.env` in your password manager (it holds the master key and the Keycloak admin password).

### Search engine indexing

`NADEL_NOINDEX` in `.env` (default `true`) makes Caddy send `X-Robots-Tag: noindex, nofollow` on every response, so a
stand is never indexed. Set `NADEL_NOINDEX=false` only on a real public site that wants the pre-rendered help pages
(`/help/...`) indexed, then `docker compose up -d caddy`.

## 3. Updating

```bash
git switch main && git pull
infra/stand/deploy.sh <user>@<ip> nadel.example.com
```

New migrations run at backend start. The demo data follows the `dev` profile, so changes the board made on the stand
may be overwritten by a new version of the demo data.

## 4. Useful commands (on the server, in /opt/nadel)

```bash
docker compose ps
docker compose logs --tail=200 backend
docker compose restart backend
# Keycloak admin console: from your machine
ssh -L 8180:127.0.0.1:8180 <user>@<ip>       # then http://localhost:8180/auth/admin
# Start over with fresh demo data (deletes everything on the stand, keeps the certificate).
# Never use `docker compose down -v`: it also deletes the Caddy volume and the certificate, and Let's Encrypt limits
# how many certificates one domain may get per week.
docker compose down && docker volume rm nadel-stand_pgdata && docker compose up -d postgres keycloak && ./stand-init.sh && docker compose up -d
```

After a reset the demo cooperative is new: enter the mailbox settings (and the Telegram bot token) in the cooperative
settings again. The `.env` (VAPID keys included) survives a reset, so existing phones keep working.

## 4a. A real cooperative: the first chairman

`first-chairman.sh` creates a cooperative (base roles, settings, charge types) and its first chairman, and prints a
one-time "set your password" link. Use it to start a new cooperative when there is no operator console; it replaces the
manual SQL and Keycloak steps. No Keycloak mail setup is needed: the link goes to the chairman by you.

```bash
cd /opt/nadel     # Postgres and Keycloak must be running (docker compose ps)
./first-chairman.sh --cooperative "SK Zorya" --email chairman@example.org --last-name Ivanenko --first-name Petro
./first-chairman.sh --help
```

- The link is shown **once**, on the screen only, and is not stored in readable form. Copy it and pass it to the
  chairman privately (a messenger or a call, not a public place). It is valid for 72 hours. Do not paste it into
  tickets or chats with several people.
- Running the same command again changes nothing and does not print a link.
- `--resend` issues a new link for a cooperative that already exists (the link was lost or expired); the previous link
  stops working.
- The command is a one-shot `docker compose run --rm --no-deps backend ...` without an HTTP server and without
  published ports. It exits with a non-zero code on any failure and writes an audit entry; nothing personal is logged.
- The chairman opens the link, sets a password, signs in and enters the e-mail contacts and phones in the app. Later
  members are invited from the member card ("Invite to the system").

## 5. Mail and Telegram

- Mail: the chairman enters a real mailbox in the cooperative settings (for Gmail, an app password). Private
  addresses are refused on the stand.
- Telegram: real Bot API. The demo bot token of the demo data is not a real token; to try the bot, create one with
  @BotFather and enter its token in the cooperative settings.
