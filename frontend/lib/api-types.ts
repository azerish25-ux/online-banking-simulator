import type { components } from "./api-gen";

type Schemas = components["schemas"];

// The OpenAPI schema is authoritative: the backend marks every response
// field `required` (and the genuinely nullable ones `nullable`), so the
// generated types need no blanket Required<> repair. These aliases only give
// the schemas shorter, domain-flavoured names.
export type Account = Schemas["AccountResponse"];
export type Tx = Schemas["TransactionResponse"];
export type Deposit = Schemas["DepositResponse"];
export type OperationListItem = Schemas["OperationListItem"];
export type OperationList = Schemas["OperationListResponse"];
export type Beneficiary = Schemas["BeneficiaryResponse"];
export type NotificationItem = Schemas["NotificationResponse"];
export type CardItem = Schemas["CardResponse"];
export type IssuedCard = Schemas["IssuedCardResponse"];
export type AdminUser = Schemas["UserResponse"];
export type Audit = Schemas["AuditResponse"];
export type MonthPoint = Schemas["MonthSummary"];
export type DayTotal = Schemas["DayTotal"];
export type User = Schemas["UserResponse"];
export type AuthResponse = Schemas["AuthResponse"];
export type MfaRequiredResponse = Schemas["MfaRequiredResponse"];

// Spring Data page envelope (framework-stable shape, not domain drift).
export type Page<T> = {
  content: T[];
  totalPages: number;
  number: number;
  totalElements: number;
};

// Cursor-paged history envelope: the transactions feed pages by an
// opaque keyset cursor, not by page numbers. nextCursor is null on the last
// page; omit/blank the cursor to restart at the newest page.
export type HistoryPage<T> = {
  items: T[];
  total: number;
  nextCursor: string | null;
};

// Public landing numbers - intentionally outside the OpenAPI spec (marketing
// data, not part of the authenticated banking contract).
export type PublicStats = { users: number; accounts: number; transfers: number; volume: string };
