import { withAuth } from "next-auth/middleware";
import { NextResponse } from "next/server";

export default withAuth(
  function middleware(req) {
    return NextResponse.next();
  },
  {
    callbacks: {
      // A token whose refresh failed is treated as no token at all, so the user lands on the
      // login page instead of on a working-looking app where every request 401s.
      authorized: ({ token }) => !!token && !token.error,
    },
    pages: {
      signIn: "/login",
    },
  }
);

export const config = {
  // /journal was missing here. Its page guards itself with getServerSession, so nothing leaked,
  // but it was the one route relying on that alone rather than on the matcher as well.
  matcher: [
    "/dashboard/:path*",
    "/notes/:path*",
    "/todos/:path*",
    "/chat/:path*",
    "/calendar/:path*",
    "/settings/:path*",
    "/meetings/:path*",
    "/journal/:path*",
  ],
};
