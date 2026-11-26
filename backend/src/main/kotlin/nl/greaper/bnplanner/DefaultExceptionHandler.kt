package nl.greaper.bnplanner

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import nl.greaper.bnplanner.auth.LoginExpiredException
import nl.greaper.bnplanner.auth.OsuUnavailableException
import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
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

    /** Something that went wrong in the planner itself, the developer hears about it */
    @ExceptionHandler
    fun exception(ex: Exception): ResponseEntity<String> {
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

        return ResponseEntity("Unable to handle request", HttpStatus.INTERNAL_SERVER_ERROR)
    }
}
