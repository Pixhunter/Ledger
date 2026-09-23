package org.example.model

interface IdKey<TYPE> {
    val id: TYPE
}

interface EnumId : IdKey<Short>

inline fun <reified T> enumById(id: Short): T where T : Enum<T>, T : EnumId =
    enumValues<T>().firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("no ${T::class.simpleName} with id $id")
