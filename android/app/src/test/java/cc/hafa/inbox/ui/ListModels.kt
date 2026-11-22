package cc.hafa.inbox.ui

import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.core.mail.MailModel
import cc.hafa.inbox.core.mail.Row
import cc.hafa.inbox.core.mail.SectionModel
import cc.hafa.inbox.core.mail.listLayout
import cc.hafa.inbox.core.mail.listStep
import cc.hafa.inbox.core.todo.Todo

/** The model with its list laid out from [sections], as the app's state does whenever they change. */
internal fun MailModel.listing(sections: List<SectionModel> = sections()): MailModel =
    step(ListEvent.Layout(listLayout(sections, ::bundleHasMore, ::bundleLoading)))

/** The model after its list takes [event]. */
internal fun MailModel.step(event: ListEvent<Row, Todo>): MailModel = copy(list = listStep(list, event))
