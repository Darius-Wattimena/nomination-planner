# Nomination Planner

The Nomination Planner is a web application designed to help Beatmap Nominators plan and manage their nominations.

- `backend/`: Kotlin, Spring Boot and MongoDB
- `frontend/`: React and Vite

## Run locally

Use Java 21 for the backend. Put your osu! OAuth client in `backend/src/main/resources/application-local.yml` and set the same client ID as `osu_id` in `frontend/src/Settings.ts`.

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

## Features Requests

Want something changed? Contact me on Discord at `greaper` or contact me directly on osu!.
