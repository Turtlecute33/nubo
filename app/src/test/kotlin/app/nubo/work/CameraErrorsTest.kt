package app.nubo.work

import app.nubo.api.ApiException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review 38: only an error of the item counts as a camera upload try; account and folder errors tell the user. */
class CameraErrorsTest {
    @Test
    fun accountAndFolderErrorsDoNotCountAndHaveAText() {
        for (code in listOf("max_storage_reached", "storage_limit_reached", "folder_not_found", "invalid_folder", "parent_folder_not_found", "api_key_not_found")) {
            val e = ApiException(code, 200)
            assertFalse(itemError(e), code)
            assertNotNull(accountProblem(e), code)
        }
        assertEquals("Your Filen storage is full", accountProblem(ApiException("max_storage_reached", 200)))
    }

    @Test
    fun networkAndServerErrorsDoNotCount() {
        assertFalse(itemError(IOException("timeout")))
        assertFalse(itemError(ApiException("server_error", 502)))
        assertFalse(itemError(ApiException("error", 429)))
        assertFalse(itemError(ApiException("rate_limited", 200)))
        assertNull(accountProblem(IOException("timeout")))
        assertNull(accountProblem(ApiException("server_error", 502)))
    }

    @Test
    fun onlyARefusalOfTheItemCounts() {
        assertTrue(itemError(ApiException("invalid_params", 400)))
        assertNull(accountProblem(ApiException("invalid_params", 400)))
        // A bug in the app (like the one of review 34) must not skip photos.
        assertFalse(itemError(IllegalArgumentException("bug")))
    }
}
