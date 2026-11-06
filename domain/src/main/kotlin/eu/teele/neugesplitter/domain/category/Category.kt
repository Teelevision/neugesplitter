package eu.teele.neugesplitter.domain.category

import eu.teele.neugesplitter.domain.value.CategoryId

enum class BuiltInCategory(val key: String, val id: CategoryId, val emoji: String) {
    HOUSING("housing", CategoryId.parse("e27c346bf48a5e7fc3312e086ae54a1a"), "🏠"),
    UTILITIES("utilities", CategoryId.parse("9d1c8c53bbe11a0f34507d1f2c775398"), "💡"),
    GROCERIES("groceries", CategoryId.parse("a5c6e4e231756a27c3170a2e6da6059e"), "🛒"),
    DINING_AND_CAFES(
        "dining_and_cafes",
        CategoryId.parse("7de2f5f349dac2125cd903cb850d79c4"),
        "🍽️",
    ),
    TRANSPORTATION("transportation", CategoryId.parse("adc2a031cf5f9237d2051b32f3d95ee7"), "🚌"),
    HEALTH("health", CategoryId.parse("95e897ffb0ba843759a50ce0342830dd"), "🩺"),
    INSURANCE("insurance", CategoryId.parse("5025cc4ee7ab924c659c199c53c5f4e1"), "🛡️"),
    SHOPPING("shopping", CategoryId.parse("9a4dc6409cf4fae466a71c6687add748"), "🛍️"),
    ENTERTAINMENT("entertainment", CategoryId.parse("2d494e77b363c800d71ac3cd3cf8be15"), "🎬"),
    HOBBIES("hobbies", CategoryId.parse("6a38eecc911740048bf38ead10f5da8e"), "🎨"),
    SPORT("sport", CategoryId.parse("c6c4bb8043e34f779f796a7c96d8bbaa"), "⚽"),
    PERSONAL_CARE("personal_care", CategoryId.parse("62b5983e7ac63874ae75d53e5dfe9fb9"), "💇"),
    FAMILY("family", CategoryId.parse("32d04080c42a3ae1b0187286ceea72f7"), "👨‍👩‍👧‍👦"),
    EDUCATION("education", CategoryId.parse("bd94347b49ab4fa29e52c756536ba25f"), "🎓"),
    TRAVEL("travel", CategoryId.parse("f11d9201b625485754074620b1e706e2"), "🌍"),
    PETS("pets", CategoryId.parse("54a51b98ed699999033f8a066deb2062"), "🐾"),
    GIFTS_AND_DONATIONS(
        "gifts_and_donations",
        CategoryId.parse("39bcaa0f20466c9b20356f5045db0261"),
        "🎁",
    ),
    FEES_AND_TAXES("fees_and_taxes", CategoryId.parse("5b8151f0394ded76ba68fb8bdde37aca"), "🏛️"),
    SAVINGS_AND_INVESTMENTS(
        "savings_and_investments",
        CategoryId.parse("365b2e04c25cd8a1a3af4585a0c1f586"),
        "📈",
    ),
    TRANSFER("transfer", CategoryId.parse("2756a3df43883aaf1c05fd26d12ee5a9"), "↔️"),
}

/**
 * A group category. [name] is a shared override; a built-in with no override uses its localized
 * label. A custom category has no [builtin] and therefore must have a name.
 */
data class Category(
    val id: CategoryId,
    val builtin: BuiltInCategory? = null,
    val name: String? = null,
    val emoji: String,
    val deleted: Boolean = false,
) {
    init {
        require(name == null || name.isNotBlank()) { "a category name is not blank" }
        require(builtin != null || name != null) { "a custom category has a name" }
        require(emoji.isNotBlank()) { "a category has an emoji" }
    }
}

fun defaultCategories(): List<Category> =
    BuiltInCategory.entries.map { category ->
        Category(id = category.id, builtin = category, emoji = category.emoji)
    }
