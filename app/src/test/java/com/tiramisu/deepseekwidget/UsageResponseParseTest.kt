package com.tiramisu.deepseekwidget

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Preflight guard for the by_api_key responses.
 *
 * `bucket` is an opaque granularity descriptor. The API reference shows it as a STRING
 * ("1d"), but a wrong DTO type must never break deserialization of the WHOLE response —
 * that would take down both widgets' data.
 *
 * Regression history (probed against Gson 2.10.1):
 *  - `Long?` bucket + "1d"  → silently read as 1 (Java's Double.parseDouble treats 'd' as a
 *    double suffix, so Long.parseLong fails then the double fallback to 1.0 succeeds);
 *  - `Long?` bucket + "1h"  → throws JsonSyntaxException → entire response lost.
 */
class UsageResponseParseTest {

    private val gson = Gson()

    /** Bucket representations the field must tolerate: documented string, other granularities,
     *  a raw number, and a numeric string. */
    private val bucketValues = listOf("\"1d\"", "\"1h\"", "\"1w\"", "\"1mo\"", "86400", "\"86400\"")

    private fun amountJson(bucket: String) = """
        {"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":{
        "start":1,"end":2,"bucket":$bucket,"models":["deepseek-v4-flash"],
        "series":[{"api_key":{"tracking_id":"t","name":"RanClaw","sensitive_id":"s"},
        "model":"deepseek-v4-flash","buckets":[{"time":1000,"usage":{
        "PROMPT_CACHE_HIT_TOKEN":"1","PROMPT_CACHE_MISS_TOKEN":"2","RESPONSE_TOKEN":"3","REQUEST":"4"}}]}]}}}
    """.trimIndent()

    private fun costJson(bucket: String) = """
        {"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":{
        "start":1,"end":2,"bucket":$bucket,"models":["deepseek-v4-flash"],
        "data":[{"currency":"CNY","series":[{"api_key":{"tracking_id":"t","name":"RanClaw","sensitive_id":"s"},
        "model":"deepseek-v4-flash","buckets":[{"time":1000,"cost":"1.85"}]}]}]}}}
    """.trimIndent()

    @Test
    fun amountResponse_parsesForEveryBucketRepresentation() {
        for (raw in bucketValues) {
            val resp = gson.fromJson(amountJson(raw), UsageByKeyAmountResponse::class.java)
            assertEquals("bucket=$raw", 0, resp.code)
            assertEquals("bucket=$raw", 1, resp.data?.bizData?.series?.size)
        }
    }

    @Test
    fun costResponse_parsesForEveryBucketRepresentation() {
        for (raw in bucketValues) {
            val resp = gson.fromJson(costJson(raw), UsageByKeyCostResponse::class.java)
            assertEquals("bucket=$raw", 0, resp.code)
            assertEquals("bucket=$raw", 1, resp.data?.bizData?.data?.firstOrNull()?.series?.size)
        }
    }

    @Test
    fun amountResponse_keepsSeriesModelAndApiKeyMetadata() {
        val resp = gson.fromJson(amountJson("\"1d\""), UsageByKeyAmountResponse::class.java)
        val series = resp.data?.bizData?.series?.firstOrNull()
        assertEquals("deepseek-v4-flash", series?.model)
        val key = series?.apiKey
        assertNotNull(key)
        assertEquals("RanClaw", key!!.asJsonObject.get("name").asString)
    }
}
