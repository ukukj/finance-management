package com.investment.dashboard.sync

import com.investment.dashboard.toss.TossApi
import com.investment.dashboard.toss.TossApiException
import com.investment.dashboard.toss.TossIpNotAllowedException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException

@Service
class TossSyncService(
    private val tossApi: TossApi,
    private val snapshotWriter: TossSnapshotWriter,
    private val syncLock: SyncLock,
    private val runRecorder: SyncRunRecorder,
    private val publicIpLookup: PublicIpLookup,
    private val failureNotifier: SyncFailureNotifier,
) {

    fun run(trigger: SyncTrigger): SyncRun {
        val started = runRecorder.start(trigger)
        var recorded = false
        try {
            val outcome = syncOutcome()
            val finished = runRecorder.finish(started, outcome.status, outcome.failureReason)
            recorded = true
            notifyIfFailed(finished)
            return finished
        } finally {
            if (!recorded) {
                notifyIfFailed(runRecorder.finish(started, SyncStatus.FAILED, UNEXPECTED_FAILURE_REASON))
            }
        }
    }

    private fun syncOutcome(): Outcome = try {
        syncUnderLock()
    } catch (e: TossIpNotAllowedException) {
        failed(e, "허용 IP 확인 필요 (현재 IP ${publicIpLookup.currentIp() ?: "확인 실패"})")
    } catch (e: TossApiException) {
        failed(e, syncFailureReason(e))
    } catch (e: SyncDomainException) {
        failed(e, e.reason)
    } catch (e: DataAccessException) {
        failed(e, syncFailureReason(e))
    } catch (e: TransactionException) {
        failed(e, syncFailureReason(e))
    }

    private fun syncUnderLock(): Outcome {
        val held = syncLock.tryAcquire() ?: return Outcome(SyncStatus.SKIPPED)
        held.use { syncFromToss() }
        return Outcome(SyncStatus.SUCCEEDED)
    }

    private fun syncFromToss() {
        val account = tossApi.accounts().firstOrNull { it.accountType == BROKERAGE_ACCOUNT_TYPE }
            ?: throw SyncDomainException.noBrokerageAccount()
        val holdings = tossApi.holdings(account.accountSeq)
        val transactions = tossApi.allTransactions(account.accountSeq)
        val usdKrwRate = tossApi.usdKrwRate()
        snapshotWriter.write(account, holdings, transactions, usdKrwRate)
    }

    private fun failed(error: RuntimeException, reason: String): Outcome {
        log.warn("토스 동기화 실패", error)
        return Outcome(SyncStatus.FAILED, reason)
    }

    private fun notifyIfFailed(run: SyncRun) {
        if (run.status == SyncStatus.FAILED) {
            failureNotifier.notifyFailure(run)
        }
    }
}

private data class Outcome(val status: SyncStatus, val failureReason: String? = null)

private fun syncFailureReason(error: RuntimeException) = "동기화 실패: ${error.message ?: error.javaClass.simpleName}"

private const val BROKERAGE_ACCOUNT_TYPE = "BROKERAGE"
private const val UNEXPECTED_FAILURE_REASON = "동기화 실패: 예상하지 못한 오류"
private val log = LoggerFactory.getLogger(TossSyncService::class.java)
