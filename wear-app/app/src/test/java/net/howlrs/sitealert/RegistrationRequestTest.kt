package net.howlrs.sitealert

import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationRequestTest {
    @Test
    fun buildsRegisterJson() {
        val json = RegistrationRequest(101, 5, "tok:EN_1-2", "3f9a0c1d2e4b5a6f", "0.1.0").toJson()
        assertEquals(
            """{"site_id":101,"device_id":5,"fcm_token":"tok:EN_1-2","watch_id":"3f9a0c1d2e4b5a6f","app_version":"0.1.0"}""",
            json,
        )
    }

    @Test
    fun omitsMissingAppVersionAndEscapesStrings() {
        val json = RegistrationRequest(1, 2, "a\"b\\c\n", "w", null).toJson()
        assertEquals("""{"site_id":1,"device_id":2,"fcm_token":"a\"b\\c\u000a","watch_id":"w"}""", json)
    }

    @Test
    fun readsDuplicateCountFromResponse() {
        assertEquals(1, RegistrationResponse.duplicateCount("""{"status":"ok","duplicate_count": 1}"""))
        assertEquals(0, RegistrationResponse.duplicateCount("""{"status":"ok","duplicate_count":0}"""))
        // 旧版の API は項目を返さない
        assertEquals(0, RegistrationResponse.duplicateCount("""{"status":"ok","site_id":101}"""))
        assertEquals(0, RegistrationResponse.duplicateCount(""))
    }

    @Test
    fun mapsStatusCodesToOutcomes() {
        assertEquals(RegistrationOutcome.SUCCESS, RegistrationOutcome.fromStatus(200))
        assertEquals(RegistrationOutcome.REJECTED, RegistrationOutcome.fromStatus(400))
        assertEquals(RegistrationOutcome.REJECTED, RegistrationOutcome.fromStatus(401))
        assertEquals(RegistrationOutcome.RETRY, RegistrationOutcome.fromStatus(408))
        assertEquals(RegistrationOutcome.RETRY, RegistrationOutcome.fromStatus(429))
        assertEquals(RegistrationOutcome.RETRY, RegistrationOutcome.fromStatus(500))
        assertEquals(RegistrationOutcome.RETRY, RegistrationOutcome.fromStatus(503))
    }
}
