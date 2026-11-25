package io.github.siloverse.brokerage.ledger.domain.transfer.exception

import io.github.siloverse.brokerage.ledger.domain.account.model.UnbalancedAsset

class UnbalancedTransferException(val transferId: Long, val unbalancedAssets: List<UnbalancedAsset>) :
    IllegalStateException("transfer $transferId does not sum to zero: $unbalancedAssets")