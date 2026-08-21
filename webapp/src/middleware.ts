import { withAuth } from "next-auth/middleware";
import { NextResponse } from "next/server";

export default withAuth(
  function middleware(req) {
    return NextResponse.next();
  },
  {
    callbacks: {
      authorized: ({ token }) => !!token,
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
