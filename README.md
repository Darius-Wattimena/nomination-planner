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

## Features Requests

Want something changed? Contact me on Discord at `greaper` or contact me directly on osu!.
