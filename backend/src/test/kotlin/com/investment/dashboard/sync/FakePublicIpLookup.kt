package com.investment.dashboard.sync

class FakePublicIpLookup : PublicIpLookup {

    var ip: String? = null

    override fun currentIp(): String? = ip
}
