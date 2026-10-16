package app.mini.hub.persistence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.observation.ConversationCoverage
import app.mini.hub.observation.ConversationObservationSummary
import app.mini.hub.observation.CommunicationKind
import app.mini.hub.observation.CommunicationSource
import app.mini.hub.observation.CorrelationConfidence
import app.mini.hub.observation.IngestCursor
import app.mini.hub.observation.NotificationCategory
import app.mini.hub.observation.NotificationRawPayload
import app.mini.hub.observation.ObservationProvenance
import app.mini.hub.observation.ObservedNotification
import app.mini.hub.observation.SourceAppNames
import app.mini.hub.observation.TruncationState
import app.mini.hub.observation.categoryForStoredObservation
import app.mini.hub.observation.classifyCommunication
import app.mini.hub.observation.coverageExplanationFor
import app.mini.hub.observation.coverageFor
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * Unencrypted sqlite-jdbc observation store for the JVM target. It runs the same SQL schema and
 * migrations as the Android SQLCipher store ([ObservationSchema]) so host tests exercise real SQL:
 * ordering, migrations and (later) full-text search. Test-only; never shipped.
 */
class JdbcObservationStore(path: String) : ObservationStore {
    override val qualification: StorageQualification = StorageQualification.InMemoryTestOnly
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        connection.createStatement().use { s ->
            s.execute(ObservationSchema.CREATE_OBSERVATIONS_V1)
            s.execute(ObservationSchema.CREATE_CONVERSATIONS_V1)
            s.execute(ObservationSchema.CREATE_CURSORS_V1)
            s.execute(ObservationSchema.INDEX_NOTIFICATION_KEY)
            runCatching {
                s.execute(ObservationSchema.CREATE_FTS_OBSERVATIONS)
                s.execute(ObservationSchema.POPULATE_FTS_FROM_EXISTING)
            }
        }
        migrate()
    }

    fun schemaVersion(): Int = connection.createStatement().use { s ->
        s.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 }
    }

    private fun migrate() {
        val current = schemaVersion()
        if (current >= ObservationSchema.VERSION) {
            // Fresh databases still need FTS population if missing.
            ensureVersion3Columns()
            ensureVersion4Columns()
            ensureVersion5Columns()
            return
        }
        transaction {
            if (current < 3) ensureVersion3Columns()
            if (current < 4) ensureVersion4Columns()
            if (current < 5) ensureVersion5Columns()
            connection.createStatement().use { it.execute("PRAGMA user_version = ${ObservationSchema.VERSION}") }
        }
    }

    private fun ensureVersion5Columns() {
        connection.createStatement().use { s ->
            // Version 5: Rebuild FTS with Unicode tokenization and idempotent population
            s.execute("DROP TABLE IF EXISTS obs_fts")
            s.execute(ObservationSchema.CREATE_FTS_OBSERVATIONS)
            s.execute(ObservationSchema.POPULATE_FTS_FROM_EXISTING)
        }
    }

    private fun observationColumns(): Set<String> {
        val columns = mutableSetOf<String>()
        connection.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(notification_observations)").use { while (it.next()) columns += it.getString("name") }
        }
        return columns
    }

    private fun ensureVersion3Columns() {
        connection.createStatement().use { s -> ObservationSchema.version3Statements(observationColumns()).forEach { s.execute(it) } }
    }

    private fun ensureVersion4Columns() {
        connection.createStatement().use { s -> ObservationSchema.version4Statements(observationColumns()).forEach { s.execute(it) } }
    }

    private inline fun <T> transaction(block: () -> T): T {
        val previous = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = previous
        }
    }

    @Synchronized
    override fun persistObservation(observation: ObservedNotification): PersistResult = try {
        transaction { writeRow(observation) }
        PersistResult.Success(observation.id, observation.revision)
    } catch (e: Throwable) {
        PersistResult.Error(e.message ?: "Database error")
    }

    @Synchronized
    override fun persistBatch(observations: List<ObservedNotification>): Int {
        if (observations.isEmpty()) return 0
        transaction {
            observations.forEach { insertObservationRowOnly(it) }
            // Update summaries once per touched conversation
            observations.map { it.conversationId.key }.distinct().forEach { convKey ->
                val sample = observations.last { it.conversationId.key == convKey }
                updateConversationSummary(sample)
            }
        }
        return observations.size
    }

    private fun insertObservationRowOnly(obs: ObservedNotification) {
        prepare(
            """INSERT OR REPLACE INTO notification_observations (id, account_key, conversation_key, package_id, notification_key,
                notification_id, tag, sender_hint, title, text_fragments_json, raw_text_digest, observed_at, post_time, revision,
                is_removed, is_group_summary, truncation_state, correlation_confidence, provenance, direction, recipients_json, reply_anchor, notification_category)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            obs.id.key, obs.accountId.key, obs.conversationId.key, obs.packageId, obs.notificationKey,
            obs.notificationId, obs.tag, obs.senderHint, obs.title, obs.textFragments.joinToString("\u001F"), obs.rawTextDigest,
            obs.observedAt.epochMillis, obs.postTime.epochMillis, obs.revision, if (obs.isRemoved) 1 else 0,
            if (obs.isGroupSummary) 1 else 0, obs.truncationState.name, obs.correlationConfidence.name, obs.provenance.name,
            ObservationSchema.encodeDirection(obs.direction), ObservationSchema.encodeRecipients(obs.recipients), obs.replyAnchor,
            ObservationSchema.encodeCategory(obs.notificationCategory ?: categoryForStoredObservation(obs)),
        ).use { it.executeUpdate() }

        runCatching {
            prepare("DELETE FROM obs_fts WHERE obs_id = ?", obs.id.key).use { it.executeUpdate() }
            if (!obs.isRemoved) {
                prepare(
                    "INSERT INTO obs_fts(obs_id, conversation_key, account_key, package_id, title, body) VALUES (?,?,?,?,?,?)",
                    obs.id.key, obs.conversationId.key, obs.accountId.key, obs.packageId,
                    obs.title ?: "", obs.textFragments.joinToString(" "),
                ).use { it.executeUpdate() }
            }
        }
    }

    private fun updateConversationSummary(obs: ObservedNotification) {
        val conv = obs.conversationId.key
        prepare(
            """INSERT OR REPLACE INTO conversation_summaries (conversation_key, account_key, title, app_name, sender_hint, package_id,
                latest_snippet, category, coverage, coverage_explanation, latest_event_at, observation_count, unread_count, is_removed)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            conv, obs.accountId.key, obs.title ?: obs.senderHint, SourceAppNames.forPackage(obs.packageId), obs.senderHint, obs.packageId,
            obs.textFragments.lastOrNull() ?: obs.title ?: "", categoryOf(obs).name, coverageFor(obs.provenance).name,
            coverageExplanationFor(obs.provenance, getCursor(obs.packageId)?.gapCount ?: 0L), obs.observedAt.epochMillis,
            count("SELECT COUNT(*) FROM notification_observations WHERE conversation_key = ?", conv),
            count("SELECT COUNT(*) FROM notification_observations WHERE conversation_key = ? AND is_removed = 0", conv),
            if (obs.isRemoved) 1 else 0,
        ).use { it.executeUpdate() }
    }

    @Synchronized
    override fun persistBatchWithCursors(observations: List<ObservedNotification>, cursors: List<IngestCursor>): BatchPersistResult = try {
        transaction {
            observations.forEach { writeRow(it) }
            cursors.forEach { writeCursor(it) }
        }
        BatchPersistResult(observations.size, 0, emptyList())
    } catch (e: Throwable) {
        BatchPersistResult(0, observations.size, listOf(e.message ?: "Database error"))
    }

    @Synchronized
    override fun persistAtomicBatch(
        removals: List<Pair<String, UtcInstant>>,
        observations: List<ObservedNotification>,
        cursors: List<IngestCursor>,
    ): BatchPersistResult = try {
        transaction {
            observations.forEach { writeRow(it) }
            removals.forEach { (key, _) -> removeRows(key) }
            cursors.forEach { writeCursor(it) }
        }
        BatchPersistResult(removals.size + observations.size, 0, emptyList())
    } catch (e: Throwable) {
        BatchPersistResult(0, removals.size + observations.size, listOf(e.message ?: "Database error"))
    }

    @Synchronized
    override fun persistObservationWithCursor(observation: ObservedNotification, cursor: IngestCursor): PersistResult = try {
        transaction { writeRow(observation); writeCursor(cursor) }
        PersistResult.Success(observation.id, observation.revision)
    } catch (e: Throwable) {
        PersistResult.Error(e.message ?: "Database error")
    }

    @Synchronized
    override fun getObservation(id: ScopedId): ObservedNotification? =
        queryOne("SELECT * FROM notification_observations WHERE id = ?", id.key)

    @Synchronized
    override fun getObservationsForConversation(conversationId: ScopedId, limit: Int): List<ObservedNotification> =
        query(
            "SELECT * FROM notification_observations WHERE conversation_key = ? ORDER BY ${ObservationSchema.TIMELINE_ORDER} LIMIT ?",
            conversationId.key, limit,
        )

    @Synchronized
    override fun getObservationByNotificationKey(notificationKey: String): ObservedNotification? =
        queryOne("SELECT * FROM notification_observations WHERE notification_key = ? AND is_removed = 0 ORDER BY observed_at DESC LIMIT 1", notificationKey)

    @Synchronized
    override fun getObservationByNotificationKeyIncludingRemoved(notificationKey: String): ObservedNotification? =
        queryOne("SELECT * FROM notification_observations WHERE notification_key = ? ORDER BY observed_at DESC LIMIT 1", notificationKey)

    @Synchronized
    override fun getObservationByNotificationIdIncludingRemoved(packageId: String, notificationId: Int): ObservedNotification? =
        queryOne("SELECT * FROM notification_observations WHERE package_id = ? AND notification_id = ? ORDER BY observed_at DESC LIMIT 1", packageId, notificationId)

    @Synchronized
    override fun getConversationSummaries(accountId: AccountId?): List<ConversationObservationSummary> {
        val sql = "SELECT * FROM conversation_summaries" + (if (accountId != null) " WHERE account_key = ?" else "") + " ORDER BY latest_event_at DESC"
        return prepare(sql, *listOfNotNull(accountId?.key).toTypedArray()).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val pkg = rs.getString("package_id")
                        val cat = NotificationCategory.valueOf(rs.getString("category"))
                        val title = rs.getString("title")
                        val snippet = rs.getString("latest_snippet")
                        add(
                            ConversationObservationSummary(
                                conversationId = ScopedId.parse(rs.getString("conversation_key")),
                                accountId = AccountId.parse(rs.getString("account_key")),
                                title = title,
                                appName = rs.getString("app_name"),
                                senderHint = rs.getString("sender_hint"),
                                packageId = pkg,
                                latestSnippet = snippet,
                                category = cat,
                                coverage = ConversationCoverage.valueOf(rs.getString("coverage")),
                                coverageExplanation = rs.getString("coverage_explanation"),
                                latestEventAt = UtcInstant(rs.getLong("latest_event_at")),
                                observationCount = rs.getInt("observation_count"),
                                unreadCount = rs.getInt("unread_count"),
                                isRemoved = rs.getInt("is_removed") != 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    override fun markRemoved(notificationKey: String, removedAt: UtcInstant): Int = transaction { removeRows(notificationKey) }

    @Synchronized
    override fun checkpointCursor(cursor: IngestCursor) = transaction { writeCursor(cursor) }

    @Synchronized
    override fun getCursor(source: String): IngestCursor? =
        prepare("SELECT * FROM ingest_cursors WHERE source = ?", source).use { ps ->
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else IngestCursor(
                    source = rs.getString("source"),
                    lastProcessedTimeMillis = rs.getLong("last_processed_time_millis"),
                    lastProcessedKey = rs.getString("last_processed_key"),
                    processedCount = rs.getLong("processed_count"),
                    gapCount = rs.getLong("gap_count"),
                )
            }
        }

    @Synchronized
    override fun recoverFromRestart(): RestartRecoveryReport {
        val totalObs = count("SELECT COUNT(*) FROM notification_observations")
        val totalConv = count("SELECT COUNT(*) FROM conversation_summaries")

        // Ensure FTS is correctly populated on restart without duplicates.
        // Dismissed notifications (is_removed=1 but key does NOT start with 'provider:') stay searchable.
        // Provider-deleted messages (key starts with 'provider:') are removed from FTS.
        transaction {
            connection.createStatement().use { s ->
                s.execute("DELETE FROM obs_fts WHERE obs_id NOT IN (SELECT id FROM notification_observations WHERE is_removed = 0 OR (is_removed = 1 AND notification_key NOT LIKE 'provider:%'))")
                s.execute(ObservationSchema.POPULATE_FTS_FROM_EXISTING)
            }
        }

        return RestartRecoveryReport(
            totalObservations = totalObs,
            totalConversations = totalConv,
            lastCursor = null,
        )
    }

    @Synchronized
    override fun search(query: app.mini.hub.search.SearchQuery): List<app.mini.hub.search.SearchResult> {
        val q = query.queryText.trim()
        if (q.isBlank()) return emptyList()

        // Clean user query for FTS5: split words, append wildcard to each.
        // Use a broader split that preserves Unicode word characters.
        val ftsQuery = q.split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"$it\"*" }

        if (ftsQuery.isBlank()) return emptyList()

        val filterClause = if (query.sourceFilter != null) " AND o.package_id = ?" else ""
        val args = if (query.sourceFilter != null) arrayOf(ftsQuery, query.sourceFilter, query.limit) else arrayOf(ftsQuery, query.limit)

        // Find matches and then resolve eligibility using the shared policy.
        // Dismissed notifications (is_removed=1) stay in FTS and are searchable if they are communications.
        // Provider-deleted messages are removed from FTS and stay hidden.
        val sql = """
            SELECT o.conversation_key, o.account_key, o.package_id,
                   coalesce(s.title, o.title, o.sender_hint) AS conv_title,
                   o.sender_hint,
                   o.title AS obs_title,
                   substr(f.body, 1, 120) AS match_snippet,
                   o.post_time,
                   coalesce(s.observation_count, 1) AS msg_count,
                   o.notification_category,
                   o.is_group_summary,
                   o.id AS obs_id
            FROM obs_fts f
            JOIN notification_observations o ON o.id = f.obs_id
            LEFT JOIN conversation_summaries s ON s.conversation_key = o.conversation_key
            WHERE obs_fts MATCH ? $filterClause
            ORDER BY o.post_time DESC
        """.trimIndent()

        return try {
            prepare(sql, *args.dropLast(1).toTypedArray()).use { ps ->
                ps.executeQuery().use { rs ->
                    val results = mutableListOf<app.mini.hub.search.SearchResult>()
                    val seenConversations = mutableSetOf<String>()

                    while (rs.next() && results.size < query.limit) {
                        val convKey = rs.getString("conversation_key")
                        if (convKey in seenConversations) continue

                        // Apply eligibility: only DirectMessage/GroupMessage are inbox-eligible.
                        // NULL category means legacy data that needs re-classification.
                        val cat = ObservationSchema.decodeCategory(rs.getString("notification_category"))
                        if (cat == NotificationCategory.GeneralNotification ||
                            cat == NotificationCategory.SystemStatus ||
                            cat == NotificationCategory.OngoingService ||
                            cat == NotificationCategory.SocialUpdate
                        ) {
                           continue
                        }
                        // NULL: must re-classify. If not a conversation message, skip.
                        if (cat == null) {
                            val pkg = rs.getString("package_id")
                            val title = rs.getString("obs_title") ?: rs.getString("conv_title") ?: ""
                            val snippet = rs.getString("match_snippet") ?: ""
                            val probe = NotificationRawPayload(
                                key = "legacy-search-probe",
                                packageId = pkg,
                                notificationId = 0,
                                senderHint = title,
                                title = title,
                                text = snippet,
                                postTimeMillis = 0L,
                            )
                            if (classifyCommunication(probe) != CommunicationKind.ConversationMessage) continue
                        }

                        // Social-platform rows can be stored with a DirectMessage category even
                        // when the notification is a recommendation or activity update.
                        if (cat == NotificationCategory.DirectMessage || cat == NotificationCategory.GroupMessage) {
                            val source = CommunicationSource.forExactPackage(rs.getString("package_id"))
                            if (source == CommunicationSource.Instagram ||
                                source == CommunicationSource.FacebookMessenger ||
                                source == CommunicationSource.X
                            ) {
                                val payload = NotificationRawPayload(
                                    key = "search-probe",
                                    packageId = rs.getString("package_id"),
                                    notificationId = 0,
                                    senderHint = rs.getString("sender_hint") ?: "",
                                    title = rs.getString("obs_title") ?: rs.getString("conv_title") ?: "",
                                    text = rs.getString("match_snippet") ?: "",
                                    postTimeMillis = rs.getLong("post_time"),
                                )
                                if (classifyCommunication(payload) != CommunicationKind.ConversationMessage) continue
                            }
                        }

                        seenConversations.add(convKey)
                        results.add(
                            app.mini.hub.search.SearchResult(
                                conversationId = parseConversationId(convKey, rs.getString("account_key")),
                                accountId = AccountId.parse(rs.getString("account_key")),
                                packageId = rs.getString("package_id"),
                                conversationTitle = rs.getString("conv_title") ?: "",
                                senderHint = rs.getString("sender_hint") ?: "",
                                snippet = rs.getString("match_snippet") ?: "",
                                eventAt = UtcInstant(rs.getLong("post_time")),
                                messageCount = rs.getInt("msg_count"),
                            )
                        )
                    }
                    results
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Exposed for future same-database features (search index) and tests. */
    internal fun connection(): Connection = connection

    private fun parseConversationId(rawKey: String, accountKey: String): ScopedId =
        runCatching { ScopedId.parse(rawKey) }.getOrElse {
            val legacyValue = rawKey.substringAfter(":conversation:", rawKey.substringAfterLast(':'))
            ScopedId(AccountId.parse(accountKey), ScopedId.Kind.CONVERSATION, legacyValue)
        }

    private fun writeRow(obs: ObservedNotification) {
        prepare(
            """INSERT OR REPLACE INTO notification_observations (id, account_key, conversation_key, package_id, notification_key,
                notification_id, tag, sender_hint, title, text_fragments_json, raw_text_digest, observed_at, post_time, revision,
                is_removed, is_group_summary, truncation_state, correlation_confidence, provenance, direction, recipients_json, reply_anchor, notification_category)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            obs.id.key, obs.accountId.key, obs.conversationId.key, obs.packageId, obs.notificationKey,
            obs.notificationId, obs.tag, obs.senderHint, obs.title, obs.textFragments.joinToString("\u001F"), obs.rawTextDigest,
            obs.observedAt.epochMillis, obs.postTime.epochMillis, obs.revision, if (obs.isRemoved) 1 else 0,
            if (obs.isGroupSummary) 1 else 0, obs.truncationState.name, obs.correlationConfidence.name, obs.provenance.name,
            ObservationSchema.encodeDirection(obs.direction), ObservationSchema.encodeRecipients(obs.recipients), obs.replyAnchor,
            ObservationSchema.encodeCategory(obs.notificationCategory ?: categoryForStoredObservation(obs)),
        ).use { it.executeUpdate() }

        // Keep FTS index in sync.
        // Logic: inserts/updates add to FTS if not removed.
        // Removals: if provider-deleted, remove from FTS. If dismissed, keep in FTS.
        runCatching {
            prepare("DELETE FROM obs_fts WHERE obs_id = ?", obs.id.key).use { it.executeUpdate() }
            val shouldBeInFts = !obs.isRemoved || (!obs.notificationKey.startsWith("provider:") && obs.isRemoved)
            if (shouldBeInFts) {
                prepare(
                    "INSERT INTO obs_fts(obs_id, conversation_key, account_key, package_id, title, body) VALUES (?,?,?,?,?,?)",
                    obs.id.key, obs.conversationId.key, obs.accountId.key, obs.packageId,
                    obs.title ?: "", obs.textFragments.joinToString(" "),
                ).use { it.executeUpdate() }
            }
        }

        val conv = obs.conversationId.key
        prepare(
            """INSERT OR REPLACE INTO conversation_summaries (conversation_key, account_key, title, app_name, sender_hint, package_id,
                latest_snippet, category, coverage, coverage_explanation, latest_event_at, observation_count, unread_count, is_removed)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            conv, obs.accountId.key, obs.title ?: obs.senderHint, SourceAppNames.forPackage(obs.packageId), obs.senderHint, obs.packageId,
            obs.textFragments.lastOrNull() ?: obs.title ?: "", categoryOf(obs).name, coverageFor(obs.provenance).name,
            coverageExplanationFor(obs.provenance, getCursor(obs.packageId)?.gapCount ?: 0L), obs.observedAt.epochMillis,
            count("SELECT COUNT(*) FROM notification_observations WHERE conversation_key = ?", conv),
            count("SELECT COUNT(*) FROM notification_observations WHERE conversation_key = ? AND is_removed = 0", conv),
            if (obs.isRemoved) 1 else 0,
        ).use { it.executeUpdate() }
    }

    private fun removeRows(notificationKey: String): Int {
        // Dismissal from Android notification listener: keep in FTS for history search.
        // Only API-deleted or user-deleted items should be removed from FTS.
        if (notificationKey.startsWith("provider:")) {
            runCatching {
                prepare("DELETE FROM obs_fts WHERE obs_id IN (SELECT id FROM notification_observations WHERE notification_key = ?)", notificationKey)
                    .use { it.executeUpdate() }
            }
        }
        return prepare("UPDATE notification_observations SET is_removed = 1, revision = revision + 1 WHERE notification_key = ? AND is_removed = 0", notificationKey)
            .use { it.executeUpdate() }
    }

    private fun writeCursor(cursor: IngestCursor) {
        prepare(
            "INSERT OR REPLACE INTO ingest_cursors (source, last_processed_time_millis, last_processed_key, processed_count, gap_count) VALUES (?,?,?,?,?)",
            cursor.source, cursor.lastProcessedTimeMillis, cursor.lastProcessedKey, cursor.processedCount, cursor.gapCount,
        ).use { it.executeUpdate() }
    }

    private fun categoryOf(obs: ObservedNotification): NotificationCategory = categoryForStoredObservation(obs)

    private fun prepare(sql: String, vararg args: Any?): PreparedStatement {
        val ps = connection.prepareStatement(sql)
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setNull(i + 1, Types.NULL)
                is String -> ps.setString(i + 1, a)
                is Int -> ps.setInt(i + 1, a)
                is Long -> ps.setLong(i + 1, a)
                else -> ps.setString(i + 1, a.toString())
            }
        }
        return ps
    }

    private fun count(sql: String, vararg args: Any?): Int =
        prepare(sql, *args).use { ps -> ps.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }

    private fun query(sql: String, vararg args: Any?): List<ObservedNotification> =
        prepare(sql, *args).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    private fun queryOne(sql: String, vararg args: Any?): ObservedNotification? = query(sql, *args).firstOrNull()

    private fun map(rs: ResultSet): ObservedNotification {
        val fragments = rs.getString("text_fragments_json").orEmpty()
        return ObservedNotification(
            id = ScopedId.parse(rs.getString("id")),
            accountId = AccountId.parse(rs.getString("account_key")),
            conversationId = ScopedId.parse(rs.getString("conversation_key")),
            packageId = rs.getString("package_id"),
            notificationKey = rs.getString("notification_key"),
            notificationId = rs.getInt("notification_id"),
            tag = rs.getString("tag"),
            senderHint = rs.getString("sender_hint"),
            title = rs.getString("title"),
            textFragments = if (fragments.isBlank()) emptyList() else fragments.split("\u001F"),
            rawTextDigest = rs.getString("raw_text_digest"),
            observedAt = UtcInstant(rs.getLong("observed_at")),
            postTime = UtcInstant(rs.getLong("post_time")),
            revision = rs.getLong("revision"),
            isRemoved = rs.getInt("is_removed") != 0,
            isGroupSummary = rs.getInt("is_group_summary") != 0,
            truncationState = TruncationState.valueOf(rs.getString("truncation_state")),
            correlationConfidence = CorrelationConfidence.valueOf(rs.getString("correlation_confidence")),
            provenance = ObservationProvenance.valueOf(rs.getString("provenance")),
            direction = ObservationSchema.decodeDirection(rs.getString("direction")),
            recipients = ObservationSchema.decodeRecipients(rs.getString("recipients_json")),
            replyAnchor = rs.getString("reply_anchor"),
            notificationCategory = ObservationSchema.decodeCategory(rs.getString("notification_category")),
        )
    }
}
