# Nomination Planner

The Nomination Planner is a web application designed to help Beatmap Nominators plan and manage their nominations.

- `backend/`: Kotlin, Spring Boot and MongoDB
- `frontend/`: React and Vite

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
