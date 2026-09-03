"use client";

import { Button } from "../components/ui/button";
import { Card, CardDescription, CardTitle } from "../components/ui/card";

export default function GlobalError({
  error,
  reset
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="en">
      <body>
        <div className="flex min-h-screen items-center justify-center bg-ink-900 p-4">
          <Card className="w-full max-w-md">
            <CardTitle>Something went wrong</CardTitle>
            <CardDescription>{error.message || "An unexpected error occurred."}</CardDescription>
            <div className="mt-4">
              <Button onClick={reset}>Try again</Button>
            </div>
          </Card>
        </div>
      </body>
    </html>
  );
}
