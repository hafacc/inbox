// The shared list model (@inbox/shared/list-model) over this app's thread summaries.

import type {
  Row as ListRow,
  SectionEntry as ListSectionEntry,
  SectionModel as ListSectionModel,
} from "@inbox/shared/list-model";
import type { ThreadSummary } from "./thread-summary";

/** A thread as a list shows it: its summary, snooze, and the date it sorts by. */
export type Row = ListRow<ThreadSummary>;

/** A list entry: a thread, a label group, or a todo. */
export type SectionEntry = ListSectionEntry<ThreadSummary>;

/** One section of a list, ready to render. */
export type SectionModel = ListSectionModel<ThreadSummary>;
