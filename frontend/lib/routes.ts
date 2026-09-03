/** Central route table. Middleware matcher strings stay literal (they need
 *  `:path*` patterns); everything else imports from here. */
export const Routes = {
  home: "/",
  login: "/login",
  register: "/register",
  dashboard: "/dashboard",
  transfers: "/transfers",
  activity: "/activity",
  beneficiaries: "/beneficiaries",
  notifications: "/notifications",
  design: "/design",
  admin: "/admin",
  account: (id: string) => "/accounts/" + id
} as const;
