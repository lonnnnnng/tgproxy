package dev.tgproxy.mobile

import org.junit.Assert.*
import org.junit.Test

class AccountPolicyTest {
    @Test fun ownershipCannotBeChangedByReauthorization() {
        assertTrue(AccountPolicy.canBind(11, 11, 100, false))
        assertFalse(AccountPolicy.canBind(11, 22, 100, true))
        assertFalse(AccountPolicy.canBind(0, 22, 100, false))
        assertTrue(AccountPolicy.canBind(0, 11, 100, true))
        assertTrue(AccountPolicy.canBind(0, 22, 0, false))
        assertFalse(AccountPolicy.canBind(0, 0, 0, true))
    }
}
