package nl.greaper.bnplanner

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import nl.greaper.bnplanner.auth.LoginExpiredException
import nl.greaper.bnplanner.auth.OsuUnavailableException
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class DefaultExceptionHandler(
    private val discordClient: DiscordClient?,
) : ResponseEntityExceptionHandler() {
    private val log = KotlinLogging.logger { }

    /** Logged in, but without the role for this */
    @ExceptionHandler
    fun accessDenied(ex: AccessDeniedException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You are not allowed to do this"))

    /** osu! no longer accepts their login, so they have to log in again */
    @ExceptionHandler
    fun loginExpired(
        ex: LoginExpiredException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        request.getSession(false)?.invalidate()
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Please log in again"))
    }

    @ExceptionHandler
    fun osuUnavailable(ex: OsuUnavailableException): ResponseEntity<ProblemDetail> {
        log.warn(ex) { "osu! is unavailable" }
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "osu! can not be reached right now"))
    }

    /** Tells which parameter of a filter is missing or wrong, like for any other parameter */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        ex.body.detail =
            ex.bindingResult.allErrors.joinToString(" ") { error ->
                if (error is FieldError) {
                    "Failed to convert '${error.field}' with value: '${error.rejectedValue}'"
                } else {
                    // The filter can't be made without it
                    "A required parameter is not present."
                }
            }

        return super.handleMethodArgumentNotValid(ex, headers, status, request)
    }

    /** Something that went wrong in the planner itself, the developer hears about it */
    @ExceptionHandler
    fun exception(ex: Exception): ResponseEntity<ProblemDetail> {
        discordClient?.send(
            "**ERROR:**\n" +
                "```\n${ex.stackTraceToString().take(1600)}\n```",
            color = EmbedColor.RED,
            thumbnail = EmbedThumbnail(""),
            footer = EmbedFooter(""),
            confidential = true,
            gamemodes = emptyList(),
        )

        log.error(ex) { "Unhandled exception occurred" }

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to handle request"))
    }
}
