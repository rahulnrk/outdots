package app.mini.hub.persistence

import app.mini.hub.domain.AccountId
import app.mini.hub.domain.Provider
import app.mini.hub.domain.ScopedId
import app.mini.hub.domain.UtcInstant
import app.mini.hub.matter.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * WP05 JDBC MatterStore implementation for cross-platform/JVM real database testing
 * and durable persistent matter storage (unencrypted host-test storage).
 */
class JdbcMatterStore(
    private val dbFile: File,
) : MatterStore {
    init {
        if (!dbFile.parentFile!!.exists()) {
            dbFile.parentFile!!.mkdirs()
        }
        initSchema()
    }

    private fun connection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = ON;")
        }
        return conn
    }

    private fun initSchema() {
        connection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS matters (
                        id TEXT PRIMARY KEY,
                        account_key TEXT NOT NULL,
                        title TEXT NOT NULL,
                        state TEXT NOT NULL,
                        consent_integer INTEGER NOT NULL,
                        excluded_integer INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS matter_evidence (
                        matter_id TEXT NOT NULL,
                        observation_id TEXT NOT NULL,
                        snippet TEXT NOT NULL,
                        captured_at INTEGER NOT NULL,
                        FOREIGN KEY(matter_id) REFERENCES matters(id) ON DELETE CASCADE
                    )
                """)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS account_consent (
                        account_key TEXT PRIMARY KEY,
                        consent_integer INTEGER NOT NULL,
                        excluded_integer INTEGER NOT NULL,
                        revision INTEGER NOT NULL DEFAULT 0
                    )
                """)
                conn.metaData.getColumns(null, null, "account_consent", "revision").use { rs ->
                    if (!rs.next()) {
                        stmt.execute("ALTER TABLE account_consent ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                    }
                }
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_matters_account ON matters(account_key)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_evidence_matter ON matter_evidence(matter_id)")
            }
        }
    }

    private fun isConsentGrantedInternal(conn: Connection, accountId: AccountId): Boolean {
        conn.prepareStatement("SELECT consent_integer FROM account_consent WHERE account_key = ?").use { ps ->
            ps.setString(1, accountId.key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return false
                return rs.getInt(1) == 1
            }
        }
    }

    private fun isExcludedInternal(conn: Connection, accountId: AccountId): Boolean {
        conn.prepareStatement("SELECT excluded_integer FROM account_consent WHERE account_key = ?").use { ps ->
            ps.setString(1, accountId.key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return true
                return rs.getInt(1) == 1
            }
        }
    }

    private fun currentConsentRevisionInternal(conn: Connection, accountId: AccountId): Long {
        conn.prepareStatement("SELECT revision FROM account_consent WHERE account_key = ?").use { ps ->
            ps.setString(1, accountId.key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return 0L
                return rs.getLong(1)
            }
        }
    }

    override fun upsert(matter: MatterRecord): MatterRecord {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                val granted = isConsentGrantedInternal(conn, matter.accountId)
                val excluded = isExcludedInternal(conn, matter.accountId)
                MatterValidation.validateMatter(matter, granted, excluded)

                conn.prepareStatement(
                    "INSERT OR REPLACE INTO matters (id, account_key, title, state, consent_integer, excluded_integer, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, matter.id.key)
                    ps.setString(2, matter.accountId.key)
                    ps.setString(3, matter.title)
                    ps.setString(4, matter.state.name)
                    ps.setInt(5, if (matter.consentGranted) 1 else 0)
                    ps.setInt(6, if (matter.excluded) 1 else 0)
                    ps.setLong(7, matter.updatedAt.epochMillis)
                    val affected = ps.executeUpdate()
                    if (affected <= 0) error("Failed to insert or replace matter record")
                }

                conn.prepareStatement("DELETE FROM matter_evidence WHERE matter_id = ?").use { ps ->
                    ps.setString(1, matter.id.key)
                    ps.executeUpdate()
                }

                if (matter.evidenceLinks.isNotEmpty()) {
                    conn.prepareStatement(
                        "INSERT INTO matter_evidence (matter_id, observation_id, snippet, captured_at) VALUES (?, ?, ?, ?)"
                    ).use { ps ->
                        matter.evidenceLinks.forEach { link ->
                            ps.setString(1, matter.id.key)
                            ps.setString(2, link.observationId.key)
                            ps.setString(3, link.snippet)
                            ps.setLong(4, link.capturedAt.epochMillis)
                            ps.addBatch()
                        }
                        val results = ps.executeBatch()
                        if (results.any { it < 0 }) {
                            error("Failed to insert matter evidence batch")
                        }
                    }
                }

                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        return matter
    }

    override fun upsertWithTicket(matter: MatterRecord, ticket: ConsentTicket): MatterRecord {
        require(ticket.accountId == matter.accountId) { "Consent ticket account must match matter accountId" }
        connection().use { conn ->
            conn.autoCommit = false
            try {
                val granted = isConsentGrantedInternal(conn, matter.accountId)
                val excluded = isExcludedInternal(conn, matter.accountId)
                val rev = currentConsentRevisionInternal(conn, matter.accountId)
                require(granted && !excluded && ticket.revision == rev) {
                    "Consent ticket is stale or account consent is revoked/excluded"
                }
                MatterValidation.validateMatter(matter, granted, excluded)

                conn.prepareStatement(
                    "INSERT OR REPLACE INTO matters (id, account_key, title, state, consent_integer, excluded_integer, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, matter.id.key)
                    ps.setString(2, matter.accountId.key)
                    ps.setString(3, matter.title)
                    ps.setString(4, matter.state.name)
                    ps.setInt(5, if (matter.consentGranted) 1 else 0)
                    ps.setInt(6, if (matter.excluded) 1 else 0)
                    ps.setLong(7, matter.updatedAt.epochMillis)
                    val affected = ps.executeUpdate()
                    if (affected <= 0) error("Failed to insert or replace matter record")
                }

                conn.prepareStatement("DELETE FROM matter_evidence WHERE matter_id = ?").use { ps ->
                    ps.setString(1, matter.id.key)
                    ps.executeUpdate()
                }

                if (matter.evidenceLinks.isNotEmpty()) {
                    conn.prepareStatement(
                        "INSERT INTO matter_evidence (matter_id, observation_id, snippet, captured_at) VALUES (?, ?, ?, ?)"
                    ).use { ps ->
                        matter.evidenceLinks.forEach { link ->
                            ps.setString(1, matter.id.key)
                            ps.setString(2, link.observationId.key)
                            ps.setString(3, link.snippet)
                            ps.setLong(4, link.capturedAt.epochMillis)
                            ps.addBatch()
                        }
                        val results = ps.executeBatch()
                        if (results.any { it < 0 }) {
                            error("Failed to insert matter evidence batch")
                        }
                    }
                }

                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        return matter
    }

    override fun get(id: ScopedId): MatterRecord? {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "SELECT id, account_key, title, state, consent_integer, excluded_integer, updated_at FROM matters WHERE id = ?"
                ).use { ps ->
                    ps.setString(1, id.key)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return null
                        val matterId = ScopedId.parse(rs.getString("id"))
                        val accountId = AccountId.parse(rs.getString("account_key"))
                        val title = rs.getString("title")
                        val state = MatterState.valueOf(rs.getString("state"))
                        val consent = rs.getInt("consent_integer") == 1
                        val excluded = rs.getInt("excluded_integer") == 1
                        val updatedAt = UtcInstant(rs.getLong("updated_at"))

                        if (!isConsentGrantedInternal(conn, accountId) || isExcludedInternal(conn, accountId) || !consent || excluded) {
                            conn.commit()
                            return null
                        }

                        val evidenceLinks = mutableListOf<EvidenceLink>()
                        conn.prepareStatement(
                            "SELECT observation_id, snippet, captured_at FROM matter_evidence WHERE matter_id = ?"
                        ).use { evPs ->
                            evPs.setString(1, id.key)
                            evPs.executeQuery().use { evRs ->
                                while (evRs.next()) {
                                    evidenceLinks.add(
                                        EvidenceLink(
                                            observationId = ScopedId.parse(evRs.getString("observation_id")),
                                            snippet = evRs.getString("snippet"),
                                            capturedAt = UtcInstant(evRs.getLong("captured_at"))
                                        )
                                    )
                                }
                            }
                        }
                        conn.commit()
                        return MatterRecord(
                            id = matterId,
                            accountId = accountId,
                            title = title,
                            state = state,
                            evidenceLinks = evidenceLinks,
                            consentGranted = consent,
                            excluded = excluded,
                            updatedAt = updatedAt
                        )
                    }
                }
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun transition(id: ScopedId, nextState: MatterState, now: UtcInstant): MatterRecord {
        val existing = get(id) ?: error("Matter not found")
        val updated = existing.copy(state = nextState, updatedAt = now)
        upsert(updated)
        return updated
    }

    override fun listForAccount(accountId: AccountId): List<MatterRecord> {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                if (!isConsentGrantedInternal(conn, accountId) || isExcludedInternal(conn, accountId)) {
                    conn.commit()
                    return emptyList()
                }
                val ids = mutableListOf<ScopedId>()
                conn.prepareStatement(
                    "SELECT id FROM matters WHERE account_key = ? AND consent_integer = 1 AND excluded_integer = 0"
                ).use { ps ->
                    ps.setString(1, accountId.key)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            ids.add(ScopedId.parse(rs.getString("id")))
                        }
                    }
                }
                conn.commit()
                return ids.mapNotNull { get(it) }
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun invalidateForAccount(accountId: AccountId) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO account_consent (account_key, consent_integer, excluded_integer) VALUES (?, 0, 1)"
                ).use { ps ->
                    ps.setString(1, accountId.key)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM matter_evidence WHERE matter_id IN (SELECT id FROM matters WHERE account_key = ?)").use { ps ->
                    ps.setString(1, accountId.key)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM matters WHERE account_key = ?").use { ps ->
                    ps.setString(1, accountId.key)
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun invalidateForObservation(observationId: ScopedId) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM matter_evidence WHERE observation_id = ? AND matter_id IN (SELECT id FROM matters WHERE account_key = ?)").use { ps ->
                    ps.setString(1, observationId.key)
                    ps.setString(2, observationId.account.key)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM matters WHERE account_key = ? AND id NOT IN (SELECT DISTINCT matter_id FROM matter_evidence)").use { ps ->
                    ps.setString(1, observationId.account.key)
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun setConsent(accountId: AccountId, granted: Boolean, excluded: Boolean) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                val currentRev = currentConsentRevisionInternal(conn, accountId)
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO account_consent (account_key, consent_integer, excluded_integer, revision) VALUES (?, ?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, accountId.key)
                    ps.setInt(2, if (granted) 1 else 0)
                    ps.setInt(3, if (excluded) 1 else 0)
                    ps.setLong(4, currentRev + 1L)
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    override fun isConsentGranted(accountId: AccountId): Boolean {
        connection().use { conn ->
            return isConsentGrantedInternal(conn, accountId)
        }
    }

    override fun isExcluded(accountId: AccountId): Boolean {
        connection().use { conn ->
            return isExcludedInternal(conn, accountId)
        }
    }

    override fun currentConsentRevision(accountId: AccountId): Long {
        connection().use { conn ->
            return currentConsentRevisionInternal(conn, accountId)
        }
    }

    override fun issueConsentTicket(accountId: AccountId): ConsentTicket {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                val granted = isConsentGrantedInternal(conn, accountId)
                val excluded = isExcludedInternal(conn, accountId)
                require(granted && !excluded) {
                    "Cannot issue consent ticket for account without active unexcluded Focus consent"
                }
                val rev = currentConsentRevisionInternal(conn, accountId)
                conn.commit()
                return ConsentTicket(accountId, rev)
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }
}
