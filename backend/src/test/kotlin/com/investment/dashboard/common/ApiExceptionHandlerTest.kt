package com.investment.dashboard.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotWritableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.ServletWebRequest

@ExtendWith(OutputCaptureExtension::class)
class ApiExceptionHandlerTest {

    @Test
    @DisplayName("Spring MVC 의 5xx 예외는 500 으로 응답하고 스택 트레이스와 함께 로그를 남긴다")
    fun spring_mvc_server_error_is_logged_with_stack_trace(output: CapturedOutput) {
        val request = ServletWebRequest(MockHttpServletRequest(), MockHttpServletResponse())

        val response = ApiExceptionHandler().handleException(HttpMessageNotWritableException("응답 직렬화 실패"), request)

        assertThat(response?.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(output).contains(
            "요청 처리 중 서버 오류: HttpMessageNotWritableException",
            "HttpMessageNotWritableException: 응답 직렬화 실패",
            "at com.investment.dashboard.common.ApiExceptionHandlerTest",
        )
    }
}
