package nl.greaper.bnplanner

import io.github.oshai.kotlinlogging.KotlinLogging
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
