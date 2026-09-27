export interface Notebook {
  id: string;
  kind: "scratchpad" | "notebook";
  title: string;
  position: string | null;
  page_count: number;
  last_edited_at: number | null;
  updated_at: number | null;
  rev: number | null;
}

export interface Page {
  id: string;
  notebook_id: string | null;
  position: string;
  ink_hash: string | null;
  ink_bytes: number;
  created_at: number;
  updated_at: number;
  rev: number;
  thumb_url: string | null;
  render_url: string | null;
}

export const SCRATCHPAD = "scratchpad";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** Server image paths (/web/...) as served through the Next.js proxy. */
export function proxied(serverPath: string | null): string | null {
  return serverPath ? serverPath.replace(/^\/web\//, "/api/zd/") : null;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      headers: init?.body ? { "content-type": "application/json" } : undefined,
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, "Can't reach the overview server. Check your connection.");
  }
  const body = (await res.json().catch(() => null)) as { error?: string; message?: string } | null;
  if (!res.ok) {
    const message =
      res.status === 401
        ? "Your device token was not accepted."
        : res.status === 502
          ? "The zetteldraw server is not responding."
          : (body?.message ?? `Request failed (${res.status}).`);
    throw new ApiError(res.status, message);
  }
  return body as T;
}

export const fetcher = <T,>(path: string) => request<T>(path);

export const api = {
  session: () => request<{ authenticated: boolean; shared?: boolean }>("/api/session"),
  signIn: (token: string) =>
    request<{ authenticated: boolean }>("/api/session", { method: "POST", body: JSON.stringify({ token }) }),
  signOut: () => request<{ authenticated: boolean }>("/api/session", { method: "DELETE" }),
  notebooksKey: "/api/zd/notebooks",
  pagesKey: (notebookId: string) => `/api/zd/notebooks/${notebookId}/pages`,
  movePage: (pageId: string, notebookId: string, afterId?: string | null) =>
    request<{ status: "moved" | "unchanged"; page: Page }>(`/api/zd/pages/${pageId}/move`, {
      method: "POST",
      body: JSON.stringify(afterId === undefined ? { notebook_id: notebookId } : { notebook_id: notebookId, after_id: afterId }),
    }),
  renameNotebook: (notebookId: string, title: string) =>
    request<{ notebook: { id: string; title: string } }>(`/api/zd/notebooks/${notebookId}`, {
      method: "PATCH",
      body: JSON.stringify({ title }),
    }),
};
