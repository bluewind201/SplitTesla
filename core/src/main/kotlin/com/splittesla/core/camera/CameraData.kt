package com.splittesla.core.camera

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads the "전국무인교통단속카메라표준데이터" CSV from data.go.kr. The file is
 * usually CP949; columns are found by their Korean header names so column
 * order changes between releases do not matter.
 */
object CameraCsv {
    fun parse(bytes: ByteArray): List<SpeedCamera> = parse(decode(bytes))

    fun parse(text: String): List<SpeedCamera> {
        val rows = splitCsv(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val lat = col("위도") ?: error("CSV에 '위도' 열이 없습니다")
        val lon = col("경도") ?: error("CSV에 '경도' 열이 없습니다")
        val limit = col("제한속도")
        val id = col("무인교통단속카메라관리번호", "관리번호")
        val place = col("설치장소")
        val road = col("소재지도로명주소")
        val sectionPos = col("단속구간위치구분")
        val sectionLen = col("과속단속구간길이")
        val zone = col("보호구역구분")
        return rows.drop(1).mapIndexedNotNull { i, r ->
            fun at(c: Int?) = c?.let { r.getOrNull(it)?.trim() }.orEmpty()
            camera(
                id = at(id).ifEmpty { "row$i" },
                lat = at(lat), lon = at(lon), limit = at(limit),
                place = at(place).ifEmpty { at(road) },
                section = at(sectionPos), sectionLength = at(sectionLen), zone = at(zone),
            )
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("MS949"))
        }
    }

    /** RFC 4180 split: quoted fields may hold commas, quotes and newlines. */
    private fun splitCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row.add(field.toString()); field.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(ch)
            }
            i++
        }
        row.add(field.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }
}

/**
 * Builds a camera from raw dataset values, or null when the row has no usable
 * position or speed limit. Only cameras with a speed limit trigger alerts;
 * signal-only cameras carry no limit.
 */
internal fun camera(
    id: String,
    lat: String,
    lon: String,
    limit: String,
    place: String,
    section: String,
    sectionLength: String,
    zone: String = "",
): SpeedCamera? {
    val la = lat.toDoubleOrNull() ?: return null
    val lo = lon.toDoubleOrNull() ?: return null
    if (la !in 32.0..39.5 || lo !in 124.0..132.0) return null
    val kmh = limit.toDoubleOrNull()?.toInt()?.takeIf { it in 10..150 } ?: return null
    val role = when {
        section.contains("시작") || section == "1" -> SectionRole.START
        section.contains("종료") || section.contains("끝") || section == "2" -> SectionRole.END
        else -> null
    }
    return SpeedCamera(
        id, la, lo, kmh, place, role, sectionLength.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
        zone.trim().takeIf { it.endsWith("보호구역") },
    )
}

/** Compact storage so the app does not re-parse the CSV on every start. */
object CameraStore {
    fun encode(cameras: List<SpeedCamera>): String = buildString {
        cameras.forEach { c ->
            append(listOf(c.id, c.latitude, c.longitude, c.limitKmh, c.section?.name.orEmpty(), c.sectionLengthM ?: "", c.place.replace('\t', ' '), c.zone.orEmpty())
                .joinToString("\t"))
            append('\n')
        }
    }

    fun decode(text: String): List<SpeedCamera> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 7) return@mapNotNull null
        SpeedCamera(
            id = f[0], latitude = f[1].toDouble(), longitude = f[2].toDouble(), limitKmh = f[3].toInt(),
            section = f[4].takeIf { it.isNotEmpty() }?.let(SectionRole::valueOf),
            sectionLengthM = f[5].toIntOrNull(), place = f[6], zone = f.getOrNull(7)?.takeIf { it.isNotEmpty() },
        )
    }.toList()
}
