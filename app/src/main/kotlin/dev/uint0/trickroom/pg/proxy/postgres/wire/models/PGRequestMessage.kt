package dev.uint0.trickroom.pg.proxy.postgres.wire.models

sealed interface PGRequestMessage
sealed class PGRequestGenericMessage(val tag: Char) : PGRequestMessage
sealed interface PGRequestStartupMessage : PGRequestMessage

data class PGRequestSSLNegotiation(
    val sslCode: Int
) : PGRequestStartupMessage

data class PGRequestStartup(
    val protocol: Int,
    val params: Map<String, String>
) : PGRequestStartupMessage

data class PGRequestSimpleQuery(
    val query: String
) : PGRequestGenericMessage(tag = 'Q')
