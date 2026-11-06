package eu.teele.neugesplitter.ui.category

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.teele.neugesplitter.R
import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.category.Category

@StringRes
fun categoryNameResource(category: BuiltInCategory): Int =
    when (category) {
        BuiltInCategory.HOUSING -> R.string.category_housing
        BuiltInCategory.UTILITIES -> R.string.category_utilities
        BuiltInCategory.GROCERIES -> R.string.category_groceries
        BuiltInCategory.DINING_AND_CAFES -> R.string.category_dining_and_cafes
        BuiltInCategory.TRANSPORTATION -> R.string.category_transportation
        BuiltInCategory.HEALTH -> R.string.category_health
        BuiltInCategory.INSURANCE -> R.string.category_insurance
        BuiltInCategory.SHOPPING -> R.string.category_shopping
        BuiltInCategory.ENTERTAINMENT -> R.string.category_entertainment
        BuiltInCategory.HOBBIES -> R.string.category_hobbies
        BuiltInCategory.SPORT -> R.string.category_sport
        BuiltInCategory.PERSONAL_CARE -> R.string.category_personal_care
        BuiltInCategory.FAMILY -> R.string.category_family
        BuiltInCategory.EDUCATION -> R.string.category_education
        BuiltInCategory.TRAVEL -> R.string.category_travel
        BuiltInCategory.PETS -> R.string.category_pets
        BuiltInCategory.GIFTS_AND_DONATIONS -> R.string.category_gifts_and_donations
        BuiltInCategory.FEES_AND_TAXES -> R.string.category_fees_and_taxes
        BuiltInCategory.SAVINGS_AND_INVESTMENTS -> R.string.category_savings_and_investments
        BuiltInCategory.TRANSFER -> R.string.category_transfer
    }

@Composable
fun categoryName(category: Category): String =
    category.name ?: stringResource(categoryNameResource(checkNotNull(category.builtin)))
