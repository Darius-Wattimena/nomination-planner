package nl.greaper.bnplanner.controller

import jakarta.annotation.security.RolesAllowed
import nl.greaper.bnplanner.auth.RolePermission
import nl.greaper.bnplanner.service.HistoryImportService
import nl.greaper.bnplanner.service.TransferService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.ZoneOffset

/** Only for the developers: moving ranked beatmaps between planners, and importing the ranked history of osu! */
@RestController
@RequestMapping("/v2/developer")
class DeveloperController(
    private val transfer: TransferService,
    private val history: HistoryImportService,
) {
    /** Every ranked beatmap with its history, the users it mentions and the role history, as a gzipped JSON file */
    @GetMapping("/export")
    @RolesAllowed(RolePermission.DEVELOPER)
    fun export(): ResponseEntity<ByteArray> {
        val name = "planner-ranked-${LocalDate.now(ZoneOffset.UTC)}.json.gz"

        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("application/gzip"))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(name)
                    .build()
                    .toString(),
            ).body(transfer.export())
    }

    /** What importing the file would do, or with dryRun=false starts it. Only ever adds ranked beatmaps that are new. */
    @PostMapping("/import", consumes = [MediaType.ALL_VALUE])
    @RolesAllowed(RolePermission.DEVELOPER)
    fun import(
        @RequestParam(required = false) dryRun: Boolean?,
        body: InputStream,
    ): TransferService.ImportSummary {
        // Kept on disk instead of in memory, an export of the whole ranked history is tens of megabytes
        val file = Files.createTempFile("import", ".json.gz")
        try {
            body.use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
        } catch (ex: Exception) {
            Files.deleteIfExists(file)
            throw ex
        }
        if (dryRun == false) {
            // Deleted once the import is done
            return transfer.start(file)
        }
        try {
            return transfer.check(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @GetMapping("/import")
    @RolesAllowed(RolePermission.DEVELOPER)
    fun findImportStatus(): TransferService.ImportStatus = transfer.findStatus()

    @GetMapping("/history")
    @RolesAllowed(RolePermission.DEVELOPER)
    fun findHistoryStatus(): HistoryImportService.Status = history.findStatus()

    /** Starts or continues importing every ranked beatmap of osu!, which takes hours */
    @PostMapping("/history/start")
    @RolesAllowed(RolePermission.DEVELOPER)
    fun startHistory(): HistoryImportService.Status = running(history.start())

    /** Works out again who nominated which gamemode of the old ranked beatmaps */
    @PostMapping("/history/modes")
    @RolesAllowed(RolePermission.DEVELOPER)
    fun guessModes(): HistoryImportService.Status = running(history.startModes())

    @PostMapping("/history/stop")
    @RolesAllowed(RolePermission.DEVELOPER)
    fun stopHistory(): HistoryImportService.Status {
        history.stop()
        return history.findStatus()
    }

    private fun running(started: Boolean): HistoryImportService.Status {
        if (!started) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The import of the ranked history is already running")
        }
        return history.findStatus()
    }

    @ExceptionHandler
    fun importRefused(ex: TransferService.ImportException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("detail" to (ex.message ?: "This file can't be imported")))
}
