import type { NextAuthOptions } from "next-auth";
import type { JWT } from "next-auth/jwt";
import GoogleProvider from "next-auth/providers/google";

/**
 * Exchanges the stored refresh token for a new access token.
 *
 * Returns the token with `error` set rather than throwing: a thrown error here would leave the
 * session in a state the UI cannot describe, whereas a flagged one lets it say plainly that
 * signing in again is needed. The old refresh token is kept when Google does not return a new
 * one, which is the usual case.
 */
async function refreshAccessToken(token: JWT): Promise<JWT> {
  if (!token.refreshToken) {
    return { ...token, error: "NoRefreshToken" };
  }
  try {
    const res = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: process.env.GOOGLE_CLIENT_ID!,
        client_secret: process.env.GOOGLE_CLIENT_SECRET!,
        grant_type: "refresh_token",
        refresh_token: token.refreshToken,
      }),
    });
    const refreshed = await res.json();
    if (!res.ok) throw new Error(refreshed.error ?? "Refresh failed");

    return {
      ...token,
      accessToken: refreshed.access_token,
      accessTokenExpires: Date.now() + (refreshed.expires_in ?? 3600) * 1000,
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      error: undefined,
    };
  } catch {
    return { ...token, error: "RefreshAccessTokenError" };
  }
}

export const authOptions: NextAuthOptions = {
  providers: [
    GoogleProvider({
      clientId: process.env.GOOGLE_CLIENT_ID!,
      clientSecret: process.env.GOOGLE_CLIENT_SECRET!,
      authorization: {
        params: {
          scope: [
            "openid",
            "email",
            "profile",
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/calendar",
          ].join(" "),
          prompt: "consent",
          access_type: "offline",
          response_type: "code",
        },
      },
    }),
  ],
  callbacks: {
    async jwt({ token, account }) {
      // Initial sign-in: keep the refresh token and the expiry alongside the access token.
      //
      // Only the access token used to be stored, and Google's lasts about an hour while this
      // JWT session lasts thirty days. So after an hour every Drive and Calendar call 401'd
      // while the middleware still saw a valid session — no redirect to sign in, just
      // "Failed to load notes" forever, with signing out by hand the only way back.
      if (account) {
        return {
          ...token,
          accessToken: account.access_token,
          refreshToken: account.refresh_token ?? token.refreshToken,
          accessTokenExpires: account.expires_at
            ? account.expires_at * 1000
            : Date.now() + 3600 * 1000,
          error: undefined,
        };
      }

      // Still valid — a minute of headroom, so a request that starts just before expiry does
      // not arrive just after it.
      if (
        token.accessTokenExpires &&
        Date.now() < token.accessTokenExpires - 60_000
      ) {
        return token;
      }

      return refreshAccessToken(token);
    },
    async session({ session, token }) {
      // Expose access token to server-side session
      session.accessToken = token.accessToken;
      // Surfaced rather than swallowed: the UI needs to be able to tell "signed out" from
      // "every request is failing for no visible reason".
      session.error = token.error;
      return session;
    },
  },
  pages: {
    signIn: "/login",
  },
  session: {
    strategy: "jwt",
  },
};
