package com.nascon.league.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nascon.league.data.CartLine
import com.nascon.league.data.CheckoutResponse
import com.nascon.league.data.Loaded
import com.nascon.league.data.Product
import com.nascon.league.data.ServiceLocator
import com.nascon.league.data.Variant
import com.nascon.league.data.friendlyError
import com.nascon.league.ui.LoadViewModel
import com.nascon.league.ui.UiState
import com.nascon.league.ui.components.Banner
import com.nascon.league.ui.components.DialogTags
import com.nascon.league.ui.components.BannerKind
import com.nascon.league.ui.components.ErrorState
import com.nascon.league.ui.components.LoadingState
import com.nascon.league.ui.components.MessageState
import com.nascon.league.ui.components.Pill
import com.nascon.league.ui.components.PrimaryButton
import com.nascon.league.ui.components.RefreshButton
import com.nascon.league.ui.components.SavedDataNotice
import com.nascon.league.ui.components.ScreenHeader
import com.nascon.league.ui.components.SectionLabel
import com.nascon.league.ui.humanize
import com.nascon.league.ui.money
import com.nascon.league.ui.theme.Condensed
import com.nascon.league.ui.theme.Pitch
import com.nascon.league.ui.theme.contentOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Test cards for the mock payment gateway (spec 5.12). */
enum class TestCard(val token: String, val title: String, val hint: String) {
    Pays("tok_success", "Card that pays", "Payment goes through"),
    Declined("tok_declined", "Card that's declined", "The bank refuses the payment"),
    CaptureFails("tok_capture_fail", "Card that fails after approval", "Approved, then the charge fails"),
}

/** Shared by all store screens: catalogue, cart, checkout and the last receipt. */
class StoreViewModel : LoadViewModel<List<Product>>() {
    private val repo = ServiceLocator.repo
    val cart = MutableStateFlow<Map<String, Int>>(emptyMap()) // variant_id -> quantity
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val lastOrder = MutableStateFlow<CheckoutResponse?>(null)

    override suspend fun fetch(): Loaded<List<Product>> = repo.products()

    init { load() }

    fun products(): List<Product> = (state.value as? UiState.Ready)?.data.orEmpty()

    fun variant(id: String): Pair<Product, Variant>? =
        products().firstNotNullOfOrNull { p -> p.variants.firstOrNull { it.variant_id == id }?.let { p to it } }

    fun add(variantId: String, quantity: Int) {
        val now = cart.value[variantId] ?: 0
        cart.value = cart.value + (variantId to (now + quantity).coerceAtMost(10))
    }

    fun remove(variantId: String) { cart.value = cart.value - variantId }

    fun subtotal(): Long = cart.value.entries.sumOf { (id, qty) -> (variant(id)?.second?.price_minor ?: 0L) * qty }

    fun checkout(discountCode: String, card: TestCard, onPaid: () -> Unit) {
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val lines = cart.value.map { (id, qty) -> CartLine(id, qty) }
                lastOrder.value = repo.checkout(lines, discountCode, card.token)
                cart.value = emptyMap()
                load() // stock changed
                onPaid()
            } catch (e: Exception) {
                error.value = friendlyError(e)
                load()
            } finally {
                busy.value = false
            }
        }
    }

    fun clearError() { error.value = null }
}

// ---------- Catalogue ----------

@Composable
fun StoreScreen(vm: StoreViewModel, onOpenProduct: (String) -> Unit, onOpenCart: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Store", subtitle = "Official club gear", actions = {
            RefreshButton(vm::load)
            CartButton(cart.values.sum(), onOpenCart)
        })
        when (val s = state) {
            UiState.Loading -> LoadingState("Loading the store")
            is UiState.Failed -> ErrorState(s.message, vm::load)
            is UiState.Ready -> {
                if (s.data.isEmpty()) {
                    MessageState(Icons.Filled.ShoppingCart, "Nothing for sale yet", "Club gear shows up here once the club adds products.")
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("product_list")) {
                        item { SavedDataNotice(s.savedAt) }
                        items(s.data, key = { it.product_id }) { product ->
                            ProductRow(product) { onOpenProduct(product.product_id) }
                            HorizontalDivider(Modifier.padding(start = 20.dp), color = Pitch.Line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartButton(count: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("open_cart")) {
        Icon(Icons.Filled.ShoppingCart, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(if (count == 0) "Cart" else "Cart ($count)")
    }
}

/** Generated product art: the item's initial on the tenant colour, so the list isn't a wall of text. */
@Composable
private fun ProductArt(product: Product, size: Int) {
    val brand = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)).background(brand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            product.name.take(1).uppercase(),
            color = contentOn(brand),
            fontFamily = Condensed,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun ProductRow(product: Product, onClick: () -> Unit) {
    val inStock = product.variants.sumOf { it.stock }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open product", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp).testTag("product_${product.product_id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductArt(product, 64)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (product.category.isNotBlank()) {
                Text(humanize(product.category), style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
            }
            Spacer(Modifier.height(4.dp))
            product.variants.minOfOrNull { it.price_minor }?.let {
                Text(money(it), style = MaterialTheme.typography.titleSmall)
            }
        }
        if (inStock == 0) Text("Sold out", style = MaterialTheme.typography.labelMedium, color = Pitch.Whistle)
    }
}

// ---------- Product detail ----------

@Composable
fun ProductScreen(vm: StoreViewModel, productId: String, onBack: () -> Unit, onOpenCart: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val product = (state as? UiState.Ready)?.data?.firstOrNull { it.product_id == productId }
    var selected by remember(product) { mutableStateOf(product?.variants?.firstOrNull { it.stock > 0 }) }
    var quantity by remember { mutableIntStateOf(1) }
    var added by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = product?.name ?: "Product", onBack = onBack, actions = { CartButton(cart.values.sum(), onOpenCart) })
        if (product == null) {
            if (state is UiState.Loading) LoadingState() else ErrorState("This item isn't in the store any more.", vm::load)
            return
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ProductArt(product, 140) }
            Spacer(Modifier.height(16.dp))
            Text(selected?.let { money(it.price_minor, it.currency) } ?: "", style = MaterialTheme.typography.headlineSmall)
            product.description?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(20.dp))
            Text("Choose a size or style", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                product.variants.forEach { v ->
                    val name = v.label.ifBlank { listOfNotNull(v.size, v.color).joinToString(" ") }.ifBlank { v.variant_id }
                    Pill(
                        text = if (v.stock > 0) name else "$name, sold out",
                        selected = selected?.variant_id == v.variant_id,
                        enabled = v.stock > 0,
                        onClick = { selected = v; quantity = 1 },
                        modifier = Modifier.testTag("variant_${v.variant_id}"),
                    )
                }
            }
            selected?.let { v ->
                Text(
                    if (v.stock <= 3) "Only ${v.stock} left" else "${v.stock} in stock",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (v.stock <= 3) Pitch.Whistle else Pitch.Muted,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("Quantity", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { if (quantity > 1) quantity-- }, enabled = quantity > 1, modifier = Modifier.size(48.dp).testTag("qty_minus"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Text("$quantity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp).testTag("qty_value"))
                val max = minOf(10, selected?.stock ?: 1)
                OutlinedButton(onClick = { if (quantity < max) quantity++ }, enabled = quantity < max, modifier = Modifier.size(48.dp).testTag("qty_plus"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "More")
                }
            }
            added?.let {
                Spacer(Modifier.height(12.dp))
                Banner(it, BannerKind.Success, inset = false) { added = null }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            PrimaryButton(
                text = selected?.let { "Add to cart, ${money(it.price_minor * quantity)}" } ?: "Sold out",
                enabled = selected != null,
                onClick = {
                    selected?.let { vm.add(it.variant_id, quantity); added = "Added $quantity to your cart." }
                },
                modifier = Modifier.fillMaxWidth().testTag("add_to_cart"),
            )
        }
    }
}

// ---------- Cart & checkout ----------

@Composable
fun CartScreen(vm: StoreViewModel, onBack: () -> Unit, onPaid: () -> Unit) {
    val cart by vm.cart.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var discount by remember { mutableStateOf("") }
    var card by remember { mutableStateOf(TestCard.Pays) }
    var confirm by remember { mutableStateOf(false) }
    val subtotal = vm.subtotal()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Your cart", onBack = onBack)
        if (cart.isEmpty()) {
            MessageState(Icons.Filled.ShoppingCart, "Your cart is empty", "Add club gear from the store and it shows up here.", "Back to the store", onBack)
            return
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            cart.forEach { (id, qty) ->
                val (product, variant) = vm.variant(id) ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(product.name, style = MaterialTheme.typography.titleMedium)
                        Text("${variant.label.ifBlank { variant.variant_id }}, quantity $qty", color = Pitch.Muted)
                    }
                    Text(money(variant.price_minor * qty), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { vm.remove(id) }, modifier = Modifier.testTag("remove_$id")) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${product.name}")
                    }
                }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = Pitch.Line)
            }

            SectionLabel("Discount code")
            OutlinedTextField(
                value = discount,
                onValueChange = { discount = it },
                placeholder = { Text("Optional, for example WELCOME10") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("discount_input"),
            )

            SectionLabel("Payment")
            Text(
                "This store uses test cards. Pick how the payment should go.",
                color = Pitch.Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            TestCard.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = card == option, onClick = { card = option }, role = Role.RadioButton)
                        .heightIn(min = 56.dp).padding(horizontal = 8.dp)
                        .testTag("card_${option.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = card == option, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                    Column {
                        Text(option.title, style = MaterialTheme.typography.titleSmall)
                        Text(option.hint, style = MaterialTheme.typography.bodyMedium, color = Pitch.Muted)
                    }
                }
            }
            error?.let { Banner(it, BannerKind.Error, onDismiss = vm::clearError) }
            Spacer(Modifier.height(12.dp))
        }

        // Sticky total + pay button: the most important action on the screen.
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row {
                Text("Subtotal", modifier = Modifier.weight(1f), color = Pitch.Muted)
                Text(money(subtotal), style = MaterialTheme.typography.titleMedium)
            }
            if (discount.isNotBlank()) {
                Text("Your discount is applied when you pay.", style = MaterialTheme.typography.labelMedium, color = Pitch.Muted)
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = if (busy) "Paying" else "Pay ${money(subtotal)}",
                busy = busy,
                onClick = { confirm = true },
                modifier = Modifier.fillMaxWidth().testTag("pay_button"),
            )
        }
    }

    if (confirm) {
        AlertDialog(
            modifier = DialogTags,
            onDismissRequest = { confirm = false },
            title = { Text("Pay ${money(subtotal)}?") },
            text = { Text("You'll pay with the test card: ${card.title.lowercase()}.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.checkout(discount, card, onPaid) }, modifier = Modifier.testTag("confirm_pay")) {
                    Text("Pay now")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ---------- Receipt ----------

@Composable
fun ReceiptScreen(vm: StoreViewModel, onDone: () -> Unit) {
    val order by vm.lastOrder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pdfError by remember { mutableStateOf<String?>(null) }
    val o = order ?: run {
        MessageState(Icons.Filled.ShoppingCart, "No receipt yet", "Your receipt appears here after you pay.", "Back to the store", onDone)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("receipt_screen")) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Pitch.Win, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(8.dp))
            Text("Payment complete", style = MaterialTheme.typography.headlineMedium)
            Text("Order ${o.order_id}", color = Pitch.Muted, modifier = Modifier.testTag("receipt_order_id"))
            o.receipt.payload["paid_at"]?.jsonPrimitive?.contentOrNull?.let { Text("Paid $it", color = Pitch.Muted) }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(14.dp))
                .border(1.dp, Pitch.Line, RoundedCornerShape(14.dp)).background(Color.White).padding(16.dp),
        ) {
            o.items.forEach { item ->
                val name = vm.variant(item.variant_id)?.let { (p, v) -> "${p.name}, ${v.label.ifBlank { v.variant_id }}" } ?: item.variant_id
                ReceiptLine("$name × ${item.quantity}", money(item.unit_price_minor * item.quantity))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Pitch.Line)
            ReceiptLine("Subtotal", money(o.subtotal_minor))
            if (o.discount_minor > 0) ReceiptLine("Discount", "−" + money(o.discount_minor))
            ReceiptLine("Total paid", money(o.total_minor), bold = true)
        }
        Text(
            "Signed receipt ${o.receipt.signature.take(12)}…",
            style = MaterialTheme.typography.labelMedium,
            color = Pitch.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        pdfError?.let { Banner(it, BannerKind.Error) { pdfError = null } }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = "Open PDF receipt",
                onClick = { pdfError = openPdf(context, o, share = false) },
                modifier = Modifier.fillMaxWidth().testTag("open_pdf"),
            )
            OutlinedButton(
                onClick = { pdfError = openPdf(context, o, share = true) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("share_pdf"),
            ) { Text("Save or share PDF") }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("receipt_done")) { Text("Back to the store") }
        }
    }
}

@Composable
private fun ReceiptLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
        Text(value, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
    }
}

/** Writes the server-generated PDF to app storage and opens or shares it. Returns an error message or null. */
private fun openPdf(context: Context, order: CheckoutResponse, share: Boolean): String? {
    return try {
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val file = File(dir, "receipt-${order.order_id}.pdf")
        file.writeBytes(Base64.decode(order.receipt.pdf_base64, Base64.DEFAULT))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = if (share) {
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Save or share receipt",
            )
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "No PDF viewer is installed. Use \"Save or share PDF\" instead."
    } catch (_: Exception) {
        "The receipt file couldn't be opened."
    }
}
