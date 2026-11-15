package eu.teele.neugesplitter.storage

/** What tidying up a group's history proposes for an entry (TI-1). */
enum class Proposal(internal val key: String) {
    /** Restating an expense as the income it stands for (TI-2). */
    INCOME("income"),

    /** Giving an uncategorised entry a category (TI-3). */
    CATEGORY("category"),
}
