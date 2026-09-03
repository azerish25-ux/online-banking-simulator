import { Skeleton } from "../components/ui/skeleton";

export default function GlobalLoading() {
  return (
    <div className="mx-auto max-w-6xl space-y-3 p-5">
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-32 w-full" />
      <Skeleton className="h-32 w-full" />
    </div>
  );
}
