export interface Reorder<T> {
  pages: T[];
  /** The page the moved page now follows; null when it is first. */
  afterId: string | null;
}

/** The list after dragging `activeId` onto `overId`, and the anchor to send to the server. */
export function reorder<T extends { id: string }>(pages: T[], activeId: string, overId: string): Reorder<T> | null {
  const from = pages.findIndex((p) => p.id === activeId);
  const to = pages.findIndex((p) => p.id === overId);
  if (from < 0 || to < 0 || from === to) {
    return null;
  }
  const next = pages.slice();
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return { pages: next, afterId: to === 0 ? null : next[to - 1].id };
}
