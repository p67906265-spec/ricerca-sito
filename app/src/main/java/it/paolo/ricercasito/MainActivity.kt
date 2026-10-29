package it.paolo.ricercasito

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

data class Site(val name: String, val template: String, val selector: String)
data class Result(val title: String, val url: String, val snippet: String)

class SiteStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("sites", Context.MODE_PRIVATE)

    fun load(): List<Site> {
        val a = JSONArray(p.getString("list", "[]"))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            Site(o.getString("n"), o.getString("t"), o.optString("s", "a"))
        }
    }

    fun save(list: List<Site>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("t", it.template).put("s", it.selector)) }
        p.edit().putString("list", a.toString()).apply()
    }
}

fun fetchResults(site: Site, q: String): List<Result> {
    val url = site.template.replace("{q}", URLEncoder.encode(q, "UTF-8"))
    val doc = Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
        .timeout(20000)
        .followRedirects(true)
        .get()
    val css = site.selector.ifBlank { "a" }
    val seen = HashSet<String>()
    val out = ArrayList<Result>()
    for (el in doc.select(css)) {
        val a = if (el.hasAttr("href")) el else (el.selectFirst("a[href]") ?: continue)
        val href = a.absUrl("href")
        if (!href.startsWith("http") || !seen.add(href)) continue
        val title = a.text().ifBlank { el.text() }.trim().take(160)
        if (title.isBlank()) continue
        val around = el.parent()?.text()?.trim().orEmpty()
        val snippet = if (around.length > title.length + 10) around.take(240) else ""
        out.add(Result(title, href, snippet))
    }
    return out
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SiteStore(this)
        setContent { MaterialTheme { App(store) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: SiteStore) {
    var sites by remember { mutableStateOf(store.load()) }
    var sel by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var dialogIndex by remember { mutableStateOf<Int?>(null) } // -1 = nuovo, >=0 = modifica
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun search() {
        val site = sites.getOrNull(sel)
        if (site == null) { message = "Aggiungi prima un sito"; return }
        if (query.isBlank()) return
        loading = true
        message = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { fetchResults(site, query.trim()) }
                results = r
                if (r.isEmpty()) message = "Nessun risultato trovato. Controlla l'indirizzo o il selettore CSS."
            } catch (e: Exception) {
                results = emptyList()
                message = "Errore: ${e.message}"
            }
            loading = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Ricerca Sito") }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(sites) { i, s ->
                        FilterChip(
                            selected = i == sel,
                            onClick = { sel = i; results = emptyList(); message = null },
                            label = { Text(s.name) }
                        )
                    }
                }
                if (sites.isNotEmpty()) {
                    TextButton(onClick = { dialogIndex = sel }) { Text("Modifica") }
                }
                TextButton(onClick = { dialogIndex = -1 }) { Text("+ Sito") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Parole da cercare") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() })
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { search() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (loading) "Ricerca in corso..." else "Cerca")
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (sites.isEmpty()) {
                Text("Nessun sito configurato. Tocca \"+ Sito\" e inserisci l'indirizzo di ricerca.",
                    Modifier.padding(top = 16.dp))
            }
            message?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }

            LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { r ->
                    Card(Modifier.fillMaxWidth().clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (r.snippet.isNotBlank()) {
                                Text(r.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            }
                            Text(Uri.parse(r.url).host.orEmpty(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }

    dialogIndex?.let { idx ->
        val editing = idx >= 0 && idx < sites.size
        val base = if (editing) sites[idx] else Site("", "", "a")
        var name by remember { mutableStateOf(base.name) }
        var template by remember { mutableStateOf(base.template) }
        var selector by remember { mutableStateOf(base.selector) }
        val valid = name.isNotBlank() && template.startsWith("http") && template.contains("{q}")
        AlertDialog(
            onDismissRequest = { dialogIndex = null },
            title = { Text(if (editing) "Modifica sito" else "Nuovo sito") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(template, { template = it }, label = { Text("Indirizzo di ricerca") },
                        supportingText = { Text("Usa {q} dove va la parola. Es: https://sito.it/search?q={q}") })
                    OutlinedTextField(selector, { selector = it }, label = { Text("Selettore CSS (opzionale)") },
                        supportingText = { Text("Es: .risultato a, h3 a. Vuoto = tutti i link") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    val s = Site(name.trim(), template.trim(), selector.trim().ifBlank { "a" })
                    sites = if (editing) sites.toMutableList().also { it[idx] = s } else sites + s
                    if (!editing) sel = sites.size - 1
                    store.save(sites)
                    dialogIndex = null
                }) { Text("Salva") }
            },
            dismissButton = {
                Row {
                    if (editing) TextButton(onClick = {
                        sites = sites.toMutableList().also { it.removeAt(idx) }
                        sel = 0
                        store.save(sites)
                        results = emptyList()
                        dialogIndex = null
                    }) { Text("Elimina") }
                    TextButton(onClick = { dialogIndex = null }) { Text("Annulla") }
                }
            }
        )
    }
}
