package com.r3corda.contracts.cash

import com.r3corda.core.contracts.Amount
import com.r3corda.core.contracts.Issued
import com.r3corda.core.contracts.OwnableState
import java.security.PublicKey

/**
 * Common elements of cash contract states.
 */
interface FungibleAssetState<T, I> : OwnableState {
    val issuanceDef: I
    val productAmount: Amount<T>
    fun move(amount: Amount<T>, owner: PublicKey): FungibleAssetState<T, I>
}