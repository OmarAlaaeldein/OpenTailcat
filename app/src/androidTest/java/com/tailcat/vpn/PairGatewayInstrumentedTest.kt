package com.tailcat.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only setup helper for live gateway tests without putting tokens in source control. */
@RunWith(AndroidJUnit4::class)
class PairGatewayInstrumentedTest {

    @Test
    fun pairTokenFromInstrumentationArgument() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val token = InstrumentationRegistry.getArguments().getString("token").orEmpty()
        require(token.isNotBlank()) { "Pass the live token with -e token <tc...>" }

        val application = instrumentation.targetContext.applicationContext as TailcatApplication
        val result = application.profileRepository.addOrUpdateFromToken("Live test gateway", token)
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)

		// EncryptedSharedPreferences uses apply(); keep the instrumentation process
		// alive long enough for its queued disk write before the runner exits.
		Thread.sleep(1_000)
		assertTrue(!application.preferencesStore.savedProfilesJson.isNullOrBlank())
		assertTrue(!application.preferencesStore.activeProfileId.isNullOrBlank())
    }
}
