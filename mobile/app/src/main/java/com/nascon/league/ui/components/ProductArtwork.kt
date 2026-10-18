package com.nascon.league.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nascon.league.R
import com.nascon.league.data.Product
import com.nascon.league.ui.theme.Pitch

/** The same transparent artwork is used in the catalogue, details and staff shop. */
@Composable
fun ProductArtwork(product: Product, modifier: Modifier = Modifier) {
    val image = when {
        product.product_id == "demo-product-pass" || product.name.contains("season pass", true) -> R.drawable.season_pass
        product.product_id == "demo-product-scarf" || product.name.contains("scarf", true) -> R.drawable.supporter_scarf
        product.product_id == "demo-product-duffel" || product.name.contains("duffel", true) -> R.drawable.training_duffel
        product.product_id == "demo-product-bottle" || product.name.contains("bottle", true) -> R.drawable.hydration_bottle
        product.name.contains("jersey", true) -> R.drawable.club_jersey
        else -> null
    }
    if (image != null) Image(painterResource(image), contentDescription = product.name,
        contentScale = ContentScale.Fit, modifier = modifier.fillMaxSize().padding(12.dp))
    else Text(product.name.take(1).uppercase(), color = Pitch.Ink, style = MaterialTheme.typography.displayMedium)
}
