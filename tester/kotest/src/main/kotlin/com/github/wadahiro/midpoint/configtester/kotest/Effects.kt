package com.github.wadahiro.midpoint.configtester.kotest

import com.github.wadahiro.midpoint.configtester.Claims
import com.github.wadahiro.midpoint.configtester.Effects

/**
 * Verifies the block's effects exhaustively: the checks in [block] claim what they select, and anything the block
 * changed, wrote to a connector, mailed or logged as an error that no check claimed fails afterwards
 * ([Claims.verifyAll]).
 *
 * ```
 * t.effectsOf { t.task("Nightly renewal").run() }.verifyAll {
 *     changes().of("jdoe").single().only("activation/validTo")
 *     mails().to("jdoe@example.com").withSubject("renewed").single()
 * }
 * ```
 */
fun Effects.verifyAll(block: Claims.() -> Unit): Effects {
    val claims = claims()
    claims.block()
    return claims.verifyAll()
}
