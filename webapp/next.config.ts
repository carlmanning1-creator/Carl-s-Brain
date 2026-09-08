import type { NextConfig } from "next";

/**
 * Security headers.
 *
 * There were none. This app holds a Google OAuth token with full `drive` scope, and the whole
 * of `lib/driveGuards.ts` exists because of it — its header names XSS and CSRF as the threat
 * model in as many words. Without a CSP, one injected script has that token's reach; without
 * X-Frame-Options, the app can be framed and clickjacked into the actions those guards protect.
 *
 * The CSP is written to what the app actually loads today:
 *  - 'unsafe-inline' and 'unsafe-eval' in script-src are required by Next.js's own runtime;
 *    tightening those needs a nonce-based setup and is a separate piece of work.
 *  - connect-src covers the APIs the browser talks to directly.
 *  - frame-ancestors 'none' is the modern form of X-Frame-Options, which is kept alongside it
 *    for older browsers.
 */
const securityHeaders = [
  {
    key: "Content-Security-Policy",
    value: [
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob: https://lh3.googleusercontent.com",
      "font-src 'self' data:",
      "media-src 'self' blob:",
      "connect-src 'self' https://www.googleapis.com https://api.anthropic.com https://api.openai.com",
      "frame-ancestors 'none'",
      "base-uri 'self'",
      "form-action 'self'",
      "object-src 'none'",
    ].join("; "),
  },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  // Full URLs of this app are not sent to other origins; the path can name an entity id.
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  // Nothing here uses the camera, the microphone is used by the recorder on this origin only.
  { key: "Permissions-Policy", value: "camera=(), geolocation=(), microphone=(self)" },
];

const nextConfig: NextConfig = {
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
