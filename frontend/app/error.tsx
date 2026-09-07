"use client";

import { Button } from "../components/ui/button";
import { Card, CardDescription, CardTitle } from "../components/ui/card";

// The error object carries server internals in development; it is deliberately
// never rendered, so nothing implementation-specific leaks to users.
export default function GlobalError({
  reset
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="en">
      <body>
        <div className="flex min-h-screen items-center justify-center bg-workspace p-4">
          <Card className="w-full max-w-md">
            <CardTitle>Something went wrong</CardTitle>
            <CardDescription>
              The request could not be completed. Try again, and if it keeps
              failing, log in once more.
            </CardDescription>
            <div className="mt-4">
              <Button onClick={reset}>Try again</Button>
            </div>
          </Card>
        </div>
      </body>
    </html>
  );
}
