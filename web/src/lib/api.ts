export interface Notebook {
  id: string;
  kind: "scratchpad" | "notebook";
  title: string;
  position: string | null;
  /** Null for Scratchpad and top-level notebooks. Absent on older servers. */
  parent_id?: string | null;
  page_count: number;
  last_edited_at: number | null;
  updated_at: number | null;
  rev: number | null;
}

export interface PageLink {
  id: string;
  direction: "out" | "in";
  other_id: string;
  other_notebook_id: string;
  other_notebook_title: string;
  other_index: number;
  other_count: number;
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
  /** Absent on older servers until /web pages enrichment ships. */
  links?: PageLink[];
}

export interface PagePlace {
  page_id: string;
  notebook_id: string;
  notebook_title: string;
  index: number;
  count: number;
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
        ? "Sign-in expired or was not accepted. Sign in again."
        : res.status === 502
          ? "The zetteldraw server is not responding."
          : (body?.message ?? `Request failed (${res.status}).`);
    throw new ApiError(res.status, message);
  }
  return body as T;
}

export const fetcher = <T,>(path: string) => request<T>(path);

export const api = {
  session: () =>
    request<{ authenticated: boolean; shared?: boolean; google?: boolean }>("/api/session"),
  signInWithGoogle: (idToken: string) =>
    request<{ authenticated: boolean }>("/api/session", {
      method: "POST",
      body: JSON.stringify({ id_token: idToken }),
    }),
  /** Legacy device-token sign-in (only when Google client id is unset). */
  signIn: (token: string) =>
    request<{ authenticated: boolean }>("/api/session", { method: "POST", body: JSON.stringify({ token }) }),
  signOut: () => request<{ authenticated: boolean }>("/api/session", { method: "DELETE" }),
  notebooksKey: "/api/zd/notebooks",
  pagesKey: (notebookId: string) => `/api/zd/notebooks/${notebookId}/pages`,
  placeKey: (pageId: string) => `/api/zd/pages/${pageId}/place`,
  backupStatusKey: "/api/zd/backup-status",
  writerKey: "/api/zd/writer",
  handOffWriter: () => request<WriterStatus>("/api/writer/hand-off", { method: "POST", body: "{}" }),
  cancelHandOff: () => request<WriterStatus>("/api/writer/cancel", { method: "POST", body: "{}" }),
};

export interface BackupStatus {
  ok: boolean;
  ready_for_device_restore: boolean;
  notebooks: number;
  boards_with_ink: number;
  logs_with_ink: number;
  missing: Array<{ table: "boards" | "notebook_logs"; id: string; ink_hash: string }>;
  note: string;
}

/** Live-writer (source of truth) lease for Play / new-device cutover. */
export interface WriterStatus {
  state: "live" | "handoff_open" | "none";
  device_id: string | null;
  device_label: string;
  claimed_at: number | null;
  last_seen_at: number | null;
  handoff_until: number | null;
  handoff_open: boolean;
  can_hand_off: boolean;
  backup_ready: boolean;
  note: string;
}

/** Android-style link label: "→ journal 3/12". */
export function pageLinkLabel(link: PageLink): string {
  const arrow = link.direction === "out" ? "→" : "←";
  return `${arrow} ${link.other_notebook_title} ${link.other_index}/${link.other_count}`;
}
