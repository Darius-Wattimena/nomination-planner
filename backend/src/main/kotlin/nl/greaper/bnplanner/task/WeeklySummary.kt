package nl.greaper.bnplanner.task

import nl.greaper.bnplanner.service.WeeklySummaryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Posts the weekly summary of each mode to the public Discord channels of that mode */
@Component
@ConditionalOnProperty(name = ["discord.weekly-summary.enabled"], havingValue = "true")
class WeeklySummary(
    private val service: WeeklySummaryService,
) {
    @Scheduled(cron = "\${discord.weekly-summary.cron}", zone = "UTC")
    fun post() = service.post()
}
