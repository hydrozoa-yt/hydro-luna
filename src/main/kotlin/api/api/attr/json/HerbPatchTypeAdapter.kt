package api.attr.json

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import game.skill.farming.HerbPatch
import game.skill.farming.HerbPatchLocation
import game.skill.farming.HerbSeeds

/**
 * A [TypeAdapter] for [HerbPatch] objects.
 * @author hydrozoa
 */
object HerbPatchTypeAdapter : TypeAdapter<HerbPatch>() {

    override fun write(out: JsonWriter, value: HerbPatch) {
        out.beginObject()
        out.name("location").value(value.location.name) // Store enum name
        out.name("plantType").value(value.plantType?.name) // Store enum name, can be null
        out.name("growthStage").value(value.growthStage)
        out.name("weeds").value(value.weeds)
        out.endObject()
    }

    override fun read(input: JsonReader): HerbPatch {
        input.beginObject()
        var location: HerbPatchLocation? = null
        var plantType: HerbSeeds? = null
        var growthStage = 0
        var weeds = 0

        while (input.hasNext()) {
            when (input.nextName()) {
                "location" -> location = HerbPatchLocation.valueOf(input.nextString())
                "plantType" -> {
                    val plantTypeName = input.nextString()
                    plantType = if (plantTypeName == "null") null else HerbSeeds.valueOf(plantTypeName)
                }
                "growthStage" -> growthStage = input.nextInt()
                "weeds" -> weeds = input.nextInt()
            }
        }
        input.endObject()

        val herbPatch = HerbPatch(location!!)
        herbPatch.plantType = plantType
        herbPatch.growthStage = growthStage
        herbPatch.weeds = weeds

        return herbPatch
    }
}
