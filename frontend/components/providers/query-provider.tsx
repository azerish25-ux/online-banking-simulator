"use client";

import { QueryClient, QueryClientProvider, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import * as React from "react";
import { SESSION_EXPIRED_EVENT } from "../../lib/api";
import { Routes } from "../../lib/routes";

/**
 * Shared client: stale-while-revalidate defaults tuned for banking reads.
 * Also the single home for session-expiry handling: when the api() client
 * broadcasts a hard 401 (refresh failed), drop every cached query and send
 * the user to the login page instead of leaving half-broken screens up.
 */
function SessionExpiryListener() {
  const router = useRouter();
  const queryClient = useQueryClient();
  React.useEffect(() => {
    function onExpired() {
      queryClient.clear();
      router.push(Routes.login);
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, [queryClient, router]);
  return null;
}

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [client] = React.useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            gcTime: 5 * 60_000,
            refetchOnWindowFocus: false,
            retry: 1
          }
        }
      })
  );
  return (
    <QueryClientProvider client={client}>
      <SessionExpiryListener />
      {children}
    </QueryClientProvider>
  );
}
