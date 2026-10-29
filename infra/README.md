# Local infrastructure

`make infra` starts the services below and waits until all are healthy
(Keycloak health is probed on its management port, `/health/ready`).

| Service         | Port(s)      | What it is                                                           |
| --------------- | ------------ | -------------------------------------------------------------------- |
| `postgres`      | 5432         | PostgreSQL 17 + PostGIS                                              |
| `keycloak`      | 8180         | Keycloak, realm `nadel`                                              |
| `mailpit`       | 1025, 8025   | Test mail server: SMTP on 1025, web UI and API on 8025 (in memory)   |
| `fake-telegram` | 8091         | Fake Telegram Bot API (`fake-telegram/server.mjs`), no real network  |

## Mailpit (e-mail)

The backend sends e-mail to `localhost:1025` in development. Open http://localhost:8025 to read the messages,
or use the API: `curl localhost:8025/api/v1/messages`. Messages live in memory and disappear on restart.

## Fake Telegram

The backend talks to the fake Bot API when `NADEL_TELEGRAM_API_URL=http://localhost:8091`; see
`.env.example`. Any bot token works, except tokens starting with `bad`, which get `401 Unauthorized`. Updates and
sent messages are kept per token, so two cooperatives' bots do not mix. Message texts and tokens are never logged.

```bash
# A resident presses "Start" with a link code (chat 555)
curl -s -X POST localhost:8091/control/updates -H 'content-type: application/json' \
  -d '{"token":"123:demo","chatId":555,"text":"/start ABC123","firstName":"Olena"}'

# What the bot sent to chat 555 (method, chatId, text, documentName, replyMarkup, at)
curl -s 'localhost:8091/control/sent?token=123:demo&chatId=555'

# Simulate a resident who blocked the bot (sendMessage then answers 403), and undo it
curl -s -X POST localhost:8091/control/block/555
curl -s -X DELETE localhost:8091/control/block/555

# Forget everything
curl -s -X POST localhost:8091/control/reset
```

To check against the real Telegram instead (owner's check), create a bot with @BotFather, leave the variable
unset or set `NADEL_TELEGRAM_API_URL=https://api.telegram.org`, register the real token in the cooperative
settings, and stop the fake service with `docker compose -f infra/docker-compose.yml stop fake-telegram`.

## PostGIS image (Apple Silicon)

The default image `postgis/postgis:17-3.5` is published for linux/amd64 only, so Docker on an Apple Silicon Mac
runs PostgreSQL under emulation: the local database and the backend tests are several times slower. Both
`docker-compose.yml` and the Testcontainers base class (`backend/src/test/java/app/nadel/IntegrationTest.java`)
read the image from `NADEL_POSTGIS_IMAGE`. On a Mac set it to the multi-arch build of the same PostgreSQL 17 +
PostGIS 3.5, made by a maintainer of the official images from the same sources:

```bash
# ~/.zshrc
export NADEL_POSTGIS_IMAGE=imresamu/postgis:17-3.5
```

A data volume is not portable between architectures: after switching, recreate it once with
`docker compose -f infra/docker-compose.yml down -v && make infra`. CI and servers keep the default image.

## Keycloak realm changes

`keycloak/nadel-realm.json` is imported only when the realm does not exist yet. After editing it,
recreate the stack with fresh volumes so the import runs again:

```bash
docker compose -f infra/docker-compose.yml down -v
make infra
```

`down -v` deletes all local data (Postgres volume included). Cooperative roles are not Keycloak roles
(ADR 0009); the realm only keeps `PLATFORM_OPERATOR`.

## Database for `make e2e`

`make e2e` never touches the demo database `nadel` that `make up` uses. It drops any stale `nadel_e2e`
database, creates a fresh one in the same Postgres (owner `nadel_owner`, PostGIS installed, `USAGE` on schema
`public` granted to `nadel_app`, as in `postgres/init/01-roles.sql`), starts the backend with `NADEL_DB_URL`
pointing at it (Flyway migrates it and loads the demo data of the `dev` profile), and drops it in the cleanup
trap after the backend stops, also when the run fails or is interrupted. Keycloak keeps its own database and users.
Override the name with `make e2e E2E_DB=other_name`.

## Local master key (`infra/.env`)

`make up`, `make backend` and `make e2e` create `infra/.env` on first use with one line,
`NADEL_PD_MASTER_KEY=<random base64 key>`, the local master key for personal-data encryption. The file is
git-ignored, has mode 600 and is sourced only for the backend process (docker compose never reads it). An existing
file is never overwritten. Never commit it. Losing the key makes the contacts encrypted with it unreadable; to start
over, delete the file and recreate the local data:

```bash
rm infra/.env
docker compose -f infra/docker-compose.yml down -v
make up
```
