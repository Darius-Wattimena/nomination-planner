# Nomination Planner

The Nomination Planner is a web application designed to help Beatmap Nominators plan and manage their nominations.

- `backend/`: Kotlin, Spring Boot and MongoDB
- `frontend/`: React and Vite
- `infra/`: the AWS setup, see [infra/README.md](infra/README.md)

## Run locally

Use Java 21 for the backend. Put your osu! OAuth client in `backend/src/main/resources/application-local.yml`:

```
osu:
  client-id: "12978"
  client-secret: "..."
  redirect-uri: "http://localhost:8080/login/oauth2/code/osu"
```

The callback URL of the OAuth client at osu! has to be that same `redirect-uri`. Logging in goes through the backend, which keeps the login in a session in MongoDB and sends the browser back to http://localhost:3000.

Backend, needs a local MongoDB:

```
cd backend
./gradlew bootRun --args=--spring.profiles.active=local
```

Frontend, on http://localhost:3000:

```
cd frontend
npm ci
npm start
```

## Following osu!

Without Aiess, the planner can follow osu! by itself. Each setting is in `backend/src/main/resources/application.yml`, off by default, and needs the osu! client ID and secret, as the planner uses its own osu! login.

| Setting | Default | What it does |
|---|---|---|
| `osu.follow-events.enabled` | `false` | Follows the beatmap events of osu! (nominate, qualify, rank, nomination reset, disqualify) and changes the status and nominators of the beatmaps on the planner. Reads the osu! API (`/api/v2/beatmapsets/events`). |
| `osu.follow-events.every-seconds` | `60` | How often it asks osu! for new events. |
| `osu.follow-groups.enabled` | `false` | Follows who joins or leaves the BN, probationary BN and NAT, and their gamemodes, and changes their roles on the planner. A NAT member without a gamemode gets all four. Reads the group history of the osu! website as JSON, as the API has none. |
| `osu.follow-groups.every-seconds` | `600` | How often it asks osu! for group changes. |
| `osu.background-sync.enabled` | `false` | Syncs a few beatmaps in progress every minute (`per-minute`), as a fallback for anything the events miss. |
| `osu.min-request-interval-millis` | `250` | At least this long between two calls to osu!. When osu! answers "too many requests", every call waits as long as its `Retry-After` header asks (otherwise 10 seconds, doubling up to a minute) and is tried again up to 3 times. |

The first time, both start at the newest event and change nothing. After that the last event handled is kept in the `followed` collection, so a restart continues where it stopped and handles nothing twice. To catch up on earlier events, set `lastId` there to an older event ID. When more happened in between than 10 pages of events, the beatmaps in progress are synced instead. After an error it waits a minute, then twice as long each time, up to an hour. What changed is logged with `[OSU]` and sent to the confidential Discord channels.

## Checks

These run in GitHub Actions on every change.

Backend: tests, formatting (ktlint) and the coverage check, which needs every line and branch covered:

```
cd backend
./gradlew build
```

Frontend: type check, lint, unit tests and build:

```
cd frontend
npm run typecheck
npm run lint
npm test
npm run build
```

## Deploying

Pushing a version tag releases the backend and the frontend to the setup of the GitHub environment `beta`, once the checks pass:

```
git tag v2.0.0
git push origin v2.0.0
```

A change on master is only checked. To deploy a tag again, run the workflow by hand in the Actions tab and pick the tag. How that setup is created is in [infra/README.md](infra/README.md).

## Features Requests

Want something changed? Contact me on Discord at `greaper` or contact me directly on osu!.
