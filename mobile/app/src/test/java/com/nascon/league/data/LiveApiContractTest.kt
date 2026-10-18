package com.nascon.league.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Opt-in tests against a disposable server, using the app's actual Retrofit DTOs. */
class LiveApiContractTest {
    private fun api(): LeagueApi {
        val base = System.getenv("ARENAONE_TEST_BASE_URL").orEmpty()
        assumeTrue("Set ARENAONE_TEST_BASE_URL to a disposable API", base.isNotBlank())
        // Stay below the server's 100 requests / ten seconds per-IP limit.
        return buildApi(base, buildHttpClient { token }.newBuilder().addInterceptor { chain ->
            Thread.sleep(120)
            chain.proceed(chain.request())
        }.build())
    }
    private var token: String? = null

    @Test fun everyRoleDecodesVisibleScreens() = runBlocking {
        val api = api()
        for (role in listOf("admin", "organizer", "manager", "player", "viewer")) {
            val login = api.login(LoginRequest("$role@nascon.test", "Nascon@26"))
            token = login.token
            assertTrue(api.leagues().isNotEmpty())
            assertTrue(api.clubs().isNotEmpty())
            assertEquals(4, api.highlights().map { it.sport_id }.distinct().size)
            for (fixture in api.highlights()) {
                val match = api.match(fixture.match_id)
                assertEquals(fixture.match_id, match.match_id)
                assertNotNull(match.scheduled_at)
                api.sport(match.sport_id)
            }
            api.fixtures("league-alpha-1")
            api.standings("league-alpha-1")
            api.club("club-alpha-1")
            assertTrue(api.products().isNotEmpty())
            api.checkoutPreferences()
            api.orders()
            if (role in listOf("admin", "manager")) assertTrue(api.managedProducts().isNotEmpty())
        }
    }

    @Test fun adminCreatesEverySportAndInventoryThroughAppClient() = runBlocking {
        val api = api()
        token = api.login(LoginRequest("admin@nascon.test", "Nascon@26")).token
        val firstKickoff = Instant.parse("2030-01-01T08:00:00Z").plusSeconds(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 20_000) * 86400L)
        for ((index, sport) in listOf("football", "basketball", "cricket", "esports").withIndex()) {
            val league = api.createLeague(buildJsonObject {
                put("name", "Contract $sport ${UUID.randomUUID()}"); put("sport_id", sport)
                put("teams", JsonArray(listOf("club-alpha-1", "club-alpha-2").map(::JsonPrimitive)))
            })
            val id = league.getValue("league_id").jsonPrimitive.content
            assertEquals(2, api.leagueManagement(id).getValue("teams").jsonArray.size)
            val match = api.createMatch(buildJsonObject {
                put("league_id", id); put("home_team_id", "club-alpha-1"); put("away_team_id", "club-alpha-2")
                put("scheduled_at", firstKickoff.plusSeconds(index * 86400L).toString())
                put("venue", "Contract court $index"); put("duration_minutes", 30)
            })
            assertEquals(sport, api.match(match.getValue("match_id").jsonPrimitive.content).sport_id)
        }
        val product = api.createProduct(buildJsonObject {
            put("name", "Contract bottle"); put("club_id", "club-alpha-1"); put("category", "accessories")
            put("variants", buildJsonArray { add(buildJsonObject {
                put("label", "Standard"); put("price_minor", 50000); put("stock", 3)
            }) })
        })
        val pid = product.getValue("product_id").jsonPrimitive.content
        val vid = product.getValue("variants").jsonArray.first().jsonObject.getValue("variant_id").jsonPrimitive.content
        api.updateVariant(pid, vid, buildJsonObject { put("stock", 4); put("price_minor", 60000) })
        assertEquals(4, api.managedProducts().first { it.product_id == pid }.variants.first().stock)
    }

    @Test fun buyerDecodesCheckoutPdfAndStaffFulfilment() = runBlocking {
        val api = api()
        val buyer = api.login(LoginRequest("player@nascon.test", "Nascon@26"))
        token = buyer.token
        val receipt = api.checkout(CheckoutRequest(
            order_id = "contract-${UUID.randomUUID()}", customer_id = buyer.user.user_id,
            items = listOf(CartLine("demo-variant-bottle", 1)), discount_code = null, payment = PaymentToken("tok_success"), receipt_email = ""
        ))
        assertTrue(java.util.Base64.getDecoder().decode(receipt.receipt.pdf_base64).decodeToString().startsWith("%PDF"))
        assertEquals("not_requested", api.receiptDelivery(receipt.order_id).status)
        token = api.login(LoginRequest("manager@nascon.test", "Nascon@26")).token
        for (state in listOf("packed", "shipped", "delivered", "refunded")) {
            val updated = api.orderStatus(receipt.order_id, buildJsonObject {
                put("status", state); put("request_id", "contract-${UUID.randomUUID()}")
            })
            assertEquals(state, updated.getValue("status").jsonPrimitive.content)
        }
    }
}
