package com.radium.inkwell.core.source

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 把书源挡在内网之外（SSRF 防护）。
 *
 * 书源是用户从网上导入的第三方规则，它想抓哪个 URL 就抓哪个 —— 这本来就是它的职责。
 * 但「任意 URL」里包含了运行设备所在的那张局域网：`http://192.168.1.1/`（路由器后台）、
 * `http://127.0.0.1:xxxx/`（本机上别的应用开的调试口、其它 App 的本地服务）。
 * 这些地址在公网上打不到，恰恰因此常常疏于防护 —— 而书源脚本还能把抓到的内容
 * 用 `java.ajax` 发回自己的服务器。手机成了跳板。
 *
 * **为什么拦在 DNS 层而不是拦 URL 字符串**：只看 URL 里的 host 挡不住两件事 ——
 * 一是域名本来就能解析到内网地址（`localtest.me` 之流解析到 127.0.0.1），
 * 二是 DNS 重绑定（第一次解析给公网 IP 骗过校验，OkHttp 真连时再解析到内网）。
 * 在 Dns 这一层过滤的是**真正要去连的那个 IP**，上面两条一起堵住。
 *
 * 这会顺带挡掉「自建局域网书源」这种小众用法，所以留了 [allowPrivate] 开关，
 * 但默认必须是拦 —— 安全默认值不该由是否有人抱怨来决定。
 */
class PrivateNetworkGuardDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val allowPrivate: Boolean = false,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = delegate.lookup(hostname)
        if (allowPrivate) return resolved
        // 全部过滤掉才算拒绝。只要有一个公网地址就放行，但**只把公网那些交给 OkHttp** ——
        // 否则一个既解析到公网又解析到内网的域名，仍可能被连到内网那条上。
        val public = resolved.filterNot { it.isPrivate() }
        if (public.isEmpty()) {
            throw UnknownHostException("拒绝访问内网地址: $hostname")
        }
        return public
    }
}

/**
 * 是否属于「不该让第三方书源碰」的地址段。
 *
 * 直接用 JDK 的判定，别自己拆 IP 字符串 —— IPv6、IPv4-mapped、各种简写形式手写必漏。
 * `isSiteLocalAddress` 覆盖 10/8、172.16/12、192.168/16；回环、链路本地（169.254/16 与 fe80::）、
 * 任意地址（0.0.0.0）、多播各有专门判定。
 */
internal fun InetAddress.isPrivate(): Boolean =
    isLoopbackAddress ||
        isAnyLocalAddress ||
        isLinkLocalAddress ||
        isSiteLocalAddress ||
        isMulticastAddress ||
        // 100.64/10 运营商级 NAT，以及 IPv6 唯一本地地址 fc00::/7 —— JDK 没有现成判定
        isCarrierGradeNat() ||
        isUniqueLocalIpv6()

private fun InetAddress.isCarrierGradeNat(): Boolean {
    val b = address
    return b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xFF) in 64..127
}

private fun InetAddress.isUniqueLocalIpv6(): Boolean {
    val b = address
    return b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
}
