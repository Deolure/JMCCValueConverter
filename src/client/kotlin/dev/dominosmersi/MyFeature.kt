package dev.dominosmersi

import com.google.gson.JsonParser
import com.mojang.serialization.DataResult
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.component.Component
import net.minecraft.component.ComponentMap
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtByte
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtDouble
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtFloat
import net.minecraft.nbt.NbtInt
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtLong
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtShort
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.nbt.NbtString
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryWrapper
import net.minecraft.screen.slot.Slot
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Formatting
import org.apache.commons.codec.binary.Base64.decodeBase64
import org.apache.logging.log4j.core.util.Integers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.ObjectOutputStream
import java.util.*
import java.util.zip.GZIPInputStream
import kotlin.jvm.optionals.getOrDefault
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull


object MyFeature {
    fun run(client: MinecraftClient) {
        val screen = client.currentScreen
        if (screen != null) {
            if (screen is HandledScreen<*>) {
                val slot = screen.focusedSlot
                if (slot != null && !slot.stack.isEmpty) {
                    client.keyboard.clipboard = disassembleItem(slot.stack)
                    client.player?.sendMessage(Text.of("Cкопировано в буфер обмена!"), false)
                } else {
                    client.keyboard.clipboard = "item(\"minecraft:air\")"
                    client.player?.sendMessage(Text.of("Cкопировано в буфер обмена!"), false)
                }
            }
        } else {
            val player = client.player ?: return
            val item = player.mainHandStack

            if (!item.isEmpty && item.count > 0) {
                client.keyboard.clipboard = disassembleItem(item)
            } else {
                client.keyboard.clipboard = "item(\"minecraft:air\")"
            }
            player.sendMessage(Text.of("Cкопировано в буфер обмена!"), false)
        }
    }
    fun run() {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val item = player.mainHandStack

        if (!item.isEmpty && item.count > 0) {
            client.keyboard.clipboard = disassembleItem(item)
        } else {
            client.keyboard.clipboard = "item(\"minecraft:air\")"
        }
        player.sendMessage(Text.of("Cкопировано в буфер обмена!"), false)
    }

    fun disassembleItem(item: ItemStack): String {
        // other values
        val creativeTags = item.components[DataComponentTypes.CUSTOM_DATA]?.copyNbt()?.getCompound("creative_plus")?.getOrNull()
        if (creativeTags != null) {
            val type = getCreativeType(creativeTags)
            return when (type) {
                "text" -> {getJmccText(creativeTags)}
                "number" -> {getJmccNumber(creativeTags)}
                "location" -> {getJmccLocation(creativeTags)}
                "vector" -> {getJmccVector(creativeTags)}
                "sound" -> {getJmccSound(creativeTags)}
                "particle" -> {getJmccParticle(creativeTags)}
                "potion" -> {getJmccPotion(creativeTags)}
                "game_value" -> {getJmccValue(creativeTags)}
                "array" -> {getJmccArray(creativeTags)}
                "map" -> {getJmccMap(creativeTags)}
                "variable" -> {getJmccVariable(creativeTags)}
                else -> {
                    val customData = item.get(DataComponentTypes.CUSTOM_DATA)
                    if (customData != null) {
                        val customData = item.get(DataComponentTypes.CUSTOM_DATA)
                        if (customData != null) {
                            val nbt = customData.copyNbt()
                            nbt.remove("creative_plus")
                            if (nbt.isEmpty) {
                                item.remove(DataComponentTypes.CUSTOM_DATA)
                            } else {
                                item.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt))
                            }
                        }
                    }
                    disassembleItem(item)
                }
            }
        }

        // id
        val id = Registries.ITEM.getId(item.item);

        // name
        val name = textToString(item.name)

        // count
        val count = item.count

        // lores
        val description  = item.get(DataComponentTypes.LORE)
        val legacyDescription = mutableListOf<String>()
        description?.styledLines?.forEach { line ->
            val text = line as Text
            val legacy = textToString(text)
            legacyDescription.add(legacy)
        }
        val legacy_lores_json = anyToPrettyJson(legacyDescription)

        // custom_tags
        val custom_tags = mutableMapOf<String, Any?>()
        val bukkit_values = item.components[DataComponentTypes.CUSTOM_DATA]?.copyNbt()?.getCompound("PublicBukkitValues")?.getOrNull()
        bukkit_values?.forEach { string, element ->
            if (string.startsWith("justcreativeplus:"))
                custom_tags[string.replace("justcreativeplus:","")] = element
        }
        val pretty_tags = anyToPrettyJson(custom_tags)

        // snbt
        val world = MinecraftClient.getInstance().world
        val ops = world!!.registryManager.getOps(NbtOps.INSTANCE)

        val nbt = ItemStack.CODEC
            .encodeStart(ops, item)
            .getOrThrow() as NbtCompound

        val clearedValues = nbt.getCompound("components").getOrNull()
        clearedValues?.remove("minecraft:custom_name")
        clearedValues?.remove("minecraft:lore")

        var snbt = "{}"
        if (clearedValues != null) {
            val raw = componentValueToAny(clearedValues)
            val unwrapped = unwrapOptional(raw)
            snbt = toSnbt(unwrapped)
        }

        return """item(id="$id",
    name="$name",
    count=$count,
    lore=$legacy_lores_json,
    nbt=m$snbt,
    custom_tags=$pretty_tags
)
        """.trimIndent()
    }

    fun textToString(text: Text): String {
        val result = StringBuilder()
        var prevStyle: Style = Style.EMPTY

        text.visit({ style, literal ->
            if (literal.isEmpty()) return@visit Optional.empty<Text>()

            if (style != null) {
                val color: TextColor? = style.color

                if (style != prevStyle) {
                    result.append("&")
                    if (color == null) {
                        result.append("r")
                    } else if (color.name.startsWith("#")) {
                        result.append(color.name)
                    } else {
                        val fmt = Formatting.byName(color.name)
                        if (fmt != null) result.append(fmt.code)
                    }
                }

                if (style.isObfuscated) result.append("&k")
                if (style.isBold) result.append("&l")
                if (style.isStrikethrough) result.append("&m")
                if (style.isUnderlined) result.append("&n")
                if (style.isItalic) result.append("&o")

                prevStyle = style
            }

            result.append(literal)
            Optional.empty<Text>()
        }, Style.EMPTY)

        return result.toString()
    }
    fun jsonToAny(element: JsonElement): Any {
        return when (element) {
            is JsonObject -> element.mapValues { jsonToAny(it.value) }
            is JsonArray -> element.map { jsonToAny(it) }
            is JsonPrimitive -> element.booleanOrNull ?: element.intOrNull ?: element.doubleOrNull ?: element.content
            else -> element.toString()
        }
    }

    fun anyToPrettyJson(value: Any, pretty: Boolean = true): String {

        fun convert(value: Any): JsonElement {
            return when (value) {
                is Map<*, *> -> buildJsonObject {
                    value.forEach { (k, v) ->
                        if (k is String && v != null) {
                            put(k.trim('"'), convert(v))
                        }
                    }
                }
                is List<*> -> buildJsonArray {
                    value.forEach { v ->
                        if (v != null) add(convert(v))
                    }
                }
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                null -> JsonNull
                else -> {
                    val s = value.toString()
                    // Если начинается с m", оставляем m" + остальное без экранирования
                    if (s.startsWith("m\"")) {
                        // Убираем лишние кавычки в конце
                        JsonPrimitive(s.trimEnd('"'))
                    } else {
                        JsonPrimitive(s.trim('"'))
                    }
                }
            }
        }

        val jsonElement = convert(value)
        val json = Json { this.prettyPrint = pretty }
        return json.encodeToString(JsonElement.serializer(), jsonElement)
    }
    fun componentValueToAny(value: Any?): Any? = when (value) {
        null -> null
        is NbtElement -> nbtToAny(value)
        is Optional<*> -> value.orElse(null)
        is List<*> -> value.map { componentValueToAny(it) }
        is Map<*, *> -> value.mapValues { (_, v) -> componentValueToAny(v) }
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }
    fun nbtToAny(element: NbtElement): Any {
        return when (element) {
            is NbtCompound -> element.keys.associateWith { key ->
                nbtToAny(element.get(key)!!)
            }
            is net.minecraft.nbt.NbtList -> element.map { nbtToAny(it) }
            is net.minecraft.nbt.NbtString -> element.asString()
            is net.minecraft.nbt.NbtInt -> element.intValue()
            is net.minecraft.nbt.NbtDouble -> element.doubleValue()
            is net.minecraft.nbt.NbtFloat -> element.floatValue()
            is net.minecraft.nbt.NbtLong -> element.longValue()
            is net.minecraft.nbt.NbtShort -> element.shortValue()
            is net.minecraft.nbt.NbtByte -> element.byteValue()
            else -> element.toString()
        }
    }
    fun getCreativeType(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()
        return value?.getString("type", "errror").orEmpty()
    }

    fun getJmccText(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()
        val text = componentValueToAny(value?.getString("text") ?: "unknown") as String
        val parsing = componentValueToAny(value?.getString("parsing") ?: "legacy")

        return when (parsing) {
            "legacy" -> "\"$text\""
            "plain" -> "p\"$text\""
            "minimessage" -> "m\"$text\""
            "json" -> "j\"${escapeForJmccJson(text)}\""
            else -> "\"$text\""
        }
    }
    fun getJmccVariable(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()
        val scope = value?.getString("scope")?.getOrNull()
        val var_name = value?.getString("variable")?.getOrNull()

        println(scope)

        return when (scope) {
            "game" -> "g`$var_name`"
            "save" -> "s`$var_name`"
            "local" -> "l`$var_name`"
            "line" -> "ln`$var_name`"
            else -> "g`$var_name`"
        }
    }

    fun escapeForJmccJson(text: String): String {
        return text.replace("\"", "\\\"")
    }

    fun getJmccNumber(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()
        val number = value?.get("number").toString()
        if ("%math" in number) {
            return number
        } else {
            return number.replace("d", "")
        }
    }
    fun getJmccLocation(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()
        val x = value?.get("x").toString().replace("d","")
        val y = value?.get("y").toString().replace("d","")
        val z = value?.get("z").toString().replace("d","")
        val pitch = value?.get("pitch").toString().replace("d","")
        val yaw = value?.get("yaw").toString().replace("d","")

        return "location($x, $y, $z, $pitch, $yaw)"
    }

    fun getJmccVector(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()
        val x = value?.get("x").toString().replace("d","")
        val y = value?.get("y").toString().replace("d","")
        val z = value?.get("z").toString().replace("d","")
        return "vector($x, $y, $z)"
    }

    fun getJmccSound(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()

        val sound = componentValueToAny(value?.getString("sound"))
        val volume = value?.get("volume").toString().replace("d","")
        val pitch = value?.get("pitch").toString().replace("d","")
        val variation = componentValueToAny(value?.getString("variation"))
        val source = componentValueToAny(value?.getString("source"))

        return buildString {
            appendLine("sound(")
            appendLine("    sound=\"$sound\",")
            appendLine("    volume=$volume,")
            appendLine("    pitch=$pitch,")
            appendLine("    variation=\"$variation\",")
            appendLine("    source=\"$source\"")
            append(")")
        }
    }

    fun getJmccParticle(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()

        val particle_type = componentValueToAny(value?.getString("particle_type"))
        val count = value?.get("count").toString().replace("d","")

        val first_spread = value?.get("first_spread").toString().replace("d","")
        val second_spread = value?.get("second_spread").toString().replace("d","")

        val x_motion = value?.get("x_motion").toString().replace("d","")
        val y_motion = value?.get("y_motion").toString().replace("d","")
        val z_motion = value?.get("z_motion").toString().replace("d","")

        val color = value?.get("color").toString().replace("d","")
        val to_color = value?.get("to_color").toString().replace("d","")

        val material = componentValueToAny(value?.getString("material")) ?: ""
        val size = value?.get("size").toString().replace("d","")

        return buildString{
            append("particle(\n")

            fun add (name: String, value: String) {
                if (value != "null") {
                    append("    $name=$value,\n")
                }
            }

            add("particle", "\"$particle_type\"")
            add("count", "$count")
            add("spread_x", "$first_spread")
            add("spread_y", "$second_spread")
            add("motion_x", "$x_motion")
            add("motion_y", "$y_motion")
            add("motion_z", "$z_motion")
            add("material", "\"$material\"")
            add("size", "$size")
            add("color", "$color")
            add("to_color", "$to_color")

            append(")")
        }
    }
    fun getJmccPotion(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()

        val potion = componentValueToAny(value?.getString("potion"))
        val amplifier = value?.get("amplifier").toString().replace("d","")
        val duration = value?.get("duration").toString().replace("d","")

        return buildString {
            append("potion(\n")
            appendLine("    potion=\"$potion\",")
            appendLine("    amplifier=$amplifier,")
            appendLine("    duration=$duration,")
            append(")")
        }
    }
    fun getJmccValue(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()

        val game_value = componentValueToAny(value?.getString("game_value"))
        val selection = componentValueToAny(value?.getString("selection")?.getOrDefault("default"))

        if (selection == "null") {
            return "value::$game_value"
        } else {
            val jsonObject = Json.parseToJsonElement(selection as String).jsonObject
            val textValue: String = jsonObject["type"]!!.jsonPrimitive.content
            println(textValue)
            return "value::$game_value<$textValue>"
        }
    }
    fun getJmccArray(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()
        val values = value?.getList("values")?.getOrNull()
        val array_jmcc = mutableListOf<String>()
        values?.forEach { value ->
            val nbt = NbtCompound()
            nbt.put("value", value)

            val value_type = getCreativeType(nbt)
            if (value != null) {
                array_jmcc.add(
                    when (value_type) {
                        "text" -> {getJmccText(nbt)}
                        "number" -> {getJmccNumber(nbt)}
                        "location" -> {getJmccLocation(nbt)}
                        "vector" -> {getJmccVector(nbt)}
                        "sound" -> {getJmccSound(nbt)}
                        "particle" -> {getJmccParticle(nbt)}
                        "potion" -> {getJmccPotion(nbt)}
                        "game_value" -> {getJmccValue(nbt)}
                        "array" -> {getJmccArray(nbt)}
                        "map" -> {getJmccMap(nbt)}
                        "variable" -> {getJmccVariable(nbt)}
                        else -> {
                            val valueCompound = value as NbtCompound
                            val base64 = valueCompound.getString("item").getOrNull() as String
                            val item = decodeItem(base64)
                            val jmccItem = disassembleItem(item)
                            jmccItem
                        }
                    }
                )
            }
        }

        return prettyPrintJmcc(prettyPrint(array_jmcc))
    }
    fun prettyPrint(value: Any?, indent: String = "      "): String {
        return when (value) {
            is List<*> -> {
                val inner = value.joinToString(",\n") {
                    prettyPrint(it, "$indent  ")
                }
                "[\n$inner\n$indent]"
            }
            is String -> {
                if (value.contains("\n") || value.contains("(")) {
                    value.lines().joinToString("\n") { "$indent$it" }
                } else {
                    "$indent$value"
                }
            }
            is Map<*, *> -> {
                val inner = value.entries.joinToString(",\n") { (k, v) ->
                    val formattedValue = if (v is Map<*, *> || v is List<*>) {
                        "\n" + prettyPrint(v, "")
                    } else {
                        prettyPrint(v, "")
                    }
                    "$indent$k: $formattedValue"
                }
                "{\n$inner\n$indent}"
            }
            else -> "$indent$value"
        }
    }
    fun getJmccMap(tags: NbtCompound): String {
        val value = tags.getCompound("value")?.getOrNull()
        val valuesMap = value?.getCompound("values")?.getOrNull()

        val jmcc_map = mutableMapOf<String, String>()
        valuesMap?.forEach { key, value ->
            val json = key.substringAfter("_")
            val jsonObj = Json.parseToJsonElement(json)
            val mapKey = jsonToAny(jsonObj) as Map<*, *>

            val nbtKey = mapToNbt(mapKey)
            val keyCompound = NbtCompound()
            keyCompound.put("value", nbtKey)
            val keyType = getCreativeType(keyCompound)
            val trueMapKey: String
            trueMapKey = when (keyType) {
                "text" -> {getJmccText(keyCompound)}
                "number" -> {getJmccNumber(keyCompound)}
                "location" -> {getJmccLocation(keyCompound)}
                "vector" -> {getJmccVector(keyCompound)}
                "sound" -> {getJmccSound(keyCompound)}
                "particle" -> {getJmccParticle(keyCompound)}
                "potion" -> {getJmccPotion(keyCompound)}
                "game_value" -> {getJmccValue(keyCompound)}
                "array" -> {getJmccArray(keyCompound)}
                "map" -> {getJmccMap(keyCompound)}
                "variable" -> {getJmccVariable(keyCompound)}
                else -> {
                    val valueCompound = value as NbtCompound
                    val base64 = valueCompound.getString("item").getOrNull() as String
                    val item = decodeItem(base64)
                    val jmccItem = disassembleItem(item)
                    jmccItem
                }
            }
            val valueCompound = NbtCompound()
            valueCompound.put("value", value)
            val valueType = getCreativeType(valueCompound)
            val trueMapValue: String
            trueMapValue = when (valueType) {
                "text" -> {getJmccText(valueCompound)}
                "number" -> {getJmccNumber(valueCompound)}
                "location" -> {getJmccLocation(valueCompound)}
                "vector" -> {getJmccVector(valueCompound)}
                "sound" -> {getJmccSound(valueCompound)}
                "particle" -> {getJmccParticle(valueCompound)}
                "potion" -> {getJmccPotion(valueCompound)}
                "game_value" -> {getJmccValue(valueCompound)}
                "array" -> {getJmccArray(valueCompound)}
                "map" -> {getJmccMap(valueCompound)}
                "variable" -> {getJmccVariable(valueCompound)}
                else -> {
                    val valueCompound = value as NbtCompound
                    val base64 = valueCompound.getString("item").getOrNull() as String
                    val item = decodeItem(base64)
                    val jmccItem = disassembleItem(item)
                    jmccItem
                }
            }

            jmcc_map[trueMapKey] = trueMapValue
        }

        return prettyPrintJmcc(prettyPrint(jmcc_map))
    }

    fun mapToNbt(map: Map<*, *>): NbtCompound {
        val compound = NbtCompound()

        for ((k, v) in map) {
            val key = k.toString()

            when (v) {
                null -> {}

                is String -> compound.putString(key, v)

                is Int -> compound.putInt(key, v)
                is Double -> compound.putDouble(key, v)
                is Float -> compound.putFloat(key, v)
                is Long -> compound.putLong(key, v)
                is Boolean -> compound.putBoolean(key, v)

                is Map<*, *> -> {
                    compound.put(key, mapToNbt(v))
                }
                is List<*> -> {
                    val list = NbtList()

                    for (item in v) {
                        when (item) {
                            is String -> list.add(NbtString.of(item))
                            is Int -> list.add(NbtInt.of(item))
                            is Double -> list.add(NbtDouble.of(item))
                            is Float -> list.add(NbtFloat.of(item))
                            is Long -> list.add(NbtLong.of(item))
                            is Boolean -> list.add(NbtByte.of(if (item) 1 else 0))
                            is Map<*, *> -> list.add(mapToNbt(item))
                        }
                    }

                    compound.put(key, list)
                }

                is Number -> {
                    compound.putDouble(key, v.toDouble())
                }

                else -> {
                    compound.putString(key, v.toString())
                }
            }
        }

        return compound
    }

    fun decodeItem(data: String): ItemStack {
        val compressed = Base64.getDecoder().decode(data)
        val nbt = NbtIo.readCompressed(ByteArrayInputStream(compressed), NbtSizeTracker.ofUnlimitedBytes())

        return ItemStack.CODEC
            .parse(NbtOps.INSTANCE, nbt)
            .result()
            .orElse(ItemStack.EMPTY)
    }

    fun prettyPrintJmcc(input: String, indentStep: Int = 2): String {
        val sb = StringBuilder()
        var indent = 0
        var i = 0
        var inString = false
        val whitespace = setOf(' ', '\n', '\t', '\r')

        fun appendIndent() {
            repeat(indent) { sb.append(' ') }
        }

        while (i < input.length) {
            val c = input[i]

            if (!inString && (c == '"' || (c == 'm' && i + 1 < input.length && input[i + 1] == '"'))) {
                val start = i
                if (c == 'm') i++
                inString = true
                sb.append(input.substring(start, i + 1))
                i++
                while (i < input.length) {
                    sb.append(input[i])
                    if (input[i] == '"' && input[i - 1] != '\\') {
                        inString = false
                        i++
                        break
                    }
                    i++
                }
                continue
            }

            if (inString) {
                sb.append(c)
                i++
                continue
            }

            val skipFuncs = setOf("vector", "location", "particle", "sound")

            if (c.isLetter() || c == '_') {
                val funcMatch = Regex("""\w+\(""").find(input.substring(i))
                if (funcMatch != null && funcMatch.range.first == 0) {
                    val funcName = funcMatch.value.dropLast(1)
                    if (funcName in skipFuncs) {
                        val start = i
                        while (i < input.length && input[i] != ')') i++
                        if (i < input.length) i++
                        sb.append(input.substring(start, i))
                        continue
                    }

                    val start = i
                    var level = 0
                    while (i < input.length) {
                        if (input[i] == '(') level++
                        else if (input[i] == ')') level--
                        i++
                        if (level == 0) break
                    }
                    sb.append(input.substring(start, i))
                    continue
                }
            }

            when (c) {
                '{', '[' -> {
                    sb.append(c)
                    sb.append('\n')
                    indent += indentStep
                    appendIndent()
                }
                '}', ']' -> {
                    sb.append('\n')
                    indent -= indentStep
                    appendIndent()
                    sb.append(c)
                }
                ',' -> {
                    sb.append(c)
                    sb.append('\n')
                    appendIndent()
                }
                else -> {
                    if (c !in whitespace) sb.append(c)
                }
            }

            i++
        }

        return sb.toString()
    }
    fun unwrapOptional(value: Any?): Any? {
        return when (value) {
            is Optional<*> -> unwrapOptional(value.orElse(null))
            is Map<*, *> -> value.mapValues { unwrapOptional(it.value) }
            is List<*> -> value.map { unwrapOptional(it) }
            else -> value
        }
    }
    fun toSnbt(value: Any?): String = when(value) {
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${toSnbt(v)}"   // Ключ теперь тоже в кавычках
        }
        is List<*> -> value.joinToString(",", "[", "]") { toSnbt(it) }
        is String -> "\"$value\""
        else -> value.toString()
    }
}
