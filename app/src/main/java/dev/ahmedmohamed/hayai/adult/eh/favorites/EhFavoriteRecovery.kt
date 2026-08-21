package dev.ahmedmohamed.hayai.adult.eh.favorites

sealed interface EhRemoteRecoveryDecision {
    data object AlreadyApplied : EhRemoteRecoveryDecision
    data object Execute : EhRemoteRecoveryDecision
    data class Conflict(val actual: EhFavoriteState?) : EhRemoteRecoveryDecision
}

object EhFavoriteRecovery {
    fun decide(
        actual: EhFavoriteState?,
        expected: EhFavoriteState?,
        desired: EhFavoriteState?,
    ): EhRemoteRecoveryDecision = when {
        sameRemoteState(actual, desired) -> EhRemoteRecoveryDecision.AlreadyApplied
        sameRemoteState(actual, expected) -> EhRemoteRecoveryDecision.Execute
        else -> EhRemoteRecoveryDecision.Conflict(actual)
    }

    fun sameRemoteState(first: EhFavoriteState?, second: EhFavoriteState?): Boolean =
        if (first == null || second == null) first == null && second == null else first.gallery == second.gallery && first.category == second.category
}
