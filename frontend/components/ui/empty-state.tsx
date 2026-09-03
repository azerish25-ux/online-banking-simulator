import { Card, CardDescription, CardTitle } from "./card";

export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <Card className="py-10 text-center">
      <CardTitle>{title}</CardTitle>
      {description ? <CardDescription>{description}</CardDescription> : null}
    </Card>
  );
}
