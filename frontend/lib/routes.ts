/** Central route table. Middleware matcher strings stay literal (they need
 *  `:path*` patterns); everything else imports from here. */
export const Routes = {
  home: "/",
  login: "/login",
  loginMfa: "/login/mfa",
  register: "/register",
  dashboard: "/dashboard",
  transfers: "/transfers",
  activity: "/activity",
  beneficiaries: "/beneficiaries",
  notifications: "/notifications",
  settings: "/settings",
  design: "/design",
  about: "/about",
  admin: "/admin",
  account: (id: string) => "/accounts/" + id
} as const;
