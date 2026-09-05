import type { components } from "./api-gen";

type Schemas = components["schemas"];

// The generator marks every field optional (the spec lacks `required`).
// Our API always returns these fields, so Required<> keeps one source of
// truth without non-null assertions at every call site.
export type Account = Required<Schemas["AccountResponse"]>;
export type Tx = Required<Schemas["TransactionResponse"]>;
export type Beneficiary = Required<Schemas["BeneficiaryResponse"]>;
export type NotificationItem = Required<Schemas["NotificationResponse"]>;
export type CardItem = Required<Schemas["CardResponse"]>;
export type IssuedCard = Required<Schemas["IssuedCardResponse"]>;
export type AdminUser = Required<Schemas["UserResponse"]>;
export type Audit = Required<Schemas["AuditResponse"]>;
export type MonthPoint = Required<Schemas["MonthSummary"]>;
export type DayTotal = Required<Schemas["DayTotal"]>;
export type User = Required<Schemas["UserResponse"]>;
export type AuthResponse = Omit<Required<Schemas["AuthResponse"]>, "user"> & { user: User };

// Spring Data page envelope (framework-stable shape, not domain drift).
export type Page<T> = {
  content: T[];
  totalPages: number;
  number: number;
  totalElements: number;
};

// Public landing numbers - intentionally outside the OpenAPI spec (marketing
// data, not part of the authenticated banking contract).
export type PublicStats = { users: number; accounts: number; transfers: number; volume: string };
