import Link from "next/link";
import { Button } from "../components/ui/button";
import { Routes } from "../lib/routes";
import { Card, CardDescription, CardTitle } from "../components/ui/card";

export default function NotFound() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-workspace p-4">
      <Card className="w-full max-w-md text-center">
        <CardTitle>Page not found</CardTitle>
        <CardDescription>The page you are looking for does not exist.</CardDescription>
        <div className="mt-4">
          {/* The landing page is public - a 404 visitor who is not signed in
              would otherwise bounce off /dashboard straight to login. */}
          <Link href={Routes.home}>
            <Button>Back to the simulator</Button>
          </Link>
        </div>
      </Card>
    </div>
  );
}
