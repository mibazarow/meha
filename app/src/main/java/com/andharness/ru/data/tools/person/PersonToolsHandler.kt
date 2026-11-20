package com.andharness.ru.data.tools.person

import android.content.Context
import com.andharness.ru.data.message.MessageSendStore
import com.andharness.ru.data.message.MessageSender
import com.andharness.ru.data.person.MessagingIndexConfigStore
import com.andharness.ru.data.person.PersonBackfillStore
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.tools.ToolHandler

/**
 * The personal-base tools (update.md §4.4–§4.5): ingestion configuration plus the person/interaction
 * surface the agent uses instead of re-scanning the phone on every request. This class is a thin
 * coordinator that wires one [ToolHandler] per tool, in their original registration order.
 *
 * - `messaging_index_config` — opt-in, package list and backfill state of the incremental indexer;
 * - `person_find` — resolves «какой Серёга» through `PersonResolver` and **never guesses silently**:
 *   it either returns one clear candidate or lists the candidates and asks the user to pick;
 * - `person_get` — profile + identities + aliases + the most recent interactions;
 * - `person_link` / `person_merge` / `person_alias_set` — maintenance, all post-verified;
 * - `interaction_search` — metadata + gist search, newest first;
 * - `compose_message` / `schedule_message` — resolve first, then send over the chosen channel
 *   (update.md §4.7–§4.8), never to a guessed recipient.
 *
 * Honesty rules (implemented by the per-tool handlers):
 * - only metadata and a short gist are ever read or shown — message bodies are never stored/logged;
 * - an ambiguous resolution is reported as ambiguous, with the candidate list, instead of a pick;
 * - a handle already owned by another person is reported as a conflict, not silently re-linked;
 * - every mutation is re-read after the write and the tool fails loudly when the state did not change.
 */
class PersonToolsHandler(
    private val context: Context,
    private val configStore: MessagingIndexConfigStore,
    private val backfillStore: PersonBackfillStore,
    private val indexer: PersonIndexer,
    private val personRepository: PersonRepository,
    private val identityRepository: PersonIdentityRepository,
    private val aliasRepository: PersonAliasRepository,
    private val interactionRepository: InteractionRepository,
    private val currentChatProvider: CurrentChatProvider,
    private val messageSender: MessageSender,
    private val messageSendStore: MessageSendStore,
) {

    fun handlers(): List<ToolHandler> {
        val args = PersonToolArgs()
        val readSupport = PersonReadSupport(
            personRepository = personRepository,
            identityRepository = identityRepository,
            aliasRepository = aliasRepository,
            interactionRepository = interactionRepository,
        )
        val recipientResolver = PersonRecipientResolver(indexer, personRepository, readSupport)
        return listOf(
            MessagingIndexConfigToolHandler(context, configStore, backfillStore, indexer),
            MessagingIndexAppAddToolHandler(context, configStore, backfillStore, indexer),
            MessagingIndexAppRemoveToolHandler(context, configStore, backfillStore, indexer),
            PersonFindToolHandler(readSupport, args),
            PersonGetToolHandler(personRepository, readSupport, args),
            PersonLinkToolHandler(personRepository, identityRepository, args),
            PersonMergeToolHandler(personRepository, identityRepository, interactionRepository, args),
            PersonAliasSetToolHandler(personRepository, aliasRepository, args),
            InteractionSearchToolHandler(interactionRepository, personRepository, readSupport, args),
            ComposeMessageToolHandler(messageSender, recipientResolver, indexer, args),
            ScheduleMessageToolHandler(
                context,
                messageSendStore,
                recipientResolver,
                readSupport,
                currentChatProvider,
                args,
            ),
            ScheduleMessageListToolHandler(messageSendStore, readSupport),
            ScheduleMessageCancelToolHandler(context, messageSendStore, readSupport, args),
        )
    }
}
