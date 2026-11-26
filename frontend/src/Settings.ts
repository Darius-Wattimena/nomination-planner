const DEV_ENV: Settings = {
  api_url: "http://localhost:8080/"
};

const PROD_ENV: Settings = {
  api_url: "https://bnplannerbackend.greaper.net/"
};

interface Settings {
  api_url: string
}

const BACKEND_ENVIRONMENT =
  typeof window !== 'undefined' && window.location.host !== 'bnplanner.greaper.net'
          ? 'dev'
          : 'prod'

export const CONFIG = BACKEND_ENVIRONMENT === "prod" ? PROD_ENV : DEV_ENV;
