package com.qinhuai.ruins.structure

import com.qinhuai.ruins.core.RelPos
import org.bukkit.block.data.BlockData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 结构补丁：只改动 structure.nbt 里被编辑的那几个方块，不重新 capture 整座遗迹。
 * 这样结构里原有的占位方块（barrier、告示牌等）和其它未改动的方块都原样保留。
 */
object StructurePatch {

    /** 返回实际改动的方块数。edits 里 value=null 表示删除该方块。 */
    fun apply(file: File, edits: Map<RelPos, BlockData?>): Int {
        if (edits.isEmpty() || !file.exists()) return 0
        val root = LinkedHashMap(read(file))
        @Suppress("UNCHECKED_CAST")
        val size = (root["size"] as? List<*>)?.map { (it as Number).toInt() } ?: return 0
        val palette = ArrayList<MutableMap<String, Any>>()
        for (p in (root["palette"] as? List<*>) ?: return 0) palette.add(LinkedHashMap(p as Map<String, Any>))
        val blocks = ArrayList<MutableMap<String, Any>>()
        for (b in (root["blocks"] as? List<*>) ?: return 0) blocks.add(LinkedHashMap(b as Map<String, Any>))

        val removals = HashSet<String>()
        val sets = HashMap<String, Int>()
        for ((rel, data) in edits) {
            if (rel.x !in 0 until size[0] || rel.y !in 0 until size[1] || rel.z !in 0 until size[2]) continue
            val key = key(rel.x, rel.y, rel.z)
            if (data == null) removals.add(key) else sets[key] = paletteIndex(palette, data)
        }
        if (removals.isEmpty() && sets.isEmpty()) return 0

        var changed = 0
        val kept = ArrayList<Map<String, Any>>(blocks.size)
        for (b in blocks) {
            val pos = b["pos"] as? List<*> ?: continue
            val k = key((pos[0] as Number).toInt(), (pos[1] as Number).toInt(), (pos[2] as Number).toInt())
            if (k in removals) { changed++; continue }
            val newState = sets.remove(k)
            if (newState != null) {
                if ((b["state"] as? Number)?.toInt() != newState) { b["state"] = newState; changed++ }
            }
            kept.add(b)
        }
        for ((k, state) in sets) {
            val p = k.split(',')
            kept.add(linkedMapOf("state" to state, "pos" to listOf(p[0].toInt(), p[1].toInt(), p[2].toInt())))
            changed++
        }
        root["palette"] = palette
        root["blocks"] = kept
        // 先写临时文件再替换，写一半失败也不会把原结构弄坏；首次修改留一份 .bak
        val tmp = File(file.parentFile, file.name + ".tmp")
        write(tmp, root)
        val bak = File(file.parentFile, file.name + ".bak")
        if (!bak.exists()) file.copyTo(bak, overwrite = true)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        return changed
    }

    private fun key(x: Int, y: Int, z: Int) = "$x,$y,$z"

    private fun paletteIndex(palette: MutableList<MutableMap<String, Any>>, data: BlockData): Int {
        val raw = data.asString
        val name: String
        val props: Map<String, String>
        val bracket = raw.indexOf('[')
        if (bracket >= 0) {
            name = raw.substring(0, bracket)
            props = raw.substring(bracket + 1, raw.length - 1)
                .split(',')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
                }
                .toMap()
        } else {
            name = raw
            props = emptyMap()
        }
        val entry = linkedMapOf<String, Any>("Name" to name)
        if (props.isNotEmpty()) entry["Properties"] = LinkedHashMap<String, Any>(props)
        for (i in palette.indices) {
            val existing = palette[i]
            if ((existing["Name"] as? String) != name) continue
            @Suppress("UNCHECKED_CAST")
            val ep = (existing["Properties"] as? Map<String, Any>) ?: emptyMap()
            if (ep.size == props.size && props.all { (k, v) -> ep[k] == v }) return i
        }
        palette.add(entry)
        return palette.size - 1
    }

    // ---- NBT 读写（结构文件只有 Compound/List/String/Int 这几种，足够） ----

    private fun read(file: File): Map<String, Any> {
        val gzip = file.inputStream().use { it.read() == 0x1f && it.read() == 0x8b }
        val stream = if (gzip) GZIPInputStream(file.inputStream().buffered()) else file.inputStream().buffered()
        DataInputStream(stream).use { input ->
            val type = input.readByte().toInt()
            require(type == 10) { "根标签不是 Compound" }
            input.readUTF()
            return readCompound(input)
        }
    }

    private fun readCompound(input: DataInputStream): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        while (true) {
            val type = input.readByte().toInt()
            if (type == 0) break
            val name = input.readUTF()
            map[name] = readPayload(input, type)
        }
        return map
    }

    private fun readPayload(input: DataInputStream, type: Int): Any = when (type) {
        1 -> input.readByte()
        2 -> input.readShort()
        3 -> input.readInt()
        4 -> input.readLong()
        5 -> input.readFloat()
        6 -> input.readDouble()
        7 -> ByteArray(input.readInt()).also { input.readFully(it) }
        8 -> input.readUTF()
        9 -> {
            val elemType = input.readByte().toInt()
            val len = input.readInt()
            val list = ArrayList<Any>(len.coerceAtLeast(0))
            repeat(len) { list.add(readPayload(input, elemType)) }
            list
        }
        10 -> readCompound(input)
        11 -> IntArray(input.readInt()) { input.readInt() }
        12 -> LongArray(input.readInt()) { input.readLong() }
        else -> error("未知 NBT 标签类型 $type")
    }

    private fun write(file: File, root: Map<String, Any>) {
        file.parentFile?.mkdirs()
        DataOutputStream(GZIPOutputStream(file.outputStream())).use { out ->
            out.writeByte(10)
            out.writeUTF("")
            writeCompound(out, root)
            out.flush()
        }
    }

    private fun writeCompound(out: DataOutputStream, map: Map<String, Any>) {
        for ((name, value) in map) writeTag(out, name, value)
        out.writeByte(0)
    }

    private fun writeTag(out: DataOutputStream, name: String, value: Any) {
        when (value) {
            is Byte -> { out.writeByte(1); out.writeUTF(name); out.writeByte(value.toInt()) }
            is Short -> { out.writeByte(2); out.writeUTF(name); out.writeShort(value.toInt()) }
            is Int -> { out.writeByte(3); out.writeUTF(name); out.writeInt(value) }
            is Long -> { out.writeByte(4); out.writeUTF(name); out.writeLong(value) }
            is Float -> { out.writeByte(5); out.writeUTF(name); out.writeFloat(value) }
            is Double -> { out.writeByte(6); out.writeUTF(name); out.writeDouble(value) }
            is ByteArray -> { out.writeByte(7); out.writeUTF(name); out.writeInt(value.size); out.write(value) }
            is String -> { out.writeByte(8); out.writeUTF(name); out.writeUTF(value) }
            is IntArray -> {
                out.writeByte(11); out.writeUTF(name); out.writeInt(value.size)
                for (v in value) out.writeInt(v)
            }
            is LongArray -> {
                out.writeByte(12); out.writeUTF(name); out.writeInt(value.size)
                for (v in value) out.writeLong(v)
            }
            is List<*> -> {
                val type = when (value.firstOrNull()) {
                    is Map<*, *> -> 10
                    is String -> 8
                    is Byte -> 1
                    is Short -> 2
                    is Int -> 3
                    is Long -> 4
                    is Float -> 5
                    is Double -> 6
                    else -> 3
                }
                out.writeByte(9); out.writeUTF(name); out.writeByte(type); out.writeInt(value.size)
                for (e in value) writePayload(out, e, type)
            }
            is Map<*, *> -> {
                out.writeByte(10); out.writeUTF(name)
                @Suppress("UNCHECKED_CAST")
                writeCompound(out, value as Map<String, Any>)
            }
            else -> { out.writeByte(8); out.writeUTF(name); out.writeUTF(value.toString()) }
        }
    }

    private fun writePayload(out: DataOutputStream, value: Any?, type: Int) {
        when (type) {
            1 -> out.writeByte((value as? Number)?.toInt() ?: 0)
            2 -> out.writeShort((value as? Number)?.toInt() ?: 0)
            3 -> out.writeInt((value as? Number)?.toInt() ?: 0)
            4 -> out.writeLong((value as? Number)?.toLong() ?: 0L)
            5 -> out.writeFloat((value as? Number)?.toFloat() ?: 0f)
            6 -> out.writeDouble((value as? Number)?.toDouble() ?: 0.0)
            8 -> out.writeUTF(value?.toString() ?: "")
            10 -> {
                @Suppress("UNCHECKED_CAST")
                writeCompound(out, (value as? Map<String, Any>) ?: emptyMap())
            }
            7 -> { val b = value as? ByteArray ?: ByteArray(0); out.writeInt(b.size); out.write(b) }
            else -> out.writeInt((value as? Number)?.toInt() ?: 0)
        }
    }
}
