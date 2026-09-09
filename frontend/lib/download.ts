import { authedFetch } from "./api";

/**
 * Save an authenticated response as a file download. Uses authedFetch so
 * exports go through the same silent-refresh path as every other request: * a download after token expiry must not silently 401. Prefers the
 * server's Content-Disposition filename (statement-IBAN-date.csv) over the
 * caller's fallback.
 */
function filenameFromDisposition(res: Response, fallback: string): string {
  const header = res.headers.get("Content-Disposition") ?? "";
  const match = /filename="?([^";]+)"?/.exec(header);
  return (match && match[1]) || fallback;
}

export async function downloadAuthed(url: string, fallbackName: string): Promise<void> {
  const res = await authedFetch(url);
  const blob = await res.blob();
  const name = filenameFromDisposition(res, fallbackName);
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = objectUrl;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(objectUrl);
}
