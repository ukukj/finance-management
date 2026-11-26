package com.investment.dashboard.dashboard

import com.investment.dashboard.dividend.DividendProfile
import com.investment.dashboard.dividend.DividendProfileRepository
import com.investment.dashboard.holding.Holding
import com.investment.dashboard.holding.HoldingRepository
import org.springframework.stereotype.Component

class HeldPosition(val holding: Holding, val profile: DividendProfile?)

@Component
class HeldPositionReader(
    private val holdingRepository: HoldingRepository,
    private val profileRepository: DividendProfileRepository,
) {

    fun read(): List<HeldPosition> {
        val profiles = profileRepository.findAll().associateBy { it.accountId to it.symbol }
        return holdingRepository.findAll()
            .filter { it.quantity.signum() > 0 }
            .map { HeldPosition(it, profiles[it.accountId to it.symbol]) }
    }
}
