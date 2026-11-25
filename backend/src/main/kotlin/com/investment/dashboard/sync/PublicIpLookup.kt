package com.investment.dashboard.sync

interface PublicIpLookup {
    fun currentIp(): String?
}
