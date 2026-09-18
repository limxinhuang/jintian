package com.jintian.app.domain

import java.math.BigDecimal

/** Decimal text on disk, exact decimal arithmetic in memory (never binary floating point). */
object Quantity {
    fun parse(text: String): BigDecimal {
        val value = text.trim()
        require(value.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,6})?"))) { "请输入正数，最多 12 位整数、6 位小数" }
        return value.toBigDecimal().also { require(it.signum() > 0) { "数值必须大于 0" } }
    }
    fun format(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
    fun normalize(text: String): String = format(parse(text))
    fun unit(text: String): String = text.trim().also {
        require(it.length in 1..12 && it.none(Char::isISOControl)) { "单位需为 1～12 个字，如页、公里、个" }
    }
}
