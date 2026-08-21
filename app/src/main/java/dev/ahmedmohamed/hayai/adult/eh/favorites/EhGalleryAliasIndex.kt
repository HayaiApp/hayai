package dev.ahmedmohamed.hayai.adult.eh.favorites

import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryAlias
import dev.ahmedmohamed.hayai.adult.eh.persistence.EhGalleryIdentity

class EhGalleryAliasIndex(aliases: Collection<EhGalleryAlias>) {
    private val canonicalByIdentity: Map<EhGalleryIdentity, EhGalleryIdentity>
    private val membersByCanonical: Map<EhGalleryIdentity, Set<EhGalleryIdentity>>

    init {
        val direct = linkedMapOf<EhGalleryIdentity, EhGalleryIdentity>()
        aliases.forEach { alias ->
            val previous = direct.putIfAbsent(alias.alternate, alias.canonical)
            require(previous == null || previous == alias.canonical) { "An alternate gallery has conflicting canonical owners." }
        }
        val resolved = linkedMapOf<EhGalleryIdentity, EhGalleryIdentity>()
        (direct.keys + direct.values).forEach { identity ->
            val seen = linkedSetOf<EhGalleryIdentity>()
            var current = identity
            while (true) {
                require(seen.add(current)) { "E-Hentai gallery aliases contain a cycle." }
                current = direct[current] ?: break
            }
            resolved[identity] = current
        }
        canonicalByIdentity = resolved
        membersByCanonical = resolved.entries.groupBy({ it.value }, { it.key }).mapValues { (canonical, members) -> members.toSet() + canonical }
    }

    fun canonical(identity: EhGalleryIdentity): EhGalleryIdentity = canonicalByIdentity[identity] ?: identity

    fun equivalents(identity: EhGalleryIdentity): Set<EhGalleryIdentity> {
        val canonical = canonical(identity)
        return membersByCanonical[canonical] ?: setOf(canonical)
    }
}

