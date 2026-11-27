package nl.greaper.bnplanner.client

/** osu! gave no clear answer in time, the planner can try again later */
class OsuUnavailableException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    constructor(cause: Throwable) : this("Could not reach osu!", cause)
}
