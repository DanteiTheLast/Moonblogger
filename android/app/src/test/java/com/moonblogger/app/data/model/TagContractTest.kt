package com.moonblogger.app.data.model
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
class TagContractTest {
 @Test fun `unchanged tags are omitted while explicit empty selection is sent`() {
  val request = PostRequest("Title", "Content")
  assertFalse(Json.parseToJsonElement(Json.encodeToString(request)).jsonObject.containsKey("tag_slugs"))
  assertEquals("[]", Json.parseToJsonElement(Json.encodeToString(request.copy(tag_slugs = emptyList()))).jsonObject["tag_slugs"].toString())
 }
 @Test fun `multiple tags use stable slugs without hashtags`() {
  val text = Json.encodeToString(PostRequest("Title", "Content", tag_slugs = listOf("viajes", "dan")))
  assertTrue(text.contains("\"tag_slugs\":[\"viajes\",\"dan\"]"))
 }
}
