@file:OptIn(ExperimentalStdlibApi::class)

package dev.uint0.trickroom.pg.proxy

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.UUID
import java.util.concurrent.Executors

enum class AuthStatus(val code: Int) {
    OK(0)
}

enum class QueryStatus(val indicator: Char) {
    IDLE('I')
}

data class ColumnDef(
    val name: String,
    val tableOid: Int,
    val colNo: Short,
    val typeOid: Int,
    val typeLen: Short,
    val typeMod: Int,
    val format: Short,
)

class Handler(private val client: AsynchronousSocketChannel) : CompletionHandler<Int, ByteBuffer> {
    private fun ByteBuffer.putAsciiChar(c: Char) = put(c.code.toByte())
    private val Int.b: Byte get() = toByte()
    private fun AsynchronousSocketChannel.writeBlocking(buf: ByteBuffer) = write(buf).get()

    override fun completed(result: Int, attachment: ByteBuffer) {
        attachment.flip()
        if(result != -1) {
            handleMessage(attachment)
        }
        attachment.clear()
        client.read(attachment, attachment, this)
    }

    override fun failed(exc: Throwable, attachment: ByteBuffer) {
        println("failed")
    }

    private fun handleMessage(buf: ByteBuffer) {
        println("[HANDLER] Received message $buf")

        when {
            isQueryRequest(buf) -> {
                println("[HANDLER] Got Simple Query Request")
                getQueryInfo(buf)
                val columns = listOf("id", "username", "age")
                val data = (1..50).map {
                    listOf(UUID.randomUUID().toString(), "test-$it", it)
                }

                client.writeBlocking(respForRowDescription(columns)).also { println("wrote mock row description") }
            }
            isSSLReq(buf) -> {
                println("[HANDLER] Got SSL Request")
                client.writeBlocking(respForSSLNego(useSSL = false)).also { println("wrote ssl nego") }
            }
            isStartupReq(buf) -> {
                println("[HANDLER] Got Startup Request")
                client.writeBlocking(respForAuth(authStatus = AuthStatus.OK)).also { println("wrote auth status") }
                client.writeBlocking(respForBackendKeyData(pid = 1234, secret = 5678)).also { println("wrote key data") }
                client.writeBlocking(respForReadyForQuery(status = QueryStatus.IDLE)).also { println("wrote query status") }
            }
            else -> {
                println("[HANDLER] Unknown message: ${buf.get(0).toHexString()}")
            }
        }
    }

    private fun respForRowDescription(columnDefs: List<ColumnDef>): ByteBuffer {
        val len = 4 + 2 + columnDefs.sumOf { 4 + 2 + 4 + 2 + 4 + 2 + it.name.length + 1 }
        return ByteBuffer.allocate(len + 1).apply {
            putAsciiChar('T')
            putInt(len)
            putShort(columnDefs.size.toShort())

            columnDefs.forEach {
                put(it.name.toByteArray(Charsets.UTF_8))
                put(0.b)

                putInt(it.tableOid)
                putShort(it.colNo)
                putInt(it.typeOid)
                putShort(it.typeLen)
                putInt(it.typeMod)
                putShort(it.format)
            }

            flip()
        }
    }

    private fun respForRow(data: List<*>): ByteBuffer {
        return ByteBuffer.allocate(len + 1).apply {
            putAsciiChar('D')
            putInt(len)^

            putShort(data.size.toShort())

        }
    }

    private fun respForCommandComplete() {

    }

    private fun getQueryInfo(b: ByteBuffer) {
        val len = b.getInt(1)
        val queryBytes = b.slice(5, len - 5)
        val queryString = Charsets.UTF_8.decode(queryBytes)
        println("[HANDLER/query] Got query: $queryString")
    }

    private fun isQueryRequest(b: ByteBuffer) = b.get(0) == 'Q'.code.b

    private fun isSSLReq(b: ByteBuffer) = b.getInt(4) == 0x04d2162f

    private fun isStartupReq(b: ByteBuffer): Boolean {
        val len = b.getInt(0)
        val payload = b.slice(4, len - 4)
        val proto = payload.getInt()
        val params = Charsets.UTF_8.decode(payload.slice(4, len - 8)).split("\u0000").chunked(2).associate { (a, b) -> a to b }
        println("[HANDLER/debug] StartupReq {len=$len, proto=0x${proto.toHexString()}, params=$params}")
        return proto == 0x30000
    }

    private fun respForReadyForQuery(status: QueryStatus) =
        ByteBuffer.allocate(6).apply {
            putAsciiChar('Z')
            putInt(5)
            putAsciiChar(status.indicator)
            flip()
        }

    private fun respForBackendKeyData(pid: Int, secret: Int) =
        ByteBuffer.allocate(17).apply {
            putAsciiChar('K')
            putInt(12)
            putInt(pid)
            putInt(secret)
            flip()
        }

    private fun respForAuth(authStatus: AuthStatus) =
        ByteBuffer.allocate(9).apply {
            putAsciiChar('R')
            putInt(8)
            putInt(authStatus.code)
            flip()
        }

    private fun respForSSLNego(useSSL: Boolean): ByteBuffer {
        require(!useSSL)
        return ByteBuffer.allocate(1).apply {
            putAsciiChar('N')
            flip()
        }
    }
}

fun main() {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val chanGroup = AsynchronousChannelGroup.withThreadPool(executor)

    AsynchronousServerSocketChannel.open(chanGroup).use {
        it.bind(InetSocketAddress("127.0.0.1", 5432))
        println("[SERVER] Listening on 127.0.0.1:5432")

        while(true) {
            // TODO: suspend
            val t = it.accept()
            val c = t.get()

            val buf = ByteBuffer.allocate(65535)
            c.read(buf, buf, Handler(c))
        }
    }
}
