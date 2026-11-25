package com.investment.dashboard.sync

class SyncDomainException private constructor(val reason: String) : RuntimeException(reason) {

    companion object {
        fun noBrokerageAccount() = SyncDomainException("토스 종합매매 계좌 없음")

        fun unknownTransactionType(type: String) = SyncDomainException("동기화 실패: 알 수 없는 거래 유형 $type")
    }
}
