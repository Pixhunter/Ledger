package org.example.database

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jooq.JSONB

object JsonbMapper {

    val mapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun <T> toJsonb(value: T): JSONB = JSONB.valueOf(mapper.writeValueAsString(value))

    inline fun <reified T> fromJsonb(json: JSONB?): T? = json?.data()?.let { mapper.readValue<T>(it) }
}