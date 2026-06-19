package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException
import org.junit.Test
import org.junit.Assert

class RetryTest {

    @Test
    void succeedsOnFirstTry() {
        int calls = 0
        def out = Retry.execute(Retry.linear(3, 0L), { calls++; return 'ok' } as Closure)
        Assert.assertEquals('ok', out)
        Assert.assertEquals(1, calls)
    }

    @Test
    void retriesUntilMax() {
        int calls = 0
        try {
            Retry.execute(Retry.linear(3, 0L), {
                calls++
                if (calls < 3) throw new RuntimeException('transient')
                return 'ok'
            } as Closure)
            Assert.assertEquals(3, calls)
        } catch (ApexCIException ex) {
            Assert.fail("Should not throw on 3rd attempt")
        }
    }

    @Test
    void givesUpAfterMaxAttempts() {
        int calls = 0
        try {
            Retry.execute(Retry.linear(2, 0L), {
                calls++
                throw new RuntimeException('always fail')
            } as Closure)
            Assert.fail("Expected ApexCIException")
        } catch (ApexCIException ex) {
            Assert.assertTrue(ex.message.contains('exhausted'))
            Assert.assertEquals(2, calls)
        }
    }
}
