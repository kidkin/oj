package top.hcode.hoj.edu;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EduDisplayOrderTest {
    @Test void displayNumbersHaveExplicitBounds() {
        for (long value : new long[]{0,-1,2147483648L,Long.MAX_VALUE})
            assertEquals(400, assertThrows(EduException.class, () -> EduDisplayOrder.validate(value)).getStatus());
        EduDisplayOrder.validate(1L); EduDisplayOrder.validate(2147483647L); EduDisplayOrder.validate(null);
    }
    @Test void legacyQuestionListsGetOneBasedNumbers() {
        assertEquals(Arrays.asList(1L,2L,3L), EduDisplayOrder.problems(Arrays.asList(50L,20L,99L),null));
        assertTrue(EduDisplayOrder.problems(Collections.emptyList(),null).isEmpty());
    }
    @Test void suppliedNumbersRemainAttachedToTheirQuestion() {
        assertEquals(Arrays.asList(30L,2L,10L), EduDisplayOrder.problems(Arrays.asList(50L,20L,99L),Arrays.asList(30L,2L,10L)));
    }
    @Test void duplicateMissingAndMismatchedQuestionNumbersAreRejected() {
        List<Long> ids=Arrays.asList(50L,20L);
        for (List<Long> values : Arrays.asList(Arrays.asList(1L,1L),Arrays.asList(null,2L),Collections.singletonList(1L),Arrays.asList(0L,2L)))
            assertEquals(400,assertThrows(EduException.class,()->EduDisplayOrder.problems(ids,values)).getStatus());
    }
    @Test void requestNumbersCannotBeSilentlyRoundedOrTruncated() {
        for (String value : Arrays.asList("1.5","1e2"," 2","+2","01","2147483648",""))
            assertEquals(400,assertThrows(EduException.class,()->EduDisplayOrder.parse(value)).getStatus());
        assertEquals(Long.valueOf(10),EduDisplayOrder.parse("10"));
        assertNull(EduDisplayOrder.parse(null));
    }
}
