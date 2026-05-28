import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        background: "#1C1B1F",
        surface: "#2B2930",
        "surface-variant": "#49454F",
        primary: "#6750A4",
        "primary-container": "#EADDFF",
        "on-primary": "#FFFFFF",
        "on-surface": "#E6E1E5",
        "on-surface-variant": "#CAC4D0",
        outline: "#938F99",
        error: "#F2B8B5",
      },
    },
  },
  plugins: [],
};

export default config;
