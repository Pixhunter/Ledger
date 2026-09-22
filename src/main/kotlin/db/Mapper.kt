package org.example.db

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jooq.JSONB

object Mapper {
    val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun <T> toJsonb(value: T): JSONB = JSONB.valueOf(mapper.writeValueAsString(value))

    inline fun <reified T> fromJsonb(json: JSONB?): T? =
        json?.data()?.let { mapper.readValue<T>(it) }
}
