package com.investment.dashboard.sync

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(comment = "토스 동기화 실행 기록")
class SyncRun(
    @Column(comment = "실행 계기 (SCHEDULED 새벽 자동, MANUAL 수동)")
    @Enumerated(EnumType.STRING)
    var triggerType: SyncTrigger,

    @Column(comment = "결과 (RUNNING 실행 중, SUCCEEDED 성공, FAILED 실패, SKIPPED 겹쳐서 건너뜀)")
    @Enumerated(EnumType.STRING)
    var status: SyncStatus,

    @Column(comment = "시작 시각")
    var startedAt: OffsetDateTime,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(comment = "식별자")
    var id: Long? = null

    @Column(comment = "종료 시각")
    var finishedAt: OffsetDateTime? = null

    @Column(comment = "실패 사유")
    var failureReason: String? = null
}
