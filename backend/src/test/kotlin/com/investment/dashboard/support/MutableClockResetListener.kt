package com.investment.dashboard.support

import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener

class MutableClockResetListener : TestExecutionListener {

    override fun beforeTestMethod(testContext: TestContext) {
        if (testContext.hasApplicationContext()) {
            testContext.applicationContext.getBeanProvider(MutableClock::class.java).ifAvailable { it.reset() }
        }
    }
}
