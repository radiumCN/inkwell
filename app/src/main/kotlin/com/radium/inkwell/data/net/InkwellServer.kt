package com.radium.inkwell.data.net

/**
 * Inkwell 中转服务器。
 *
 * 应用内更新（`api/v1/update` 下各渠道）与意见反馈（`api/v1/feedback`）共用同一台。
 * 抽成一处是因为两边硬编码同一个域名迟早会漂：换域名时漏改一处，
 * 症状会是「更新好好的，反馈全失败」这类看不出关联的怪事。
 */
object InkwellServer {
    const val BASE = "https://book-server.skylark.run"
}
