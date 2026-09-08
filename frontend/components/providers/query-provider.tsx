"use client";

import { QueryClient, QueryClientProvider, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import * as React from "react";
import { SESSION_EXPIRED_EVENT, subscribeAuthChannel } from "../../lib/api";
import { Routes } from "../../lib/routes";

/**
 * Shared client: stale-while-revalidate defaults tuned for banking reads.
 * Also the single home for session-end handling: when the api() client
 * broadcasts a hard 401 (refresh failed) OR a sibling tab logs out or hits
 * the same expiry (the browser cookie jar is shared, so the browser cookie jar is shared, so one tab's
 * session end invalidates every tab's cached data), drop every cached query
 * and send this tab to the login page instead of leaving half-broken or
 * stale screens up behind it.
 */
function SessionExpiryListener() {
  const router = useRouter();
  const queryClient = useQueryClient();
  React.useEffect(() => {
    function evictAndRoute() {
      queryClient.clear();
      router.push(Routes.login);
    }
    function onExpired() {
      evictAndRoute();
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    // Peer tabs announce logout/expiry over the auth channel; the local
    // SESSION_EXPIRED_EVENT covers the tab that detected the problem itself.
    const unsubscribe = subscribeAuthChannel((message) => {
      if (message.type === "expired" || message.type === "logout") {
        evictAndRoute();
      }
    });
    return () => {
      window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
      unsubscribe();
    };
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
