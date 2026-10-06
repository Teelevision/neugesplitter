package eu.teele.neugesplitter.domain

import eu.teele.neugesplitter.domain.value.MemberId

/** Member identifiers that are stable and readable in failure output. */
fun member(n: Int): MemberId = MemberId.parse("%032x".format(n))

val ANNA = member(1)
val BEN = member(2)
val CLARA = member(3)
val MARIUS = member(4)
val NORA = member(5)
val OTTO = member(6)
