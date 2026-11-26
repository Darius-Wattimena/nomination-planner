const DEV_ENV: Settings = {
  api_url: "http://localhost:8080/",
  osu_id: 12978, // osu! OAuth client ID
  osu_redirect: "http://localhost:3000/login"
};

const PROD_ENV: Settings = {
  api_url: "https://bnplannerbackend.greaper.net/",
  osu_id: 14229, // osu! OAuth client ID
  osu_redirect: "https://bnplanner.greaper.net/login"
};

interface Settings {
  api_url: string,
  osu_id: number,
  osu_redirect: string
}

const BACKEND_ENVIRONMENT =
  typeof window !== 'undefined' && window.location.host !== 'bnplanner.greaper.net'
          ? 'dev'
          : 'prod'

export const CONFIG = BACKEND_ENVIRONMENT === "prod" ? PROD_ENV : DEV_ENV;
