package com.silentyeti.pluginless;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestsTest {
    @Test
    void requestsExpireCannotBeReplacedAndAreConsumedOnce() {
        var requests = new Requests<Long>();
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var c = UUID.randomUUID();
        assertFalse(requests.put(a, a, 100L, 1000, 0));
        assertTrue(requests.put(a, b, 100L, 1000, 0));
        assertFalse(requests.put(c, b, 1000L, 1000, 1));
        assertEquals(100L, requests.get(b, 999).value().longValue());
        assertNull(requests.take(b, 1000));
        assertTrue(requests.put(c, b, 1000L, 1000, 1000));
        assertEquals(c, requests.take(b, 1001).sender());
        assertNull(requests.take(b, 1001));
        assertTrue(requests.put(a, b, 100L, 1000, 0));
        requests.remove(a);
        assertNull(requests.get(b, 0));
    }
}
