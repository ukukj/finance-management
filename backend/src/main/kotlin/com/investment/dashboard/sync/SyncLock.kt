package com.investment.dashboard.sync

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

@Component
class SyncLock(private val dataSource: DataSource) {

    fun tryAcquire(): Held? {
        val connection = DataSourceUtils.getConnection(dataSource)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        var held: Held? = null
        try {
            if (jdbc.queryForObject(TRY_LOCK_SQL, Boolean::class.javaObjectType) == true) {
                held = AdvisoryLock(connection, jdbc)
            }
        } finally {
            if (held == null) {
                DataSourceUtils.releaseConnection(connection, dataSource)
            }
        }
        return held
    }

    interface Held : AutoCloseable

    private inner class AdvisoryLock(private val connection: Connection, private val jdbc: JdbcTemplate) : Held {

        override fun close() {
            try {
                jdbc.queryForObject(UNLOCK_SQL, Boolean::class.javaObjectType)
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource)
            }
        }
    }
}

private const val LOCK_KEY = 727001L
private const val TRY_LOCK_SQL = "select pg_try_advisory_lock($LOCK_KEY)"
private const val UNLOCK_SQL = "select pg_advisory_unlock($LOCK_KEY)"
