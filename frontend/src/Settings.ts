interface Settings {
  api_url: string
}

// The backend is on the same address as the website, CloudFront passes its paths on.
// Only while developing it runs apart.
export const CONFIG: Settings = {
  api_url: import.meta.env.DEV ? "http://localhost:8080/" : "/"
};
