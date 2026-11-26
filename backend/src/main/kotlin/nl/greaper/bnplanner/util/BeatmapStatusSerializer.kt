package nl.greaper.bnplanner.util

import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus.Companion.toPriorityStatus
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

class BeatmapStatusSerializer : StdSerializer<BeatmapStatus>(BeatmapStatus::class.java) {
    override fun serialize(
        status: BeatmapStatus,
        gen: JsonGenerator,
        context: SerializationContext,
    ) {
        gen.writeNumber(status.toPriorityStatus())
    }
}

class BeatmapStatusDeserializer : StdDeserializer<BeatmapStatus>(BeatmapStatus::class.java) {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): BeatmapStatus? {
        val value = parser.intValue

        return BeatmapStatus.fromPriorityStatus(value)
    }
}
