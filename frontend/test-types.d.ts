// Vitest component tests rely on jest-dom matchers (toBeInTheDocument & co),
// which the vitest.setup.mjs imports at runtime. This import makes the same
// augmentation visible to tsc so `npm run build`'s type check and a plain
// `tsc --noEmit` agree with what the tests actually use.
import "@testing-library/jest-dom/vitest";
