package com.radium.inkwell.core.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 极简 WebDAV 客户端：只覆盖备份所需的 4 个动词（MKCOL/PUT/GET/PROPFIND）。
 * 不引第三方 sardine——年久失修且依赖重。
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val base = baseUrl.trimEnd('/')

    class WebDavException(val code: Int, message: String) : IOException(message)

    /** 建目录；已存在（405）视为成功 */
    suspend fun mkcol(path: String) {
        val resp = execute(request(path).method("MKCOL", null).build())
        resp.use {
            if (!it.isSuccessful && it.code != 405) {
                throw WebDavException(it.code, "MKCOL 失败: ${it.code}")
            }
        }
    }

    suspend fun put(path: String, bytes: ByteArray, contentType: String = "application/octet-stream") {
        val resp = execute(
            request(path).put(bytes.toRequestBody(contentType.toMediaType())).build()
        )
        resp.use {
            if (!it.isSuccessful) throw WebDavException(it.code, "PUT 失败: ${it.code}")
        }
    }

    /**
     * 取文件；不存在返回 null。
     *
     * 409 也当作不存在：GET 本身没有「冲突」语义，服务端回 409 只可能是**父目录不存在**
     * （坚果云就是这么回的，而不是 404）。首次同步时 inkwell/ 目录还没建，
     * 于是「读远端备份」直接报 `GET 失败: 409`，同步一次都成功不了。
     */
    suspend fun get(path: String): ByteArray? {
        val resp = execute(request(path).get().build())
        resp.use {
            return when {
                it.code == 404 || it.code == 409 -> null
                it.isSuccessful -> withContext(Dispatchers.IO) { it.body?.bytes() }
                else -> throw WebDavException(it.code, "GET 失败: ${it.code}")
            }
        }
    }

    /** PROPFIND Depth:1，返回子项名（不含自身） */
    suspend fun list(path: String): List<String> {
        val body = """<?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>"""
            .toRequestBody("application/xml".toMediaType())
        val resp = execute(
            request(path).method("PROPFIND", body).header("Depth", "1").build()
        )
        resp.use {
            if (it.code == 404) return emptyList()
            if (!it.isSuccessful && it.code != 207) {
                throw WebDavException(it.code, "PROPFIND 失败: ${it.code}")
            }
            val xml = withContext(Dispatchers.IO) { it.body?.string() } ?: return emptyList()
            return parseHrefs(xml, path)
        }
    }

    /** 连接与认证探活 */
    suspend fun check(): Result<Unit> = runCatching { list("") }.map { }

    private fun request(path: String): Request.Builder {
        val url = if (path.isBlank()) base else "$base/${path.trimStart('/')}"
        return Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(username, password))
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            })
        }

    /** 从 multistatus 里抽 href 的最后一段名；集合自身条目（以请求路径结尾）被剔除 */
    private fun parseHrefs(xml: String, selfPath: String): List<String> {
        val selfNorm = selfPath.trim('/').lowercase()
        return HREF_REGEX.findAll(xml)
            .map { java.net.URLDecoder.decode(it.groupValues[1].trim(), "UTF-8").trim('/') }
            .filter { it.isNotEmpty() }
            .filterNot { href -> href.lowercase().endsWith(selfNorm) && selfNorm.isNotEmpty() }
            .map { it.substringAfterLast('/') }
            .toList()
    }

    private companion object {
        val HREF_REGEX = Regex("<(?:[a-zA-Z0-9]+:)?href[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?href>", RegexOption.IGNORE_CASE)
    }
}
