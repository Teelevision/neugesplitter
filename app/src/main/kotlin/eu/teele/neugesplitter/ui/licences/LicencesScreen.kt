package eu.teele.neugesplitter.ui.licences

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.teele.neugesplitter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Something the app ships that others made, and the licence it ships under.
 *
 * @property name unique among [NOTICES].
 * @property files its full licence texts, under `assets/licences/`.
 * @property modules the `group:module` prefixes of the release build's modules it covers.
 * @property adaptedAs what the app made of it, for data shipped changed (NOTICE).
 */
internal data class Notice(
    val name: String,
    val licence: String,
    val files: List<String>,
    val modules: List<String> = emptyList(),
    @StringRes val adaptedAs: Int? = null,
)

/** What the release build ships, by its runtime classpath (LicencesTest), and the word lists. */
internal val NOTICES =
    listOf(
        Notice(
            "AndroidX, Jetpack Compose",
            "Apache-2.0",
            listOf("androidx.txt"),
            listOf("androidx."),
        ),
        Notice("Kotlin", "Apache-2.0", listOf("kotlin.txt"), listOf("org.jetbrains.kotlin:")),
        Notice(
            "kotlinx.coroutines",
            "Apache-2.0",
            listOf("kotlinx-coroutines.txt"),
            listOf("org.jetbrains.kotlinx:kotlinx-coroutines-"),
        ),
        Notice(
            "kotlinx.collections.immutable",
            "Apache-2.0",
            listOf("kotlinx-collections-immutable.txt"),
            listOf("org.jetbrains.kotlinx:kotlinx-collections-immutable"),
        ),
        Notice(
            "kotlinx.serialization",
            "Apache-2.0",
            listOf("kotlinx-serialization.txt"),
            listOf("org.jetbrains.kotlinx:kotlinx-serialization-"),
        ),
        Notice(
            "JetBrains Java Annotations",
            "Apache-2.0",
            listOf("jetbrains-annotations.txt"),
            listOf("org.jetbrains:annotations"),
        ),
        Notice(
            "SQLDelight",
            "Apache-2.0",
            listOf("sqldelight.txt"),
            listOf("app.cash.sqldelight:"),
        ),
        Notice("OkHttp", "Apache-2.0", listOf("okhttp.txt"), listOf("com.squareup.okhttp3:")),
        Notice("Okio", "Apache-2.0", listOf("okio.txt"), listOf("com.squareup.okio:")),
        Notice(
            "kage",
            "MIT, Apache-2.0",
            listOf("kage-mit.txt", "kage-apache.txt"),
            listOf("com.github.android-password-store:kage"),
        ),
        Notice("Bouncy Castle", "MIT", listOf("bouncycastle.txt"), listOf("org.bouncycastle:")),
        Notice("HKDF", "Apache-2.0", listOf("hkdf.txt"), listOf("at.favre.lib:hkdf")),
        Notice(
            "kotlin-result",
            "ISC",
            listOf("kotlin-result.txt"),
            listOf("com.michael-bull.kotlin-result:"),
        ),
        Notice("zxcvbn4j", "MIT", listOf("zxcvbn4j.txt"), listOf("com.nulab-inc:zxcvbn")),
        Notice(
            "Guava ListenableFuture",
            "Apache-2.0",
            listOf("guava.txt"),
            listOf("com.google.guava:listenablefuture"),
        ),
        Notice("JSpecify", "Apache-2.0", listOf("jspecify.txt"), listOf("org.jspecify:")),
        Notice(
            "diceware · Uli Fouquet",
            "CC BY 3.0",
            listOf("cc-by-3.0.txt"),
            adaptedAs = R.string.words_german,
        ),
        Notice(
            "EFF word lists · Electronic Frontier Foundation",
            "CC BY 3.0 US",
            listOf("cc-by-3.0-us.txt"),
            adaptedAs = R.string.words_english,
        ),
    )

/** Every [Notice], each opening its full licence text (ui-design 3). */
@Composable
fun LicencesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Screen(stringResource(R.string.third_party), onBack, modifier) { padding ->
        LazyColumn(contentPadding = padding) {
            items(NOTICES, key = { it.name }) { notice ->
                val adapted =
                    notice.adaptedAs?.let {
                        " · ${stringResource(it)} · ${stringResource(R.string.adapted)}"
                    }
                ListItem(
                    headlineContent = { Text(notice.name) },
                    supportingContent = { Text(notice.licence + adapted.orEmpty()) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpen(notice.name) },
                )
            }
        }
    }
}

/** The full licence texts of the [Notice] called [name], one after the other. */
@Composable
fun LicenceScreen(name: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val notice = NOTICES.first { it.name == name }
    val assets = LocalContext.current.assets
    val texts by
        produceState(emptyList<List<String>>(), notice) {
            value =
                withContext(Dispatchers.IO) {
                    notice.files.map { file ->
                        assets.open("licences/$file").bufferedReader().use { unwrap(it.readText()) }
                    }
                }
        }
    Screen(notice.name, onBack, modifier) { padding ->
        LazyColumn(
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                )
        ) {
            texts.forEachIndexed { i, paragraphs ->
                if (i > 0) item { HorizontalDivider(Modifier.padding(vertical = 16.dp)) }
                items(paragraphs) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** A licence text's paragraphs, with the line breaks hard-wrapped for 80 columns taken out. */
internal fun unwrap(text: String): List<String> =
    text
        .replace("\r\n", "\n")
        .split(Regex("\n\\s*\n"))
        .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
        .filter { it.isNotEmpty() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        content(padding)
    }
}
