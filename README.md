# Nomination Planner

The Nomination Planner is a web application designed to help Beatmap Nominators plan and manage their nominations.

- `backend/`: Kotlin, Spring Boot and MongoDB
- `frontend/`: React and Vite
- `infra/`: the AWS setup, see [infra/README.md](infra/README.md)

## Run locally

Use Java 21 for the backend: Gradle uses the Java of `JAVA_HOME`, or the one IntelliJ is set to. Create `backend/src/main/resources/application-local.yml`, which git ignores, with your osu! OAuth client:

```
osu:
  client-id: "12978"
  client-secret: "..."
  redirect-uri: "http://localhost:8080/login/oauth2/code/osu"
```

The callback URL of the OAuth client at osu! has to be that same `redirect-uri`. Logging in goes through the backend, which keeps the login in a session in MongoDB and sends the browser back to http://localhost:3000.

Backend, needs a MongoDB on `localhost:27017`, for example with `docker run -d -p 27017:27017 mongo:8`:

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

The planner can follow osu! by itself. Each setting is in `backend/src/main/resources/application.yml`. Following is off by default and needs the osu! client ID and secret, as the planner uses its own osu! login.

| Setting | Default | What it does |
|---|---|---|
| `osu.follow-events.enabled` | `false` | Follows the beatmap events of osu! (nominate, qualify, rank, nomination reset, disqualify) and changes the status and nominators of the beatmaps on the planner. Reads the osu! API (`/api/v2/beatmapsets/events`). |
| `osu.follow-events.every-seconds` | `60` | How often it asks osu! for new events. |
| `osu.follow-groups.enabled` | `false` | Follows who joins or leaves the BN, probationary BN and NAT, and their gamemodes, and changes their roles on the planner. A NAT member without a gamemode gets all four. Reads the group history of the osu! website as JSON, as the API has none. |
| `osu.follow-groups.every-seconds` | `600` | How often it asks osu! for group changes. |
| `osu.background-sync.enabled` | `false` | Syncs a few beatmaps in progress every minute, as a fallback for anything the events miss. |
| `osu.background-sync.per-minute` | `6` | How many beatmaps that is. |
| `osu.min-request-interval-millis` | `250` | At least this long between two calls to osu!. When osu! answers "too many requests", every call waits as long as its `Retry-After` header asks (otherwise 10 seconds, doubling up to a minute) and is tried again up to 3 times. |

The first time, both start at the newest event and change nothing. After that the last event handled is kept in the `followed` collection, so a restart continues where it stopped and handles nothing twice. To catch up on earlier events, set `lastId` there to an older event ID. When more happened in between than 10 pages of events, the beatmaps in progress are synced instead. After an error it waits a minute, then twice as long each time, up to an hour. What changed is logged with `[OSU]` and sent to the confidential Discord channels.

## Other settings

| Setting | Default | What it does |
|---|---|---|
| `planner.admins` | `"2369776"` | osu! ids that may do everything on the planner, whatever their roles on osu! are. NAT members always may. |
| `planner.wait-millis` | `25000` | How long the website waits for a change of the planner before it asks again. |
| `beatmaps.keep-old` | `true` | Keeps the beatmap collection of the previous version up to date, so that version can be started again. Off on AWS. |
| `discord.weekly-summary.enabled` | `false` | Posts a weekly summary of each mode to the public Discord channels of that mode: the beatmaps that went quiet (no change for 21 to 90 days, older ones are left out like on the website), the 5 waiting longest for a second nominator, and how many qualified and ranked in the last 7 days. Needs `discord.enabled`. Admins can see what it would post with `POST /v2/admin/weekly-summary/preview`. |
| `discord.weekly-summary.cron` | `"0 0 9 * * MON"` | When it posts, as a Spring cron in UTC (second, minute, hour, day of month, month, day of week). |

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

A version tag starts a release. The Release workflow builds and checks the backend and frontend once, and then each environment (now `beta`) waits for an approval: GitHub notifies you, and "Review deployments" on the run deploys exactly that build there.

```
git tag v2.0.0
git push origin v2.0.0
```

A change on master or in a pull request is only checked. Environments and their approvers are in the repository settings under Environments, how their AWS setup is created is in [infra/README.md](infra/README.md).

## Features Requests

Want something changed? Contact me on Discord at `greaper` or contact me directly on osu!.
