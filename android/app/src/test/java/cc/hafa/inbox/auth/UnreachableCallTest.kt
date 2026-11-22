package cc.hafa.inbox.auth

import cc.hafa.inbox.mail.isOffline
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.FirebaseFunctionsException.Code
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class UnreachableCallTest {
    // The SDK's constructor is Kotlin-internal; the SDK makes these the same way.
    private fun functionsError(code: Code, cause: Throwable? = null): FirebaseFunctionsException {
        val type = FirebaseFunctionsException::class.java
        return if (cause == null) {
            type.getConstructor(String::class.java, Code::class.java, Any::class.java).newInstance(code.name, code, null)
        } else {
            type.getConstructor(String::class.java, Code::class.java, Any::class.java, Throwable::class.java)
                .newInstance(code.name, code, null, cause)
        }
    }

    @Test
    fun `a callable that never reached its server counts as offline`() {
        val noHost = functionsError(Code.INTERNAL, UnknownHostException("Unable to resolve host"))
        assertTrue(isUnreachableCall(noHost))
        assertTrue(isOffline(noHost))
        assertTrue(isUnreachableCall(functionsError(Code.DEADLINE_EXCEEDED, IOException())))
        assertTrue(isUnreachableCall(functionsError(Code.UNAVAILABLE)))
        // The token fetch passes it on as a network error, which every Gmail caller treats as offline.
        assertTrue(isOffline(UnreachableException(noHost)))
    }

    @Test
    fun `a server that answered with an error is not offline`() {
        val crashed = functionsError(Code.INTERNAL)
        assertFalse(isUnreachableCall(crashed))
        assertFalse(isOffline(crashed))
        assertFalse(isUnreachableCall(functionsError(Code.FAILED_PRECONDITION)))
    }
}
