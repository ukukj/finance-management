package com.investment.dashboard.support

import com.investment.dashboard.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
class MutableClockResetTest {

    @Autowired
    lateinit var clock: MutableClock

    @RepeatedTest(2)
    @DisplayName("앞 테스트가 시계를 옮겨도 다음 테스트는 기본 시각에서 시작한다")
    fun each_test_starts_at_default_instant_after_previous_test_moved_clock() {
        val startedAt = clock.instant()

        clock.set(OffsetDateTime.parse("2030-03-01T10:00+09:00").toInstant())

        assertThat(startedAt).isEqualTo(OffsetDateTime.parse("2026-11-22T10:00+09:00").toInstant())
    }
}
