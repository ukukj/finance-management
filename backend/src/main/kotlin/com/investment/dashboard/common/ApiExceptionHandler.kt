package com.investment.dashboard.common

import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.context.MessageSourceResolvable
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.validation.method.ParameterValidationResult
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import tools.jackson.core.JacksonException

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(ex: InvalidRequestException): ApiError = invalid(ex.fields.toList())

    @ExceptionHandler
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(ex: NotFoundException): ApiError = ApiError("NOT_FOUND", ex.message.orEmpty())

    @ExceptionHandler
    @ResponseStatus(HttpStatus.CONFLICT)
    fun manualEntryNotAllowed(ex: ManualEntryNotAllowedException): ApiError =
        ApiError("MANUAL_NOT_ALLOWED", ex.message.orEmpty())

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.bindingResult.fieldErrors.map { it.field to it.defaultMessage.orEmpty() }
        return handleExceptionInternal(ex, invalid(errors), headers, status, request)
    }

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.parameterValidationResults.flatMap { result ->
            result.resolvableErrors.map { fieldOf(result, it) to it.defaultMessage.orEmpty() }
        }
        return handleExceptionInternal(ex, invalid(errors), headers, status, request)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val field = (ex.cause as? JacksonException)?.path?.firstNotNullOfOrNull { it.propertyName }
        val errors = listOfNotNull(field?.let { it to UNREADABLE_VALUE })
        return handleExceptionInternal(ex, invalid(errors), headers, status, request)
    }

    override fun handleTypeMismatch(
        ex: TypeMismatchException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = listOfNotNull(ex.propertyName?.let { it to UNREADABLE_VALUE })
        return handleExceptionInternal(ex, invalid(errors), headers, status, request)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if (statusCode.is5xxServerError) {
            log.error("요청 처리 중 서버 오류: {}", ex.javaClass.simpleName, ex)
            return super.handleExceptionInternal(ex, body, headers, statusCode, request)
        }
        val error = body as? ApiError ?: ApiError(codeOf(statusCode), reasonOf(statusCode))
        return super.handleExceptionInternal(ex, error, headers, statusCode, request)
    }

    private fun invalid(errors: List<Pair<String, String>>): ApiError {
        val fields = errors.groupBy({ it.first }) { it.second }
            .mapValues { (_, messages) -> messages.sorted().joinToString(", ") }
        return ApiError(INVALID_REQUEST, "요청 값이 올바르지 않음", fields)
    }
}

private fun fieldOf(result: ParameterValidationResult, error: MessageSourceResolvable): String =
    (error as? FieldError)?.field ?: result.methodParameter.parameterName.orEmpty()

private fun codeOf(status: HttpStatusCode): String = if (status.value() == HttpStatus.BAD_REQUEST.value()) {
    INVALID_REQUEST
} else {
    HttpStatus.resolve(status.value())?.name ?: status.value().toString()
}

private fun reasonOf(status: HttpStatusCode): String = HttpStatus.resolve(status.value())?.reasonPhrase.orEmpty()

private val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

private const val INVALID_REQUEST = "INVALID_REQUEST"
private const val UNREADABLE_VALUE = "값이 없거나 형식이 틀림"
