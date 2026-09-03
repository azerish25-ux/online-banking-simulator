import Link from "next/link";
import { Button } from "../components/ui/button";
import { Routes } from "../lib/routes";
import { Card, CardDescription, CardTitle } from "../components/ui/card";

export default function NotFound() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-ink-900 p-4">
      <Card className="w-full max-w-md text-center">
        <CardTitle>Page not found</CardTitle>
        <CardDescription>The page you are looking for does not exist.</CardDescription>
        <div className="mt-4">
          <Link href={Routes.dashboard}>
            <Button>Back to dashboard</Button>
          </Link>
        </div>
      </Card>
    </div>
  );
}
